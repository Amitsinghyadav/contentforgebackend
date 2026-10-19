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
    
    @Query("SELECT p FROM ContentPostEntity p WHERE p.targetDate BETWEEN :startDate AND :endDate ORDER BY p.targetDate ASC, p.createdAt ASC")
    List<ContentPostEntity> findByTargetDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
