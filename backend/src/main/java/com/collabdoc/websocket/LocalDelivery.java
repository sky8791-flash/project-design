package com.collabdoc.websocket;

import java.util.Map;

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
     * Visits every session still registered with this process, reporting whether each one is open, so a bus
     * that tracks membership remotely can re-assert the live ones and discover the ones no close callback
     * ever reached. Skipping a non-open session would hide exactly the member that needs dropping.
     */
    void forEachLiveSession(SessionVisitor visitor);

    interface SessionVisitor {

        void visit(String documentId, String sessionId, boolean open);
    }
}
