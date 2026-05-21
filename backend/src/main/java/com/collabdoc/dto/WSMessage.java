package com.collabdoc.dto;

import java.util.Map;

public class WSMessage {

    private String type;
    private String documentId;
    private Long userId;
    private Map<String, Object> payload;

    public WSMessage() {}

    public WSMessage(String type, String documentId, Long userId, Map<String, Object> payload) {
        this.type = type;
        this.documentId = documentId;
        this.userId = userId;
        this.payload = payload;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
