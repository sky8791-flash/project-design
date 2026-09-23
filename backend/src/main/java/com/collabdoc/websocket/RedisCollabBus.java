package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cross-process fan-out. Every node subscribes to one channel and delivers only to the sessions it holds,
 * so a write sequenced on node A reaches a client parked on node B.
 *
 * <p>The publishing node does not shortcut delivery to its own sessions: it receives its own message like
 * everybody else, which keeps one delivery path and therefore one place where the origin session is
 * excluded.</p>
 *
 * <p>Membership lives in one hash per document, {@code sessionId -> nodeId}. Frames do not need it —
 * they go to every node — but {@code onlineCount} does, and a count must not be trusted from a node that
 * died mid-session, so entries are read through node liveness keys and dead ones are dropped.</p>
 */
@Component
@ConditionalOnProperty(name = "app.collab.bus", havingValue = "redis")
class RedisCollabBus implements CollabBus {

    static final String CHANNEL = "collab:frames";
    private static final Logger log = LoggerFactory.getLogger(RedisCollabBus.class);
    private static final String DOC_SESSIONS = "collab:doc:";
    private static final String NODE = "collab:node:";
    private static final Duration NODE_TTL = Duration.ofSeconds(30);
    // How many documents one tick may visit once their Redis calls start failing. Counts *failed* documents,
    // not attempts, so a poison key (a wrong-type value at collab:doc:X) cannot push the healthy documents
    // behind it out of the tick — and rotation keeps "behind it" from meaning "forever".
    private static final int MAX_FAILED_DOCUMENTS_PER_TICK = 8;
    // An alarm, not a bound: only the failing branch reads the size, so it fires once Redis has been
    // unreachable long enough for the owed-removal queue to grow, which is exactly when presence stops being
    // trustworthy. The queue is not capped: an intent dropped here is a member nobody else can ever prune.
    private static final int PENDING_REMOVALS_LOG_THRESHOLD = 1_000;
    // Keeps one tick well inside {@link #NODE_TTL}. Each call to a stalled Redis costs the full read timeout,
    // so without a budget a node holding ~150 documents would spend longer between lease renewals than the
    // lease lasts and watch peers prune the members of a node that is alive.
    private static final Duration TICK_BUDGET = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LocalDelivery delivery;
    private final String nodeId = UUID.randomUUID().toString();
    /**
     * Removals the heartbeat still owes: a session whose close was announced here, or one it found registered
     * but no longer open. A missed {@code HDEL} is not self-healing the way a missed {@code HSET} is — the
     * sweep only ever adds, and pruning drops only fields of nodes whose lease lapsed — so a ghost member
     * carries this node's own live id and inflates {@code onlineCount} on that document until this node's
     * lease lapses or it restarts under a new id. {@code sessionLeft} records the intent even when its own
     * delete lands, because a tick that snapshotted the session before it closed would otherwise re-add it,
     * and an intent is cleared only once the delete lands.
     */
    private final Set<OwedRemoval> pendingRemovals = ConcurrentHashMap.newKeySet();
    /**
     * Where in the document list the last tick stopped working, so the next one starts there. Touched only by
     * the single scheduler thread that runs {@link #membershipHeartbeat()}.
     */
    private int rotation;

