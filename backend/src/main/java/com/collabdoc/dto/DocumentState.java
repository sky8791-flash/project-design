package com.collabdoc.dto;

import java.time.LocalDateTime;

public class DocumentState {

    private String documentId;
    private String title;
    private String content;
    private String contentFormat;
    private int version;

    /**
     * The version {@code content} actually represents. It lags {@code version} because the server is a
     * sequencer: the client that joins must replay {@code operation_log} from here up to {@code version}.
     */
    private int checkpointVersion;

    private int onlineCount;
    private LocalDateTime lastUpdated;

    public DocumentState() {}

    public DocumentState(String documentId, String content, int version, int onlineCount,
                         LocalDateTime lastUpdated) {
        this(documentId, content, version, onlineCount, lastUpdated, "html");
    }

    public DocumentState(String documentId, String content, int version, int onlineCount,
                         LocalDateTime lastUpdated, String contentFormat) {
        this(documentId, content, version, onlineCount, lastUpdated, contentFormat, version);
    }

    public DocumentState(String documentId, String content, int version, int onlineCount,
                         LocalDateTime lastUpdated, String contentFormat, int checkpointVersion) {
        this.documentId = documentId;
        this.content = content;
        this.version = version;
        this.onlineCount = onlineCount;
        this.lastUpdated = lastUpdated;
        this.contentFormat = contentFormat;
        this.checkpointVersion = checkpointVersion;
    }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentFormat() { return contentFormat; }
    public void setContentFormat(String contentFormat) { this.contentFormat = contentFormat; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public int getCheckpointVersion() { return checkpointVersion; }
    public void setCheckpointVersion(int checkpointVersion) { this.checkpointVersion = checkpointVersion; }
    public int getOnlineCount() { return onlineCount; }
    public void setOnlineCount(int onlineCount) { this.onlineCount = onlineCount; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
