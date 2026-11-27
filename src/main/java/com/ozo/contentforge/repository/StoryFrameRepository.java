package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.StoryFrameEntity;
import com.ozo.contentforge.model.enums.FramePublishStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StoryFrameRepository extends JpaRepository<StoryFrameEntity, Long> {
    List<StoryFrameEntity> findByPostIdOrderByFrameNumberAsc(Long postId);

    List<StoryFrameEntity> findByPublishStatusOrderByCreatedAtAsc(FramePublishStatus status);

    void deleteByPostId(Long postId);
}
