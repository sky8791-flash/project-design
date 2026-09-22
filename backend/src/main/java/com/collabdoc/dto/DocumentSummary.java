package com.collabdoc.dto;

import java.time.LocalDateTime;

/**
 * List metadata for one document, without its body. {@code permission} is {@code OWNER} for the
 * caller's own documents, otherwise the share permission.
 */
public class DocumentSummary {

    private String id;
    private String title;
    private int version;
    private String permission;
    private Long ownerId;
    private String ownerName;
    private LocalDateTime updatedAt;

    public DocumentSummary() {}

    public DocumentSummary(String id, String title, int version, String permission,
                           Long ownerId, String ownerName, LocalDateTime updatedAt) {
        this.id = id;
        this.title = title;
        this.version = version;
        this.permission = permission;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
