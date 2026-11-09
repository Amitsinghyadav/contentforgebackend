package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.PlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlanRepository extends JpaRepository<PlanEntity, Long> {
    List<PlanEntity> findByActiveTrueOrderByIdAsc();
}

