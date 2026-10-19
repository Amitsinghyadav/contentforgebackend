package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.InstagramPublicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstagramPublicationRepository extends JpaRepository<InstagramPublicationEntity, Long> {
    List<InstagramPublicationEntity> findByPostIdOrderByCreatedAtDesc(Long postId);
    Optional<InstagramPublicationEntity> findFirstByPostIdOrderByCreatedAtDesc(Long postId);
}
