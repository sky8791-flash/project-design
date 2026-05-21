package com.collabdoc.dto;

import java.time.LocalDateTime;

public class OperationLogDTO {

    private Long id;
    private Long documentId;
    private Long userId;
    private String username;
    private String commandType;
    private String commandParams;
    private Integer version;
    private LocalDateTime createdAt;

    public OperationLogDTO() {}

    public OperationLogDTO(Long id, Long documentId, Long userId, String username, 
                           String commandType, String commandParams, Integer version, LocalDateTime createdAt) {
        this.id = id;
        this.documentId = documentId;
        this.userId = userId;
        this.username = username;
        this.commandType = commandType;
        this.commandParams = commandParams;
        this.version = version;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getCommandType() { return commandType; }
    public void setCommandType(String commandType) { this.commandType = commandType; }
    public String getCommandParams() { return commandParams; }
    public void setCommandParams(String commandParams) { this.commandParams = commandParams; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}