package com.collabdoc.pattern.memento;

import com.collabdoc.entity.DocumentSnapshot;
import com.collabdoc.repository.DocumentSnapshotRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MementoCaretaker {

    private static final int MAX_SNAPSHOTS_PER_DOC = 50;

    private final DocumentSnapshotRepository snapshotRepository;

    public MementoCaretaker(DocumentSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public void capture(Long documentId, String content, String contentFormat, int version) {
        snapshotRepository.save(new DocumentSnapshot(documentId, content, contentFormat, version));
        prune(documentId);
    }

    /** Returns {@code null} when no snapshot was kept for that version. */
    public DocumentMemento restore(Long documentId, int version) {
        return snapshotRepository.findByDocumentIdAndVersion(documentId, version)
                .map(snapshot -> new DocumentMemento(
                        snapshot.getContent(), snapshot.getContentFormat(), snapshot.getVersion()))
                .orElse(null);
    }

    private void prune(Long documentId) {
        List<DocumentSnapshot> snapshots = snapshotRepository.findByDocumentIdOrderByVersionDesc(documentId);
        if (snapshots.size() <= MAX_SNAPSHOTS_PER_DOC) return;
        snapshotRepository.deleteAll(snapshots.subList(MAX_SNAPSHOTS_PER_DOC, snapshots.size()));
    }
}
