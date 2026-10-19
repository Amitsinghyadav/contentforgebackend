package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.BrandProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandProfileRepository extends JpaRepository<BrandProfileEntity, Long> {
    Optional<BrandProfileEntity> findByIsDefaultTrue();
}
