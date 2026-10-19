package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.VoiceLibraryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VoiceLibraryRepository extends JpaRepository<VoiceLibraryEntity, Long> {
    Optional<VoiceLibraryEntity> findByIsDefaultTrue();
}
