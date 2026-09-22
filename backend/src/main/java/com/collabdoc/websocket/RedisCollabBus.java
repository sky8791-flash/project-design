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
import java.util.UUID;

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

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LocalDelivery delivery;
    private final String nodeId = UUID.randomUUID().toString();

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
        try {
            redis.opsForHash().delete(DOC_SESSIONS + documentId, sessionId);
        } catch (Exception e) {
            log.warn("Could not drop membership for session {} on doc {}: {}", sessionId, documentId, e.getMessage());
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
     * Refreshes this node's lease and re-asserts its membership. Both are needed because the connect moment
     * is not the only thing that matters: a reader prunes fields whose node lease has lapsed, and without
     * this tick a Redis flap longer than the TTL — or a socket that arrived before the first heartbeat —
     * would leave its sessions permanently missing from everyone's online count.
     */
    @Scheduled(fixedRate = 10_000)
    void renewNodeLease() {
        try {
            redis.opsForValue().set(NODE + nodeId, "1", NODE_TTL);
            Map<String, Map<String, String>> byDocument = new HashMap<>();
            delivery.forEachLiveSession((documentId, sessionId) ->
                    byDocument.computeIfAbsent(documentId, k -> new HashMap<>()).put(sessionId, nodeId));
            byDocument.forEach((documentId, members) ->
                    redis.opsForHash().putAll(DOC_SESSIONS + documentId, members));
        } catch (Exception e) {
            log.warn("Could not renew collab node lease: {}", e.getMessage(), e);
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

    record FrameEnvelope(Long userId, String documentId, String excludeSessionId, Map<String, Object> frame) {}
}
