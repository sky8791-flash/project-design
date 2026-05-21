package com.collabdoc.pattern.observer;

import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DocumentSubjectImpl implements DocumentSubject {

    private final Map<String, Set<DocumentObserver>> documentObservers = new ConcurrentHashMap<>();

    @Override
    public void attach(DocumentObserver observer) {
        String docId = observer.getDocumentId();
        documentObservers.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(observer);
    }

    @Override
    public void detach(DocumentObserver observer) {
        String docId = observer.getDocumentId();
        Set<DocumentObserver> observers = documentObservers.get(docId);
        if (observers != null) {
            observers.remove(observer);
            if (observers.isEmpty()) {
                documentObservers.remove(docId);
            }
        }
    }

    @Override
    public void notifyAllObservers(String documentId, String content, int version) {
        Set<DocumentObserver> observers = documentObservers.get(documentId);
        if (observers != null) {
            for (DocumentObserver observer : observers) {
                observer.update(documentId, content, version);
            }
        }
    }

    public int getObserverCount(String documentId) {
        Set<DocumentObserver> observers = documentObservers.get(documentId);
        return observers != null ? observers.size() : 0;
    }

    public Set<DocumentObserver> getObservers(String documentId) {
        return documentObservers.getOrDefault(documentId, Set.of());
    }
}
