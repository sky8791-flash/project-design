package com.collabdoc.dto;

import java.time.LocalDateTime;

public class DocumentState {

    private String documentId;
    private String content;
    private int version;
    private int onlineCount;
    private LocalDateTime lastUpdated;

    public DocumentState() {}

    public DocumentState(String documentId, String content, int version, int onlineCount, LocalDateTime lastUpdated) {
        this.documentId = documentId;
        this.content = content;
        this.version = version;
        this.onlineCount = onlineCount;
        this.lastUpdated = lastUpdated;
    }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public int getOnlineCount() { return onlineCount; }
    public void setOnlineCount(int onlineCount) { this.onlineCount = onlineCount; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
