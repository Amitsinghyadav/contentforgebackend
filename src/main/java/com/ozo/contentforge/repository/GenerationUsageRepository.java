package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.GenerationUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GenerationUsageRepository extends JpaRepository<GenerationUsageEntity, Long> {
    List<GenerationUsageEntity> findByPostId(Long postId);

    @Query("SELECT u FROM GenerationUsageEntity u WHERE u.createdAt BETWEEN :from AND :to ORDER BY u.createdAt DESC")
    List<GenerationUsageEntity> findByDateRange(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT SUM(u.actualCost) FROM GenerationUsageEntity u WHERE u.createdAt BETWEEN :from AND :to")
    Double sumActualCostBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
