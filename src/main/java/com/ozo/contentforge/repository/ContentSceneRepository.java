package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.ContentSceneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ContentSceneRepository extends JpaRepository<ContentSceneEntity, Long> {
    List<ContentSceneEntity> findByPostIdOrderBySceneNumberAsc(Long postId);
    Optional<ContentSceneEntity> findByPostIdAndSceneNumber(Long postId, Integer sceneNumber);
    void deleteByPostId(Long postId);
}
