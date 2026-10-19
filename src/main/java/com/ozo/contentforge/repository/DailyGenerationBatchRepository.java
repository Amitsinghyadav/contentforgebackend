package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.DailyGenerationBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailyGenerationBatchRepository extends JpaRepository<DailyGenerationBatchEntity, Long> {
    Optional<DailyGenerationBatchEntity> findByTargetDate(LocalDate targetDate);
}
