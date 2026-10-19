package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.VideoRenderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoRenderRepository extends JpaRepository<VideoRenderEntity, Long> {
    List<VideoRenderEntity> findByPostIdOrderByRenderVersionDesc(Long postId);
    Optional<VideoRenderEntity> findFirstByPostIdOrderByRenderVersionDesc(Long postId);
}
