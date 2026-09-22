package com.collabdoc.pattern.observer;

import com.collabdoc.dto.ContentAppliedEvent;

public interface DocumentSubject {

    void attach(DocumentObserver observer);

    void detach(String documentId);

    void notifyAllObservers(ContentAppliedEvent event);
}
