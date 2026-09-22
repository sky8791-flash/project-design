package com.collabdoc.pattern.observer;

import com.collabdoc.dto.ContentAppliedEvent;
import com.collabdoc.websocket.CollabBus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fans a committed document change out over the socket. Presence is measured here, at send time, from the
 * session registry rather than from observer counts, and the registry spans nodes when the bus does.
 */
public class WebSocketObserver implements DocumentObserver {

    private final CollabBus bus;
    private final String documentId;

    public WebSocketObserver(CollabBus bus, String documentId) {
        this.bus = bus;
        this.documentId = documentId;
    }

    @Override
    public void update(ContentAppliedEvent event) {
        if (!documentId.equals(String.valueOf(event.getDocumentId()))) return;

        // The unicast goes out first: it is the sender's permission to release its next batch, and
        // otClient's flush() stalls forever if it never arrives. It involves no broker round trip, so a
        // Redis outage cannot take it down with the broadcast, and the origin session is excluded from the
        // broadcast below, so the two never compete.
        Map<String, Object> originFrame = event.getOriginFrame();
        if (originFrame != null && event.getOriginSessionId() != null) {
            bus.deliverLocal(documentId, event.getOriginSessionId(), originFrame);
        }

        Map<String, Object> frame = event.getFrame();
        if (frame == null) return;

        Map<String, Object> withPresence = new LinkedHashMap<>(frame);
        withPresence.put("onlineCount", bus.onlineCount(documentId));
        bus.broadcast(documentId, withPresence, event.getOriginSessionId());
    }

    @Override
    public String getDocumentId() { return documentId; }
}
