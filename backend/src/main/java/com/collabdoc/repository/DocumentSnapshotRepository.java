package com.collabdoc.repository;

import com.collabdoc.entity.DocumentSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentSnapshotRepository extends JpaRepository<DocumentSnapshot, Long> {
    List<DocumentSnapshot> findByDocumentIdOrderByVersionDesc(Long documentId);
    Optional<DocumentSnapshot> findByDocumentIdAndVersion(Long documentId, Integer version);
    Optional<DocumentSnapshot> findTopByDocumentIdOrderByVersionDesc(Long documentId);
}
