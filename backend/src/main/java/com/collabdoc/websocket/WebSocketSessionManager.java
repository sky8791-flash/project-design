package com.collabdoc.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Set<WebSocketSession>> documentSessions = new ConcurrentHashMap<>();

    public void register(String documentId, WebSocketSession session) {
        documentSessions.computeIfAbsent(documentId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String documentId, WebSocketSession session) {
        Set<WebSocketSession> sessions = documentSessions.get(documentId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                documentSessions.remove(documentId);
            }
        }
    }

    public void broadcast(String documentId, Map<String, Object> payload) {
        Set<WebSocketSession> sessions = documentSessions.get(documentId);
        if (sessions == null) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }

        TextMessage textMessage = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    log.warn("Failed to send broadcast to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    public void broadcastExcept(String documentId, Map<String, Object> payload, WebSocketSession excludeSession) {
        Set<WebSocketSession> sessions = documentSessions.get(documentId);
        if (sessions == null) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (IOException e) {
            return;
        }

        TextMessage textMessage = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (session.isOpen() && !session.equals(excludeSession)) {
                try {
                    session.sendMessage(textMessage);
                } catch (IOException e) {
                    log.warn("Failed to send broadcast-except to session {}: {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    public int getOnlineCount(String documentId) {
        Set<WebSocketSession> sessions = documentSessions.get(documentId);
        if (sessions == null) return 0;
        return (int) sessions.stream().filter(WebSocketSession::isOpen).count();
    }
}
