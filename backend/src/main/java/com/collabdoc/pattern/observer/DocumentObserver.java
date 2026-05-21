package com.collabdoc.pattern.observer;

public interface DocumentObserver {

    void update(String documentId, String content, int version);

    String getUserId();

    String getDocumentId();
}
