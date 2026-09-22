package com.collabdoc.websocket;

import com.collabdoc.dto.ShareNotifiedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pushes notifications to every session a user owns. Deliberately fired after commit: sending inside the
 * share transaction both showed recipients an invite that might still roll back, and blocked that
 * transaction on the socket send timeout while it held the document row lock.
 */
@Component
public class NotificationPusher {

    private final CollabBus bus;

    public NotificationPusher(CollabBus bus) {
        this.bus = bus;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShareNotified(ShareNotifiedEvent event) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "NOTIFICATION");
        frame.put("id", event.getNotificationId());
        frame.put("notificationType", "SHARE");
        frame.put("documentId", String.valueOf(event.getDocumentId()));
        frame.put("message", event.getMessage());
        frame.put("createdAt", event.getCreatedAt());
        bus.toUser(event.getRecipientId(), frame);
    }
}
