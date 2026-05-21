package com.collabdoc.pattern.observer;

import com.collabdoc.websocket.WebSocketSessionManager;

import java.util.Map;

public class WebSocketObserver implements DocumentObserver {

    private final WebSocketSessionManager sessionManager;
    private String userId;
    private String documentId;

    public WebSocketObserver(WebSocketSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void init(String userId, String documentId) {
        this.userId = userId;
        this.documentId = documentId;
    }

    @Override
    public void update(String documentId, String content, int version) {
        if (this.documentId.equals(documentId)) {
            Map<String, Object> payload = Map.of(
                "type", "CONTENT_UPDATE",
                "documentId", documentId,
                "content", content,
                "version", version
            );
            sessionManager.broadcast(documentId, payload);
        }
    }

    @Override
    public String getUserId() { return userId; }

    @Override
    public String getDocumentId() { return documentId; }
}
