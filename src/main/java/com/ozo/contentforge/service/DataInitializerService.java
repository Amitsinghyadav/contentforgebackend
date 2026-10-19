package com.ozo.contentforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.MasterSettingsEntity;
import com.ozo.contentforge.repository.MasterSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class DataInitializerService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializerService.class);

    private final MasterSettingsRepository settingsRepository;
    private final MasterSettingsService settingsService;
    private final ObjectMapper objectMapper;

    public DataInitializerService(MasterSettingsRepository settingsRepository,
                                  MasterSettingsService settingsService,
                                  ObjectMapper objectMapper) {
        this.settingsRepository = settingsRepository;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        log.info("Checking Master Settings configuration...");

        // Only ensure Master Settings schema exists if empty
        if (settingsRepository.findBySettingKey("GLOBAL_CONFIG").isEmpty()) {
            try {
                Map<String, Object> defaults = settingsService.getDefaultSettings();
                MasterSettingsEntity entity = MasterSettingsEntity.builder()
                        .settingKey("GLOBAL_CONFIG")
                        .settingValueJson(objectMapper.writeValueAsString(defaults))
                        .updatedBy("system_init")
                        .updatedAt(LocalDateTime.now())
                        .version(1)
                        .build();
                settingsRepository.save(entity);
                log.info("Initialized default system master settings configuration.");
            } catch (Exception e) {
                log.error("Failed to seed master settings config: {}", e.getMessage());
            }
        }
        // No dummy brand, no dummy presenter, no dummy voice, no dummy calendar occasions, and no dummy posts are seeded.
        log.info("Database is clean. No dummy data created.");
    }
}
