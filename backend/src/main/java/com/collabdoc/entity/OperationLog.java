package com.collabdoc.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "operation_log",
        uniqueConstraints = @UniqueConstraint(name = "uk_oplog_doc_version",
                columnNames = {"document_id", "version"}))
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "command_type", nullable = false, length = 50)
    private String commandType;

    @Column(name = "command_params", columnDefinition = "JSON")
    private String commandParams;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public OperationLog() {}

    public OperationLog(Long documentId, Long userId, String commandType, String commandParams, Integer version) {
        this.documentId = documentId;
        this.userId = userId;
        this.commandType = commandType;
        this.commandParams = commandParams;
        this.version = version;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public Long getUserId() { return userId; }
    public String getCommandType() { return commandType; }
    public String getCommandParams() { return commandParams; }
    public Integer getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
