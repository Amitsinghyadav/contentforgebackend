package com.ozo.contentforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.AuditLogEntity;
import com.ozo.contentforge.model.entity.MasterSettingsEntity;
import com.ozo.contentforge.repository.AuditLogRepository;
import com.ozo.contentforge.repository.MasterSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class MasterSettingsService {

    private static final Logger log = LoggerFactory.getLogger(MasterSettingsService.class);
    private final MasterSettingsRepository settingsRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public MasterSettingsService(MasterSettingsRepository settingsRepository,
                                 AuditLogRepository auditLogRepository,
                                 ObjectMapper objectMapper) {
        this.settingsRepository = settingsRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getAllSettings() {
        return settingsRepository.findBySettingKey("GLOBAL_CONFIG")
                .map(entity -> {
                    try {
                        return objectMapper.readValue(entity.getSettingValueJson(), new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        log.error("Failed to parse settings JSON: {}", e.getMessage());
                        return getDefaultSettings();
                    }
                })
                .orElseGet(this::getDefaultSettings);
    }

    @Transactional
    public Map<String, Object> updateSettings(Map<String, Object> newSettings, String actorId) {
        String beforeJson = "";
        MasterSettingsEntity entity = settingsRepository.findBySettingKey("GLOBAL_CONFIG")
                .orElse(MasterSettingsEntity.builder()
                        .settingKey("GLOBAL_CONFIG")
                        .version(1)
                        .build());

        if (entity.getSettingValueJson() != null) {
            beforeJson = entity.getSettingValueJson();
        }

        try {
            String newJson = objectMapper.writeValueAsString(newSettings);
            entity.setSettingValueJson(newJson);
            entity.setUpdatedBy(actorId != null ? actorId : "admin");
            entity.setUpdatedAt(LocalDateTime.now());
            settingsRepository.save(entity);

            // Audit
            auditLogRepository.save(AuditLogEntity.builder()
                    .actorId(actorId != null ? actorId : "admin")
                    .action("UPDATE_SETTINGS")
                    .entityType("MASTER_SETTINGS")
                    .entityId("GLOBAL_CONFIG")
                    .beforeJson(beforeJson)
                    .afterJson(newJson)
                    .createdAt(LocalDateTime.now())
                    .build());

            return newSettings;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update master settings: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> getDefaultSettings() {
        Map<String, Object> s = new HashMap<>();
        s.put("brandName", "OZO Health");
        s.put("industry", "Preventive Healthcare & Diagnostics");
        s.put("tagline", "Evidence-based daily preventive health & metabolic insights");
        s.put("brandTone", "Trustworthy, clear, Indian, empathetic");
        s.put("website", "https://ozohealth.in");
        s.put("cta", "Download OZO Health app for personalized metabolic screenings");
        s.put("defaultLanguage", "Hinglish");
        s.put("languageMix", "Hindi-led with natural English medical terms");

        // Quotas
        s.put("dailyReels", 2);
        s.put("dailyPosts", 0);
        s.put("dailyCarousels", 0);
        s.put("carouselSlideCount", 6);
        s.put("occasionExtraItems", 1);

        // Content
        s.put("reelDurationTarget", 45);
        s.put("productionMode", "Mode A"); // Mode A or Mode B
        s.put("avatarEngine", "Avatar IV"); // Avatar III or Avatar IV
        s.put("brollModel", "Runway Gen-4.5");
        s.put("brollCount", 2);
        s.put("brollClipDuration", 5);
        s.put("brollOutputRatio", "9:16");
        s.put("subtitlesEnabled", true);

        // Voice Cloning & Audio (ElevenLabs PVC)
        s.put("defaultVoiceTier", "INSTANT"); // "INSTANT" (IVC) or "PROFESSIONAL" (PVC)
        s.put("elevenLabsTtsModel", "eleven_multilingual_v2");
        s.put("pvcMinAudioMinutes", 30);
        s.put("pvcHighFidelityMode", true);

        // Publishing
        s.put("timezone", "Asia/Kolkata");
        s.put("reelSlot1", "11:00 AM IST");
        s.put("reelSlot2", "7:00 PM IST");
        s.put("autoPublish", false);

        // Approvals
        s.put("textApprovalRequired", true);
        s.put("mediaApprovalRequired", true);
        s.put("clinicalReviewRequired", true);

        // Budget
        s.put("dailySpendCapUsd", 25.0);
        s.put("monthlySpendCapUsd", 500.0);
        s.put("regenerationAllowancePercent", 20.0);

        return s;
    }
}