    RedisCollabBus(StringRedisTemplate redis, ObjectMapper objectMapper, LocalDelivery delivery) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.delivery = delivery;
        log.info("Collab bus: redis (channel {}, node {})", CHANNEL, nodeId);
        try {
            redis.hasKey(NODE + nodeId);
        } catch (Exception e) {
            // Loud but not fatal: frames are dropped and onlineCount degrades to this node until Redis is
            // reachable. Management health stays off by default, so without this line the outage is invisible.
            log.error("Redis is unreachable in collab-bus=redis mode; realtime frames will be dropped "
                    + "until it answers: {}", e.getMessage());
        }
    }

    /**
     * Publishes rather than delivering locally first: one path, so the bus cannot diverge between the node
     * that wrote and the nodes that only listen.
     */
    @Override
    public void broadcast(String documentId, Map<String, Object> frame, String excludeSessionId) {
        publish(new FrameEnvelope(null, documentId, excludeSessionId, frame));
    }

    @Override
    public void toUser(Long userId, Map<String, Object> frame) {
        publish(new FrameEnvelope(userId, null, null, frame));
    }

    @Override
    public void deliverLocal(String documentId, String sessionId, Map<String, Object> frame) {
        delivery.sendLocal(documentId, sessionId, frame);
    }

    @Override
    public void sessionJoined(String documentId, String sessionId) {
        try {
            redis.opsForHash().put(DOC_SESSIONS + documentId, sessionId, nodeId);
        } catch (Exception e) {
            log.warn("Could not record membership for session {} on doc {}: {}", sessionId, documentId, e.getMessage());
        }
    }

    @Override
    public void sessionLeft(String documentId, String sessionId) {
        // Recorded even when the delete below succeeds, and cleared only by a delete that landed: a tick that
        // snapshotted this session before it closed would otherwise re-add it.
        pendingRemovals.add(new OwedRemoval(documentId, sessionId));
        try {
            redis.opsForHash().delete(DOC_SESSIONS + documentId, sessionId);
        } catch (Exception e) {
            if (pendingRemovals.size() > PENDING_REMOVALS_LOG_THRESHOLD) {
                log.error("Deferring membership removals for {} sessions; Redis has been unreachable long "
                        + "enough that presence cannot be trusted on this node", pendingRemovals.size());
            } else {
                log.warn("Could not drop membership for session {} on doc {}, retrying on the next tick: {}",
                        sessionId, documentId, e.getMessage());
            }
        }
    }

    /**
     * Degrades to this node's own count rather than throwing: callers include the read path behind
     * {@code GET /api/documents/{id}} and {@code INIT}, where a Redis outage must not turn into a 500 or a
     * closed socket while MySQL is perfectly healthy.
     */
    @Override
    public int onlineCount(String documentId) {
        String key = DOC_SESSIONS + documentId;
        Map<Object, Object> members;
        try {
            members = redis.opsForHash().entries(key);
        } catch (Exception e) {
            log.warn("Falling back to the local session count for doc {}: {}", documentId, e.getMessage());
            return delivery.localOnlineCount(documentId);
        }
        if (members.isEmpty()) return 0;

        // One liveness probe per distinct node, not per session: this runs while the document row lock is
        // held, and a 50-member document would otherwise cost 50 round trips at the configured timeout.
        Set<Object> nodes = new HashSet<>(members.values());
        Set<Object> liveNodes = new HashSet<>();
        try {
            for (Object node : nodes) {
                if (Boolean.TRUE.equals(redis.hasKey(NODE + node))) liveNodes.add(node);
            }
        } catch (Exception e) {
            log.warn("Counting doc {} from this node only: {}", documentId, e.getMessage());
            return delivery.localOnlineCount(documentId);
        }

        List<Object> dead = new ArrayList<>();
        int live = 0;
        for (Map.Entry<Object, Object> entry : members.entrySet()) {
            if (liveNodes.contains(entry.getValue())) {
                live++;
            } else {
                dead.add(entry.getKey());
            }
        }
        if (!dead.isEmpty()) {
            try {
                redis.opsForHash().delete(key, dead.toArray());
            } catch (Exception e) {
                log.warn("Could not prune {} stale members of doc {}: {}", dead.size(), documentId, e.getMessage());
            }
        }
        return live;
    }

    /**
     * Renews the node lease, then visits every document that needs it with at most one {@code HSETALL} and one
     * {@code HDEL} each.
     *
     * <p>The lease is renewed <em>first</em> and unconditionally: gating it on "every document succeeded"
     * would let one poisoned key cost the whole node its lease and have peers prune every document this node
     * holds, and renewing it last would let the round trips below push the next renewal past
     * {@link #NODE_TTL}. {@link #TICK_BUDGET} caps the rest so a stalled Redis cannot do that either.</p>
     *
     * <p>Because the budget and {@link #MAX_FAILED_DOCUMENTS_PER_TICK} can cut the visit short, the next tick
     * resumes at {@link #rotation} rather than at the head of the list — the list is rebuilt the same way every
     * tick, so resuming at 0 would starve the same tail indefinitely, and after a lapsed lease only this sweep
     * can put this node's own members back. Resuming is not exact: when documents leave the list the cursor can
     * pass over a slot, which costs one tick, not a permanent stall.</p>
     */
    @Scheduled(fixedRate = 10_000)
    void membershipHeartbeat() {
        try {
            redis.opsForValue().set(NODE + nodeId, "1", NODE_TTL);
        } catch (Exception e) {
            log.warn("Could not renew collab node lease: {}", e.getMessage());
        }

        Map<String, Map<String, String>> byDocument = new HashMap<>();
        Set<OwedRemoval> lingering = new HashSet<>();
        try {
            delivery.forEachLiveSession((documentId, sessionId, open) -> {
                if (open) {
                    byDocument.computeIfAbsent(documentId, k -> new HashMap<>()).put(sessionId, nodeId);
                } else {
                    lingering.add(new OwedRemoval(documentId, sessionId));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not snapshot local sessions for the membership tick: {}", e.getMessage());
        }
        // A session that went non-open without afterConnectionClosed running is never reported to sessionLeft,
        // so the comparison above is the only way this node learns its member is a ghost — and a field naming
        // a live node is pruned by nobody.
        pendingRemovals.addAll(lingering);

        // Sessions this node owes a removal for. The HSETALL must not re-assert them, which is what stops a
        // tick that snapshotted a session moments before it closed from putting a ghost back; the HDEL in the
        // same pass clears the intent, and only on success.
        Map<String, Set<OwedRemoval>> owed = new HashMap<>();
        for (OwedRemoval removal : Set.copyOf(pendingRemovals)) {
            owed.computeIfAbsent(removal.documentId(), k -> new HashSet<>()).add(removal);
        }

        Set<String> documents = new TreeSet<>(byDocument.keySet());
        documents.addAll(owed.keySet());
        List<String> order = List.copyOf(documents);
        long deadline = System.nanoTime() + TICK_BUDGET.toNanos();
        int visited = 0;
        int failures = 0;
        for (int i = 0; i < order.size(); i++) {
            if (failures >= MAX_FAILED_DOCUMENTS_PER_TICK || System.nanoTime() >= deadline) break;
            String documentId = order.get((i + rotation) % order.size());
            visited++;
            boolean broken = false;

            Set<OwedRemoval> ghosts = owed.get(documentId);
            Map<String, String> members = byDocument.get(documentId);
            if (members != null) {
                if (ghosts != null) ghosts.forEach(removal -> members.remove(removal.sessionId()));
                if (!members.isEmpty()) {
                    try {
                        redis.opsForHash().putAll(DOC_SESSIONS + documentId, members);
                    } catch (Exception e) {
                        broken = true;
                        log.warn("Could not re-assert membership for doc {}: {}", documentId, e.getMessage());
                    }
                }
            }
            // One varargs call for the whole document: an outage that closed sessions across 50 documents
            // costs 50 round trips, not one per closed session.
            if (ghosts != null) {
                Object[] fields = ghosts.stream().map(OwedRemoval::sessionId).toArray();
                try {
                    redis.opsForHash().delete(DOC_SESSIONS + documentId, fields);
                    pendingRemovals.removeAll(ghosts);
                } catch (Exception e) {
                    broken = true;
                    log.warn("Could not drop {} membership field(s) on doc {}, retrying on the next tick: {}",
                            ghosts.size(), documentId, e.getMessage());
                }
            }
            if (broken) failures++;
        }
        rotation = order.isEmpty() ? 0 : (rotation + visited) % order.size();
        if (visited < order.size()) {
            log.warn("Membership tick stopped after {} of {} document(s), {} still waiting",
                    visited, order.size(), order.size() - visited);
        }
    }

    private void publish(FrameEnvelope envelope) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // Never propagate: this runs from post-commit cleanup and from socket handlers, where an
            // exception costs either a 5xx on a write that already committed or a closed connection.
            // A lost frame is recoverable — every frame carries the version, so the client refetches the gap.
            log.warn("Dropped a collab frame on the way to {}: {}", CHANNEL, e.getMessage(), e);
        }
    }

    @Bean
    CollabFrameListener collabFrameListener(RedisConnectionFactory factory) {
        return new CollabFrameListener(factory, this::onFrame);
    }

    /** The receive half of the bus: one envelope in, delivered to whichever sessions this node holds. */
    void onFrame(Message message, byte[] pattern) {
        FrameEnvelope envelope = read(new String(message.getBody(), StandardCharsets.UTF_8));
        if (envelope == null) return;
        // The document id decides which kind of frame this is. A frame carrying neither is dropped
        // rather than handed to a map lookup keyed on null.
        if (envelope.documentId() != null) {
            delivery.deliverToDocument(envelope.documentId(), envelope.frame(), envelope.excludeSessionId());
        } else if (envelope.userId() != null) {
            delivery.deliverToUser(envelope.userId(), envelope.frame());
        }
    }

    private FrameEnvelope read(String json) {
        try {
            return objectMapper.readValue(json, FrameEnvelope.class);
        } catch (Exception e) {
            log.warn("Discarding unparsable collab frame: {}", e.getMessage());
            return null;
        }
    }

    /** A session this node still owes a membership removal for. */
    private record OwedRemoval(String documentId, String sessionId) {}

    record FrameEnvelope(Long userId, String documentId, String excludeSessionId, Map<String, Object> frame) {}

    /**
     * Owns the subscriber and starts it off the context's critical path, retrying until Redis answers.
     *
     * <p>Spring Data's {@code RedisMessageListenerContainer} implements {@code SmartLifecycle} and has no
     * auto-startup switch, so exposing it as a bean means an unreachable Redis fails {@code start()} during
     * context refresh and the application comes up as a failure: a node that boots a moment before its Redis,
     * or restarts while Redis is down, never joins the cluster at all. That contradicts what this bus
     * promises everywhere else — frames are recoverable, every one carries a version and a client that sees a
     * gap refetches it — so the coherent behaviour is to start degraded, say so loudly, and subscribe as soon
     * as the server responds. Once subscribed, the container's own recovery handles later drops.</p>
     */
    static class CollabFrameListener implements SmartLifecycle {

        private static final Duration FIRST_RETRY = Duration.ofSeconds(5);
        // Redis being down for an hour should not cost 720 warnings, so the gap widens until it is refused.
        private static final Duration RETRY_CAP = Duration.ofMinutes(1);
        private final RedisConnectionFactory factory;
        private final MessageListener listener;
        private final Duration firstRetry;
        private final Duration retryCap;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicReference<RedisMessageListenerContainer> container = new AtomicReference<>();
        private volatile Thread attempt;
        // Bumped under the {@code running} CAS, so writes to it are ordered by that CAS's volatile semantics.
        private volatile long generation;

        CollabFrameListener(RedisConnectionFactory factory, MessageListener listener) {
            this(factory, listener, FIRST_RETRY, RETRY_CAP);
        }

        /** The retry cadence is open to a test: nothing should have to wait five seconds to assert on it. */
        CollabFrameListener(RedisConnectionFactory factory, MessageListener listener,
                            Duration firstRetry, Duration retryCap) {
            this.factory = factory;
            this.listener = listener;
            this.firstRetry = firstRetry;
            this.retryCap = retryCap;
        }

        @Override
        public void start() {
            if (!running.compareAndSet(false, true)) return;
            long generation = ++this.generation;
            // The generation is in the thread name because two attempts of one lifecycle are otherwise
            // indistinguishable in the log, and this class is exactly where that would hurt.
            attempt = Thread.ofVirtual().name("collab-listener-attempt-", generation)
                    .start(() -> startUntilReachable(generation));
        }

        /**
         * Only the thread the latest {@link #start()} owns may keep trying or adopt a container.
         *
         * <p>{@code stop()} interrupts rather than joins — joining could block shutdown on a connect that
         * ignores interrupts — so a superseded attempt can wake up to find {@code running} true again, set by a
         * later {@code start()}. The generation is what separates that from a fresh lifecycle; without it the
         * old thread keeps retrying beside its replacement, and since {@code running} says {@code true} every
         * check it makes looks like a clean retry. It cannot subscribe twice (the CAS on {@code container}
         * allows one), but it would win that slot against the live attempt and then hold it — see
         * {@link #startUntilReachable}'s supersede checks, which are what make the losing path survivable.</p>
         *
         * <p>Lifecycle calls are assumed not to overlap: {@code start()} from a {@code stop()} that has not
         * finished is not a case this handles.</p>
         */
        private void startUntilReachable(long generation) {
            Duration delay = firstRetry;
            while (running.get() && this.generation == generation) {
                RedisMessageListenerContainer candidate = new RedisMessageListenerContainer();
                candidate.setConnectionFactory(factory);
                candidate.addMessageListener(listener, new ChannelTopic(CHANNEL));
                try {
                    // Not a bean, so nobody calls this for us — and without it the container has no subscriber
                    // to register with, which fails every attempt forever rather than just this one.
                    candidate.afterPropertiesSet();
                    candidate.start();
                    if (superseded(generation)) {
                        destroy(candidate, "superseded before it could be adopted");
                        return;
                    }
                    if (!container.compareAndSet(null, candidate)) {
                        // Somebody else's container is in the slot. Giving up here is what would leave the node
                        // subscribed by nobody, because the only other holder can be an attempt this one
                        // supersedes — and it releases the slot a moment after. Ours must not be orphaned either.
                        destroy(candidate, "superseded by the container already in the slot");
                        log.warn("Collab frame listener found {} already held by another attempt; retrying in {}",
                                CHANNEL, delay);
                    } else if (superseded(generation)) {
                        // Stopped after the adoption was won: that stop's {@code release()} may already have
                        // looked and found the slot empty, so this thread is the one that lets it go — but only
                        // while the slot still holds *this* container, or we would tear down a live subscription.
                        if (container.compareAndSet(candidate, null)) {
                            destroy(candidate, "released an adoption its stop never saw");
                        }
                        return;
                    } else {
                        log.info("Collab frame listener subscribed to {}", CHANNEL);
                        return;
                    }
                } catch (Exception e) {
                    destroy(candidate, "released after a failed attempt");
                    // The throwable rather than the stack: at the capped cadence an outage would otherwise
                    // cost a trace a minute, and {@code toString} carries the cause chain anyway.
                    log.warn("Collab frame listener could not subscribe; retrying in {}: {}", delay, e.toString());
                }
                if (!waitBeforeRetry(delay)) return;
                delay = delay.compareTo(retryCap) >= 0 ? retryCap : delay.multipliedBy(2);
            }
        }

        /** @return whether this attempt has been replaced by a newer one, or stood down. */
        private boolean superseded(long generation) {
            return generation != this.generation || !running.get();
        }

        /** @return false when the thread was told to stop while it waited. */
        private boolean waitBeforeRetry(Duration delay) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

        private void destroy(RedisMessageListenerContainer candidate, String whatHappened) {
            try {
                candidate.destroy();
            } catch (Exception e) {
                // Nothing was acquired in the usual case; worth hearing when it was, which happens for a
                // failure after the connection had already been obtained.
                log.warn("Collab frame listener could not be destroyed ({}): {}", whatHappened, e.toString());
            }
        }

        @Override
        public void stop() {
            running.set(false);
            Thread thread = attempt;
            if (thread != null) thread.interrupt();
            release();
        }

        /**
         * {@code destroy} rather than {@code stop}: the task executor the container builds in
         * {@code afterPropertiesSet} is only released by the former.
         */
        private void release() {
            RedisMessageListenerContainer subscribed = container.getAndSet(null);
            if (subscribed != null) destroy(subscribed, "releasing");
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }
    }
}
