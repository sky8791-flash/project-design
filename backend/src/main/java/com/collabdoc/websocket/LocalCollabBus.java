package com.collabdoc.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Default bus: one process, so "every node" is this node and delivery is a direct call. Selected unless
 * {@code app.collab.bus=redis}, which is the mode required for more than one instance.
 */
@Component
@ConditionalOnProperty(name = "app.collab.bus", havingValue = "memory", matchIfMissing = true)
public class LocalCollabBus implements CollabBus {

    private static final Logger log = LoggerFactory.getLogger(LocalCollabBus.class);

    private final LocalDelivery delivery;

    public LocalCollabBus(LocalDelivery delivery) {
        this.delivery = delivery;
        // A misspelled app.collab.bus matches neither bus, and the resulting missing-bean failure names
        // DocumentService rather than the property. Logging the mode that won makes the two-mode split
        // visible in the startup output of each replica.
        log.info("Collab bus: memory (frames do not cross processes)");
    }

    @Override
    public void sessionJoined(String documentId, String sessionId) {
        // The local session map is already the membership record.
    }

    @Override
    public void sessionLeft(String documentId, String sessionId) {
    }

    @Override
    public int onlineCount(String documentId) {
        return delivery.localOnlineCount(documentId);
    }

    @Override
    public void broadcast(String documentId, Map<String, Object> frame, String excludeSessionId) {
        delivery.deliverToDocument(documentId, frame, excludeSessionId);
    }

    @Override
    public void toUser(Long userId, Map<String, Object> frame) {
        delivery.deliverToUser(userId, frame);
    }

    @Override
    public void deliverLocal(String documentId, String sessionId, Map<String, Object> frame) {
        delivery.sendLocal(documentId, sessionId, frame);
    }
}
