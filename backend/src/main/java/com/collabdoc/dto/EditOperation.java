package com.collabdoc.dto;

import com.collabdoc.ot.OTOperation;

public class EditOperation {

    private String documentId;
    private Long userId;
    private OTOperation.Type operationType;
    private int position;
    private String text;
    private int length;
    private int baseVersion;

    public EditOperation() {}

    public EditOperation(String documentId, Long userId, OTOperation.Type operationType,
                         int position, String text, int length, int baseVersion) {
        this.documentId = documentId;
        this.userId = userId;
        this.operationType = operationType;
        this.position = position;
        this.text = text;
        this.length = length;
        this.baseVersion = baseVersion;
    }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public OTOperation.Type getOperationType() { return operationType; }
    public void setOperationType(OTOperation.Type operationType) { this.operationType = operationType; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
    public int getBaseVersion() { return baseVersion; }
    public void setBaseVersion(int baseVersion) { this.baseVersion = baseVersion; }

    public OTOperation toOTOperation() {
        if (operationType == OTOperation.Type.INSERT) {
            return OTOperation.insert(position, text);
        } else {
            return OTOperation.delete(position, length);
        }
    }
}
