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

    @org.springframework.beans.factory.annotation.Value("${meta.app.id:2141628373385657}")
    private String metaAppIdProperty;

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
                        Map<String, Object> merged = new HashMap<>(getDefaultSettings());
                        Map<String, Object> stored = objectMapper.readValue(entity.getSettingValueJson(), new TypeReference<Map<String, Object>>() {});
                        merged.putAll(stored);
                        if (metaAppIdProperty != null && !metaAppIdProperty.isBlank()) {
                            merged.put("metaAppId", metaAppIdProperty.trim());
                        }
                        return merged;
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
            if (newSettings != null) {
                if (newSettings.containsKey("videoAspectRatio")) {
                    newSettings.put("brollOutputRatio", newSettings.get("videoAspectRatio"));
                } else if (newSettings.containsKey("brollOutputRatio")) {
                    newSettings.put("videoAspectRatio", newSettings.get("brollOutputRatio"));
                }
            }
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
        s.put("brandLogoUrl", "");
        s.put("industry", "Preventive Healthcare & Diagnostics");
        s.put("tagline", "Evidence-based daily preventive health & metabolic insights");
        s.put("brandTone", "Trustworthy, clear, Indian, empathetic");
        s.put("website", "https://ozohealth.in");
        s.put("cta", "Download OZO Health app for personalized metabolic screenings");
        s.put("attachEndCta", true); // Enable/disable dedicated Call-To-Action (CTA) end scene & carousel slide
        s.put("defaultLanguage", "Hinglish");
        s.put("languageMix", "Hindi-led with natural English medical terms");

        // Content Generation & Formatting (Quotas are dynamically driven by the Reel Planner)
        s.put("carouselSlideCount", 6);
        s.put("occasionExtraItems", 1);

        // Content
        s.put("reelDurationTarget", 45);
        s.put("productionMode", "Mode A"); // Mode A or Mode B
        s.put("avatarEngine", "Avatar IV"); // Avatar III or Avatar IV
        s.put("videoAspectRatio", "9:16"); // "9:16" (Vertical), "1:1" (Square), "16:9" (Landscape)
        s.put("brollModel", "Runway Gen-4.5");
        s.put("brollCount", 2);
        s.put("brollClipDuration", 5);
        s.put("brollOutputRatio", "9:16");
        s.put("subtitlesEnabled", true);
        s.put("captionLanguage", "Hinglish"); // "Hinglish", "Hindi", or "English"
        s.put("renderEngine", "ffmpeg"); // "ffmpeg" (basic composite) or "remotion" (contentforgerender service)
        s.put("reelStyle", "clean-brand"); // render service style id
        s.put("brandPrimaryColor", "#0074BC");
        s.put("brandSecondaryColor", "#2BB673");
        s.put("brandTextColor", "#231F20");
        s.put("brandPhone", "");
        s.put("brandInstagramHandle", ""); // empty = the connected Instagram account (instagramUsername)

        // Voice Cloning & Audio (ElevenLabs PVC)
        s.put("defaultVoiceTier", "INSTANT"); // "INSTANT" (IVC) or "PROFESSIONAL" (PVC)
        s.put("elevenLabsTtsModel", "eleven_multilingual_v2");
        s.put("pvcMinAudioMinutes", 30);
        s.put("pvcHighFidelityMode", true);

        // Publishing & Meta Cross-Posting
        s.put("timezone", "Asia/Kolkata");
        s.put("reelSlot1", "11:00 AM IST");
        s.put("reelSlot2", "7:00 PM IST");
        s.put("autoPublish", false);
        s.put("metaConnected", false);
        s.put("metaUserName", "");
        s.put("facebookPageId", "");
        s.put("facebookPageName", "");
        s.put("facebookPageAccessToken", "");
        s.put("instagramAccountId", "");
        s.put("instagramUsername", "");
        s.put("crossPostToFacebook", true);
        s.put("metaAppId", metaAppIdProperty != null && !metaAppIdProperty.isBlank() ? metaAppIdProperty.trim() : "2141628373385657");

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
