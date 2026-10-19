package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.BackgroundAssetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BackgroundAssetRepository extends JpaRepository<BackgroundAssetEntity, Long> {
    Optional<BackgroundAssetEntity> findByIsDefaultTrue();
    List<BackgroundAssetEntity> findAllByOrderByCreatedAtDesc();
}
