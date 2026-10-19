package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.MediaJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MediaJobRepository extends JpaRepository<MediaJobEntity, Long> {
    List<MediaJobEntity> findByPostIdOrderByStartedAtDesc(Long postId);
    List<MediaJobEntity> findByPostIdAndSceneId(Long postId, Long sceneId);
    @Transactional
    void deleteByPostId(Long postId);
}
