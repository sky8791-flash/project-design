package com.collabdoc.dto;

import java.time.LocalDateTime;

/** A share row joined with the sharee's identity, which the raw entity does not carry. */
public class DocumentShareView {

    private Long shareId;
    private Long userId;
    private String username;
    private String userCode;
    private String permission;
    private Long sharedBy;
    private LocalDateTime createdAt;

    public DocumentShareView() {}

    public DocumentShareView(Long shareId, Long userId, String username, String userCode,
                             String permission, Long sharedBy, LocalDateTime createdAt) {
        this.shareId = shareId;
        this.userId = userId;
        this.username = username;
        this.userCode = userCode;
        this.permission = permission;
        this.sharedBy = sharedBy;
        this.createdAt = createdAt;
    }

    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getUserCode() { return userCode; }
    public void setUserCode(String userCode) { this.userCode = userCode; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public Long getSharedBy() { return sharedBy; }
    public void setSharedBy(Long sharedBy) { this.sharedBy = sharedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
