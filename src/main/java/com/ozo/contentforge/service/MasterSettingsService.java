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

/**
 * Settings of the current brand: brand details, generation options and its Instagram/Facebook connection.
 * Stored as one JSON row per brand (rows are scoped to the brand automatically).
 */
@Service
public class MasterSettingsService {

    /** The settings row of a brand. */
    public static final String SETTINGS_KEY = "BRAND_CONFIG";

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
        return settingsRepository.findBySettingKey(SETTINGS_KEY)
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

    /** Saves a new brand's first settings: the defaults with its name and industry filled in. */
    @Transactional
    public Map<String, Object> initializeForBrand(String brandName, String industry) {
        Map<String, Object> s = getDefaultSettings();
        s.put("brandName", brandName);
        if (industry != null && !industry.isBlank()) s.put("industry", industry.trim());
        return updateSettings(s);
    }

    @Transactional
    public Map<String, Object> updateSettings(Map<String, Object> newSettings) {
        String actorId = com.ozo.contentforge.security.CurrentUser.actor();
        String beforeJson = "";
        MasterSettingsEntity entity = settingsRepository.findBySettingKey(SETTINGS_KEY)
                .orElse(MasterSettingsEntity.builder()
                        .settingKey(SETTINGS_KEY)
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
            entity.setUpdatedBy(actorId);
            entity.setUpdatedAt(LocalDateTime.now());
            settingsRepository.save(entity);

            // Audit
            auditLogRepository.save(AuditLogEntity.builder()
                    .actorId(actorId)
                    .action("UPDATE_SETTINGS")
                    .entityType("MASTER_SETTINGS")
                    .entityId(SETTINGS_KEY + ":" + com.ozo.contentforge.tenancy.BrandContext.require())
                    .beforeJson(beforeJson)
                    .afterJson(newJson)
                    .createdAt(LocalDateTime.now())
                    .build());

            return newSettings;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update master settings: " + e.getMessage(), e);
        }
    }

    /** Keys owned by the Instagram/Facebook connection: only the Meta endpoints change them. */
    public static final java.util.Set<String> META_CONNECTION_KEYS = java.util.Set.of(
            "metaConnected", "metaUserName", "facebookPageId", "facebookPageName", "facebookPageAccessToken",
            "instagramAccountId", "instagramUsername", "metaTokenStatus", "metaTokenCheckedAt", "metaTokenError");

    /** Saves settings edited on the Settings page, keeping the stored Instagram/Facebook connection as it is. */
    @Transactional
    public Map<String, Object> updateEditableSettings(Map<String, Object> edited) {
        Map<String, Object> current = getAllSettings();
        Map<String, Object> next = new HashMap<>(edited);
        for (String key : META_CONNECTION_KEYS) {
            if (current.containsKey(key)) next.put(key, current.get(key));
            else next.remove(key);
        }
        return updateSettings(next);
    }

    public Map<String, Object> getDefaultSettings() {
        Map<String, Object> s = new HashMap<>();
        s.put("brandName", "");
        s.put("brandLogoUrl", "");
        s.put("industry", "");
        s.put("tagline", "");
        s.put("brandTone", "Warm, clear, trustworthy and practical");
        s.put("website", "");
        s.put("cta", "");
        s.put("attachEndCta", true); // Enable/disable dedicated Call-To-Action (CTA) end scene & carousel slide
        s.put("defaultLanguage", "Hinglish");
        s.put("languageMix", "Hindi-led with natural English terms");

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
        s.put("brandPrimaryColor", "#0074BC");
        s.put("brandSecondaryColor", "#2BB673");
        s.put("brandTextColor", "#231F20");
        s.put("brandPhone", "");
        s.put("brandInstagramHandle", ""); // empty = the connected Instagram account (instagramUsername)

        // Voice Cloning & Audio (ElevenLabs PVC)
        s.put("defaultVoiceTier", "INSTANT"); // "INSTANT" (IVC) or "PROFESSIONAL" (PVC)
        s.put("elevenLabsTtsModel", "eleven_v4");
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
        s.put("clinicalReviewRequired", false);

        // Budget
        s.put("dailySpendCapUsd", 25.0);
        s.put("monthlySpendCapUsd", 500.0);
        s.put("regenerationAllowancePercent", 20.0);

        return s;
    }
}
