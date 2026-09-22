package com.collabdoc.repository;

import com.collabdoc.entity.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    List<OperationLog> findByDocumentIdOrderByVersionAsc(Long documentId);
    List<OperationLog> findByDocumentIdAndVersionGreaterThanOrderByVersionAsc(Long documentId, Integer version);

    /** Checkpoints fold everything before them into a full document state, so those rows can go. */
    @Modifying
    @Query("delete from OperationLog l where l.documentId = :documentId and l.version <= :version")
    void deleteByDocumentIdUpToVersion(@Param("documentId") Long documentId, @Param("version") Integer version);

    @Modifying
    @Query("delete from OperationLog l where l.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from OperationLog l where l.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
}
