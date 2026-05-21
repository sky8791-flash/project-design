package com.collabdoc.pattern.memento;

import com.collabdoc.entity.DocumentSnapshot;
import com.collabdoc.repository.DocumentSnapshotRepository;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MementoCaretaker {

    private final DocumentSnapshotRepository snapshotRepository;
    private final Map<Long, List<DocumentMemento>> historyStacks = new ConcurrentHashMap<>();
    private final Map<Long, Integer> currentIndices = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 50;

    public MementoCaretaker(DocumentSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public void saveState(String content, int version, Long documentId) {
        DocumentMemento memento = new DocumentMemento(content, version);
        List<DocumentMemento> historyStack = historyStacks.computeIfAbsent(documentId, k -> new ArrayList<>());
        int currentIndex = currentIndices.getOrDefault(documentId, -1);

        currentIndex++;
        if (currentIndex < historyStack.size()) {
            historyStack.subList(currentIndex, historyStack.size()).clear();
        }
        historyStack.add(memento);
        if (historyStack.size() > MAX_HISTORY) {
            historyStack.remove(0);
            currentIndex--;
        }
        currentIndices.put(documentId, currentIndex);

        DocumentSnapshot snapshot = new DocumentSnapshot(documentId, content, version);
        snapshotRepository.save(snapshot);
    }

    public DocumentMemento undo(Long documentId) {
        List<DocumentMemento> historyStack = historyStacks.get(documentId);
        int currentIndex = currentIndices.getOrDefault(documentId, -1);
        if (historyStack == null || currentIndex < 0 || historyStack.isEmpty()) {
            return null;
        }
        if (currentIndex > 0) {
            currentIndex--;
        }
        currentIndices.put(documentId, currentIndex);
        return historyStack.get(currentIndex);
    }

    public DocumentMemento redo(Long documentId) {
        List<DocumentMemento> historyStack = historyStacks.get(documentId);
        int currentIndex = currentIndices.getOrDefault(documentId, -1);
        if (historyStack == null || currentIndex < 0 || currentIndex >= historyStack.size() - 1) {
            return null;
        }
        currentIndex++;
        currentIndices.put(documentId, currentIndex);
        return historyStack.get(currentIndex);
    }

    public void reset(Long documentId) {
        historyStacks.remove(documentId);
        currentIndices.remove(documentId);
    }

    public void loadHistoryFromDatabase(Long documentId) {
        List<DocumentSnapshot> snapshots = snapshotRepository.findByDocumentIdOrderByVersionDesc(documentId);
        List<DocumentMemento> historyStack = new ArrayList<>();
        int currentIndex = -1;

        for (DocumentSnapshot snapshot : snapshots) {
            DocumentMemento memento = new DocumentMemento(snapshot.getContent(), snapshot.getVersion());
            historyStack.add(0, memento);
            currentIndex++;
        }

        if (!historyStack.isEmpty()) {
            currentIndex = historyStack.size() - 1;
        }

        historyStacks.put(documentId, historyStack);
        currentIndices.put(documentId, currentIndex);
    }
}
