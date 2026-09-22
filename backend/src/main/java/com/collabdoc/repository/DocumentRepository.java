package com.collabdoc.repository;

import com.collabdoc.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByCreatedByOrderByUpdatedAtDesc(Long userId);

    List<Document> findByCreatedBy(Long userId);

    /**
     * Takes the document row lock that serializes version assignment: concurrent writers queue here,
     * so the {@code version + 1} they mint inside the same transaction cannot collide.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.content = :content, d.contentFormat = :format, "
            + "d.version = :next, d.updatedAt = :now "
            + "where d.id = :id and d.version = :base")
    int casContent(@Param("id") Long id, @Param("content") String content,
                   @Param("format") String format, @Param("next") Integer next,
                   @Param("base") Integer base, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("update Document d set d.version = :next, d.updatedAt = :now "
            + "where d.id = :id and d.version = :base")
    int casAdvanceVersion(@Param("id") Long id, @Param("next") Integer next,
                          @Param("base") Integer base, @Param("now") LocalDateTime now);
}
