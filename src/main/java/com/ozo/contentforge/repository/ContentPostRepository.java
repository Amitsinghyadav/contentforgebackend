package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContentPostRepository extends JpaRepository<ContentPostEntity, Long> {
    List<ContentPostEntity> findByTargetDateOrderByScheduledAtAscCreatedAtAsc(LocalDate targetDate);
    List<ContentPostEntity> findByBatchId(Long batchId);
    Optional<ContentPostEntity> findByIdempotencyKey(String idempotencyKey);
    List<ContentPostEntity> findByTargetDateAndContentType(LocalDate targetDate, ContentType contentType);
    List<ContentPostEntity> findByStatus(PostStatus status);
    List<ContentPostEntity> findByEventIdOrderByIdDesc(Long eventId);
    Optional<ContentPostEntity> findFirstByEventIdOrderByIdDesc(Long eventId);
    
    @Query("SELECT p FROM ContentPostEntity p WHERE p.targetDate BETWEEN :startDate AND :endDate ORDER BY p.targetDate ASC, p.createdAt ASC")
    List<ContentPostEntity> findByTargetDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    /** Approved posts whose publish time has come (used by the scheduler across all brands). */
    @Query("SELECT p FROM ContentPostEntity p WHERE p.status = com.ozo.contentforge.model.enums.PostStatus.SCHEDULED "
            + "AND p.scheduledAt <= :now AND (p.nextPublishAttemptAt IS NULL OR p.nextPublishAttemptAt <= :now) "
            + "ORDER BY p.scheduledAt ASC")
    List<ContentPostEntity> findDueForPublishing(@Param("now") java.time.LocalDateTime now);

    /** Atomically moves a post to PUBLISHING so two publishers can't post it twice. Returns 1 when claimed. */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE ContentPostEntity p SET p.status = com.ozo.contentforge.model.enums.PostStatus.PUBLISHING "
            + "WHERE p.id = :id AND p.status IN :from")
    int claimForPublishing(@Param("id") Long id, @Param("from") java.util.Collection<PostStatus> from);

    List<ContentPostEntity> findByStatusIn(java.util.Collection<PostStatus> statuses);
}
