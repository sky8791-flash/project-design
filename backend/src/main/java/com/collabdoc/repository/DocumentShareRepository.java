package com.collabdoc.repository;

import com.collabdoc.entity.DocumentShare;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DocumentShareRepository extends JpaRepository<DocumentShare, Long> {
    List<DocumentShare> findByDocumentId(Long documentId);
    List<DocumentShare> findByUserId(Long userId);
    Optional<DocumentShare> findByDocumentIdAndUserId(Long documentId, Long userId);
    boolean existsByDocumentIdAndUserId(Long documentId, Long userId);
    void deleteByDocumentIdAndUserId(Long documentId, Long userId);
}