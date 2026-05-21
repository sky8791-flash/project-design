package com.collabdoc.websocket;

import com.collabdoc.dto.DocumentState;
import com.collabdoc.dto.EditOperation;
import com.collabdoc.ot.OTOperation;
import com.collabdoc.pattern.observer.DocumentObserver;
import com.collabdoc.pattern.observer.DocumentSubject;
import com.collabdoc.pattern.observer.WebSocketObserver;
import com.collabdoc.service.DocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DocumentWebSocketHandler extends TextWebSocketHandler {

    private final DocumentService documentService;
    private final DocumentSubject documentSubject;
    private final ObjectMapper objectMapper;
    private final WebSocketSessionManager sessionManager;
    private final Map<WebSocketSession, DocumentObserver> sessionObservers = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, String> sessionUserIds = new ConcurrentHashMap<>();

    public DocumentWebSocketHandler(DocumentService documentService,
                                    DocumentSubject documentSubject,
                                    ObjectMapper objectMapper,
                                    WebSocketSessionManager sessionManager) {
        this.documentService = documentService;
        this.documentSubject = documentSubject;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String documentId = extractDocumentId(session);
        String userId = extractUserId(session);

        sessionManager.register(documentId, session);
        sessionUserIds.put(session, userId);

        WebSocketObserver observer = new WebSocketObserver(sessionManager);
        observer.init(userId, documentId);
        documentSubject.attach(observer);
        sessionObservers.put(session, observer);

        DocumentState state = documentService.getDocumentState(Long.parseLong(documentId));
        String stateJson = objectMapper.writeValueAsString(Map.of(
            "type", "INIT",
            "content", state.getContent(),
            "version", state.getVersion(),
            "onlineCount", state.getOnlineCount()
        ));
        session.sendMessage(new org.springframework.web.socket.TextMessage(stateJson));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, org.springframework.web.socket.TextMessage message) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(message.getPayload());
        String type = jsonNode.get("type").asText();

        switch (type) {
            case "EDIT" -> handleEdit(jsonNode, session);
            case "CURSOR" -> handleCursor(jsonNode, session);
            default -> {}
        }
    }

    private void handleEdit(JsonNode jsonNode, WebSocketSession session) throws Exception {
        String documentId = jsonNode.get("documentId").asText();
        String userId = extractUserId(session);

        EditOperation editOp = new EditOperation();
        editOp.setDocumentId(documentId);
        editOp.setUserId(Long.parseLong(userId));
        editOp.setOperationType(OTOperation.Type.valueOf(jsonNode.get("operationType").asText()));
        editOp.setPosition(jsonNode.get("position").asInt());
        if (jsonNode.has("text")) editOp.setText(jsonNode.get("text").asText());
        if (jsonNode.has("length")) editOp.setLength(jsonNode.get("length").asInt());
        editOp.setBaseVersion(jsonNode.get("baseVersion").asInt());

        DocumentState result = documentService.applyOperation(editOp);
    }

    private void handleCursor(JsonNode jsonNode, WebSocketSession session) throws Exception {
        String documentId = jsonNode.get("documentId").asText();
        String userId = extractUserId(session);

        Map<String, Object> payload = Map.of(
            "type", "CURSOR_UPDATE",
            "userId", userId,
            "position", jsonNode.get("position").asInt()
        );

        sessionManager.broadcastExcept(documentId, payload, session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String documentId = extractDocumentId(session);
        String userId = sessionUserIds.remove(session);
        sessionManager.unregister(documentId, session);

        DocumentObserver observer = sessionObservers.remove(session);
        if (observer != null) {
            documentSubject.detach(observer);
        }

        Map<String, Object> payload = Map.of(
            "type", "USER_LEFT",
            "userId", userId != null ? userId : "unknown",
            "onlineCount", sessionManager.getOnlineCount(documentId)
        );
        sessionManager.broadcast(documentId, payload);
    }

    private String extractDocumentId(WebSocketSession session) {
        String uri = session.getUri().getPath();
        String[] parts = uri.split("/");
        return parts[parts.length - 1];
    }

    private String extractUserId(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (query != null && query.contains("userId=")) {
            return query.split("userId=")[1].split("&")[0];
        }
        return "anonymous";
    }
}
