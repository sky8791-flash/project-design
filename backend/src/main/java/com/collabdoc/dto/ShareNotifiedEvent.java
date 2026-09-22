package com.collabdoc.dto;

/** A share invite that has been persisted and may now be pushed to the recipient's open sessions. */
public class ShareNotifiedEvent {

    private final Long recipientId;
    private final Long notificationId;
    private final Long documentId;
    private final String message;
    private final java.time.LocalDateTime createdAt;

    public ShareNotifiedEvent(Long recipientId, Long notificationId, Long documentId,
                              String message, java.time.LocalDateTime createdAt) {
        this.recipientId = recipientId;
        this.notificationId = notificationId;
        this.documentId = documentId;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getRecipientId() { return recipientId; }
    public Long getNotificationId() { return notificationId; }
    public Long getDocumentId() { return documentId; }
    public String getMessage() { return message; }
    public java.time.LocalDateTime getCreatedAt() { return createdAt; }
}
