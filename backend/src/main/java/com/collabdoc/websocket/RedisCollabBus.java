package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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
    // trustworthy. The queue itself cannot be capped — dropping an owed removal recreates the ghost.
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
     * Closed sessions whose removal the heartbeat still owes. A missed {@code HDEL} is not self-healing the
     * way a missed {@code HSET} is — the sweep only ever adds, and pruning drops only fields of nodes whose
     * lease lapsed — so a ghost member carries this node's own live id and inflates {@code onlineCount} on
     * that document until this node's lease lapses or it restarts under a new id. Closing therefore records
     * the intent first and lets the heartbeat clear it only once the delete lands, because a tick that
     * snapshotted the session before it closed would otherwise re-add it.
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
        try {
            delivery.forEachLiveSession((documentId, sessionId) ->
                    byDocument.computeIfAbsent(documentId, k -> new HashMap<>()).put(sessionId, nodeId));
        } catch (RuntimeException e) {
            log.warn("Could not snapshot local sessions for the membership tick: {}", e.getMessage());
        }

        // Fields this node still owes a removal for. The HSETALL must not re-assert them, which is what stops a
        // tick that snapshotted a session moments before it closed from putting a ghost back; the HDEL in the
        // same pass clears the intent, and only on success, because a field naming a live node is pruned by
        // nobody else.
        Map<String, Set<String>> owed = new HashMap<>();
        for (OwedRemoval removal : Set.copyOf(pendingRemovals)) {
            owed.computeIfAbsent(removal.documentId(), k -> new HashSet<>()).add(removal.sessionId());
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

            Set<String> ghosts = owed.get(documentId);
            Map<String, String> members = byDocument.get(documentId);
            if (members != null) {
                if (ghosts != null) members.keySet().removeAll(ghosts);
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
                try {
                    redis.opsForHash().delete(DOC_SESSIONS + documentId, ghosts.toArray());
                    ghosts.forEach(field -> pendingRemovals.remove(new OwedRemoval(documentId, field)));
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
    RedisMessageListenerContainer collabFrameListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            FrameEnvelope envelope = read(new String(message.getBody(), StandardCharsets.UTF_8));
            if (envelope == null) return;
            // The document id decides which kind of frame this is. A frame carrying neither is dropped
            // rather than handed to a map lookup keyed on null.
            if (envelope.documentId() != null) {
                delivery.deliverToDocument(envelope.documentId(), envelope.frame(), envelope.excludeSessionId());
            } else if (envelope.userId() != null) {
                delivery.deliverToUser(envelope.userId(), envelope.frame());
            }
        }, new ChannelTopic(CHANNEL));
        return container;
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
}
