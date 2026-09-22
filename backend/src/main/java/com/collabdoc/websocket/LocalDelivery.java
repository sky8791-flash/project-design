package com.collabdoc.websocket;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Delivery into the sockets this process owns. Implemented by {@link WebSocketSessionManager} and called
 * back by whichever {@link CollabBus} is active, so the bus never needs to know how a session is held.
 */
public interface LocalDelivery {

    void deliverToDocument(String documentId, Map<String, Object> frame, String excludeSessionId);

    void deliverToUser(Long userId, Map<String, Object> frame);

    void sendLocal(String documentId, String sessionId, Map<String, Object> frame);

    int localOnlineCount(String documentId);

    /**
     * Visits every session this process still holds, so a bus that tracks membership remotely can
     * re-assert it instead of relying on the connect moment alone.
     */
    void forEachLiveSession(BiConsumer<String, String> visitor);
}
