package com.collabdoc.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A persisted notification: a share invite has to survive the recipient being offline, which is why
 * this is a table and not just a socket frame.
 */
@Entity
@Table(name = "notification",
        indexes = @Index(name = "idx_notif_user_read", columnList = "user_id,read_flag"))
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "document_id")
    private Long documentId;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 255)
    private String message;

    @Column(name = "read_flag", nullable = false)
    private boolean read = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Notification() {}

    public Notification(Long userId, Long documentId, String type, String message) {
        this.userId = userId;
        this.documentId = documentId;
        this.type = type;
        this.message = message;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getDocumentId() { return documentId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
