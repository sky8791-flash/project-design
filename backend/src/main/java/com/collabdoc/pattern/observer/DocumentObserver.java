package com.collabdoc.pattern.observer;

import com.collabdoc.dto.ContentAppliedEvent;

public interface DocumentObserver {

    void update(ContentAppliedEvent event);

    String getDocumentId();
}
