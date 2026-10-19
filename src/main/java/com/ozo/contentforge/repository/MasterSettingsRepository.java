package com.ozo.contentforge.repository;

import com.ozo.contentforge.model.entity.MasterSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MasterSettingsRepository extends JpaRepository<MasterSettingsEntity, Long> {
    Optional<MasterSettingsEntity> findBySettingKey(String settingKey);
}
