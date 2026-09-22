package com.collabdoc.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_snapshot_doc_version",
                columnNames = {"document_id", "version"}))
public class DocumentSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, updatable = false)
    private Long documentId;

    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String content;

    @Column(name = "content_format", nullable = false, length = 20)
    private String contentFormat = Document.FORMAT_HTML;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public DocumentSnapshot() {}

    public DocumentSnapshot(Long documentId, String content, String contentFormat, Integer version) {
        this.documentId = documentId;
        this.content = content;
        this.contentFormat = contentFormat;
        this.version = version;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDocumentId() { return documentId; }
    public String getContent() { return content; }
    public String getContentFormat() { return contentFormat; }
    public Integer getVersion() { return version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
