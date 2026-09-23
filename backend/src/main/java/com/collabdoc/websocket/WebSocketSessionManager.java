package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the sockets this process owns and writes to them; it is the {@link LocalDelivery} that whichever
 * {@link CollabBus} is active calls back into. Nothing here reaches a client on another node — that is
 * the bus's job — and unicasts (INIT, ACK, REJECT) stay local, because only this node has the session.
 */
@Component
public class WebSocketSessionManager implements LocalDelivery {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, WebSocketSession>> documentSessions = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionOwners = new ConcurrentHashMap<>();

    /**
     * Uses the Spring-managed mapper on purpose: a bare {@code new ObjectMapper()} cannot serialize
     * {@code LocalDateTime}, and frames carrying timestamps were dropped silently.
     */
    public WebSocketSessionManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Stores a serialized-send view of {@code session} under its id. Nothing may send on the raw session
     * passed in here: only the decorated one is safe against concurrent writes from broadcast threads.
     */
    public void register(String documentId, WebSocketSession session, Long userId) {
        WebSocketSession outbound =
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        documentSessions.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>())
                .put(session.getId(), outbound);
        sessionOwners.put(session.getId(), userId);
    }

    public void unregister(String documentId, WebSocketSession session) {
        sessionOwners.remove(session.getId());
        documentSessions.computeIfPresent(documentId, (key, sessions) -> {
            sessions.remove(session.getId());
            return sessions.isEmpty() ? null : sessions;
        });
    }

    @Override
    public void sendLocal(String documentId, String sessionId, Map<String, Object> payload) {
        Map<String, WebSocketSession> sessions = sessionsOf(documentId);
        if (sessions == null) return;
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) return;

        TextMessage message = serialize(payload);
        if (message != null) {
            deliver(session, message, documentId);
        }
    }

    @Override
    public void deliverToDocument(String documentId, Map<String, Object> payload, String excludeSessionId) {
        Map<String, WebSocketSession> sessions = sessionsOf(documentId);
        if (sessions == null) return;

        TextMessage message = serialize(payload);
        if (message == null) return;

        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            if (entry.getKey().equals(excludeSessionId)) continue;
            deliver(entry.getValue(), message, documentId);
        }
    }

    @Override
    public void deliverToUser(Long userId, Map<String, Object> payload) {
        if (userId == null) return;
        TextMessage message = serialize(payload);
        if (message == null) return;

        sessionOwners.forEach((sessionId, owner) -> {
            if (!userId.equals(owner)) return;
            documentSessions.forEach((documentId, sessions) -> {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null) {
                    deliver(session, message, documentId);
                }
            });
        });
    }

    @Override
    public int localOnlineCount(String documentId) {
        Map<String, WebSocketSession> sessions = sessionsOf(documentId);
        if (sessions == null) return 0;
        return (int) sessions.values().stream().filter(WebSocketSession::isOpen).count();
    }

    @Override
    public void forEachLiveSession(LocalDelivery.SessionVisitor visitor) {
        documentSessions.forEach((documentId, sessions) ->
                sessions.forEach((sessionId, session) -> {
                    // Report a non-open session rather than skipping it: the membership bus can only drop a
                    // field it is told about, and for these nothing ever tells it — afterConnectionClosed is
                    // the only other remover and it never runs for this state.
                    visitor.visit(documentId, sessionId, session.isOpen());
                    if (!session.isOpen()) {
                        sessions.remove(sessionId);
                        sessionOwners.remove(sessionId);
                        // Two-arg remove: a session registered for the same document in the meantime wins.
                        if (sessions.isEmpty()) documentSessions.remove(documentId, sessions);
                    }
                }));
    }

    /** The map keys are {@code ConcurrentHashMap}s, so a null document id must never reach {@code get}. */
    private Map<String, WebSocketSession> sessionsOf(String documentId) {
        return documentId == null ? null : documentSessions.get(documentId);
    }

    private TextMessage serialize(Map<String, Object> payload) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(payload));
        } catch (IOException e) {
            log.error("Failed to serialize ws payload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * One client must never abort a fan-out. Besides {@code IOException}, the decorator throws
     * unchecked when a slow consumer crosses the send time or buffer limit, and an escaping exception
     * here would skip every session after it in the loop.
     */
    private void deliver(WebSocketSession session, TextMessage message, String documentId) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(message);
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to send to session {} on doc {}: {}", session.getId(), documentId, e.getMessage());
        }
    }
}
