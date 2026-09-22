package com.collabdoc.dto;

import java.util.Map;

/**
 * Published once a document state change has been persisted. Routed to the document's observer by
 * {@code DocumentSubjectImpl} after the writing transaction commits.
 *
 * <p>{@code frame} goes to every session of the document except {@code originSessionId};
 * {@code originFrame} is delivered to the origin session only (its acknowledgement).</p>
 */
public class ContentAppliedEvent {

    private final Long documentId;
    private final String originSessionId;
    private final Map<String, Object> frame;
    private final Map<String, Object> originFrame;

    public ContentAppliedEvent(Long documentId, String originSessionId, Map<String, Object> frame) {
        this(documentId, originSessionId, frame, null);
    }

    public ContentAppliedEvent(Long documentId, String originSessionId,
                               Map<String, Object> frame, Map<String, Object> originFrame) {
        this.documentId = documentId;
        this.originSessionId = originSessionId;
        this.frame = frame;
        this.originFrame = originFrame;
    }

    public Long getDocumentId() { return documentId; }
    public String getOriginSessionId() { return originSessionId; }
    public Map<String, Object> getFrame() { return frame; }
    public Map<String, Object> getOriginFrame() { return originFrame; }
}
