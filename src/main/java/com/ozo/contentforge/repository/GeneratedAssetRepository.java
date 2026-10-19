package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface GeneratedAssetRepository extends JpaRepository<GeneratedAssetEntity, Long> {
    List<GeneratedAssetEntity> findByPostId(Long postId);
    List<GeneratedAssetEntity> findByPostIdAndAssetType(Long postId, AssetType assetType);
    @Transactional
    void deleteByPostId(Long postId);
}
