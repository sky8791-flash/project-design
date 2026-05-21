package com.collabdoc.repository;

import com.collabdoc.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    List<OperationLog> findByDocumentIdOrderByVersionAsc(Long documentId);
    List<OperationLog> findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(Long documentId, Integer version);
}
