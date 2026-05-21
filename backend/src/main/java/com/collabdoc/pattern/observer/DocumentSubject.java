package com.collabdoc.pattern.observer;

public interface DocumentSubject {

    void attach(DocumentObserver observer);

    void detach(DocumentObserver observer);

    void notifyAllObservers(String documentId, String content, int version);

    int getObserverCount(String documentId);
}
