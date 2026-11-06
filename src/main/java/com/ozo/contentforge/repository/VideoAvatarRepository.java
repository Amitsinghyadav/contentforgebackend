package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.VideoAvatarEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VideoAvatarRepository extends JpaRepository<VideoAvatarEntity, Long> {
    Optional<VideoAvatarEntity> findByIsDefaultTrue();
    List<VideoAvatarEntity> findAllByOrderByCreatedAtDesc();
    Optional<VideoAvatarEntity> findByAvatarId(String avatarId);
    boolean existsByAvatarId(String avatarId);
}
