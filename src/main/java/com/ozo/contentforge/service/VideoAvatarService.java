package com.ozo.contentforge.service;

import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.entity.PresenterProfileEntity;
import com.ozo.contentforge.model.entity.VideoAvatarEntity;
import com.ozo.contentforge.repository.PresenterProfileRepository;
import com.ozo.contentforge.repository.VideoAvatarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class VideoAvatarService {

    private static final Logger log = LoggerFactory.getLogger(VideoAvatarService.class);

    private final VideoAvatarRepository videoAvatarRepository;
    private final PresenterProfileRepository presenterRepository;
    private final StorageService storageService;
    private final AiProviderService aiProviderService;

    private final ConsentGuard consentGuard;

    public VideoAvatarService(VideoAvatarRepository videoAvatarRepository,
                              PresenterProfileRepository presenterRepository,
                              StorageService storageService,
                              AiProviderService aiProviderService,
                              ConsentGuard consentGuard) {
        this.consentGuard = consentGuard;
        this.videoAvatarRepository = videoAvatarRepository;
        this.presenterRepository = presenterRepository;
        this.storageService = storageService;
        this.aiProviderService = aiProviderService;
    }

    public List<VideoAvatarEntity> getAllAvatars() {
        return videoAvatarRepository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<VideoAvatarEntity> getAvatarById(Long id) {
        return videoAvatarRepository.findById(id);
    }

    @Transactional
    public VideoAvatarEntity createVideoAvatar(String name, String description, MultipartFile videoFile,
                                              Boolean isDefault, Long linkPresenterId) {
        consentGuard.requireFace();
        log.info("Creating dynamic Video Avatar from dashboard upload: '{}', file size: {} bytes",
                name, videoFile.getSize());

        // 1. Store sample training video permanently on AWS S3
        GeneratedAssetEntity videoAsset;
        try {
            videoAsset = storageService.storeUploadedFile(videoFile, AssetType.VIDEO, null, null);
        } catch (Exception e) {
            log.error("Failed to store video avatar file on S3: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to upload video to storage: " + e.getMessage(), e);
        }

        // 2. Prepare initial DB record
        boolean markDefault = Boolean.TRUE.equals(isDefault);
        if (markDefault) {
            unsetAllVideoDefaults();
            unsetAllPresenterDefaults();
        }

        VideoAvatarEntity entity = VideoAvatarEntity.builder()
                .name(name)
                .description(description)
                .videoUrl(videoAsset.getStorageUrl())
                .status("PROCESSING")
                .statusMessage("Training video uploaded to S3. Uploading to HeyGen...")
                .isDefault(markDefault)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        entity = videoAvatarRepository.save(entity);

        // 3. Send video to HeyGen Asset API (POST https://api.heygen.com/v3/assets)
        try {
            byte[] videoBytes = videoFile.getBytes();
            String contentType = videoFile.getContentType() != null ? videoFile.getContentType() : "video/mp4";
            String filename = videoFile.getOriginalFilename() != null ? videoFile.getOriginalFilename() : "avatar_sample.mp4";

            log.info("Uploading avatar footage to HeyGen v3 assets API...");
            String assetId = aiProviderService.uploadHeyGenVideoAsset(videoBytes, contentType, filename);
            entity.setAssetId(assetId);
            entity.setStatusMessage("Footage registered on HeyGen (Asset ID: " + assetId + "). Initiating Digital Twin generation...");
            videoAvatarRepository.save(entity);

            // 4. Initiate Digital Twin avatar creation (POST https://api.heygen.com/v3/avatars)
            log.info("Calling HeyGen v3 create Digital Twin API for asset '{}'...", assetId);
            Map<String, String> twinResult = aiProviderService.createHeyGenDigitalTwin(name, assetId);

            String lookId = twinResult.get("look_id");
            String avatarId = twinResult.get("avatar_id");
            String groupId = twinResult.get("group_id");
            String status = twinResult.get("status");
            String previewImageUrl = twinResult.get("preview_image_url");
            String consentStatus = twinResult.get("consent_status");

            if (lookId != null && !lookId.isBlank()) entity.setLookId(lookId);
            if (groupId != null && !groupId.isBlank()) entity.setGroupId(groupId);
            if (previewImageUrl != null && !previewImageUrl.isBlank()) entity.setThumbnailUrl(previewImageUrl);
            if (consentStatus != null && !consentStatus.isBlank()) entity.setConsentStatus(consentStatus);

            // If an explicit avatarId is immediately returned, save it
            if (avatarId != null && !avatarId.isBlank()) {
                entity.setAvatarId(avatarId);
            } else if (lookId != null && !lookId.isBlank()) {
                entity.setAvatarId(lookId);
            }

            if ("completed".equalsIgnoreCase(status) || "ready".equalsIgnoreCase(status)) {
                entity.setStatus("COMPLETED");
                entity.setStatusMessage("HeyGen Digital Twin avatar ready for dynamic generation.");
            } else if ("PENDING_CONSENT".equalsIgnoreCase(status)) {
                entity.setStatus("PENDING_CONSENT");
                entity.setStatusMessage("HeyGen requires verbal consent verification before activating this digital twin. Click 'Provide Consent' below.");
            } else {
                entity.setStatus("PROCESSING");
                entity.setStatusMessage("HeyGen is processing the digital twin video avatar. Look ID: " + lookId);
            }

            // 5. If marked default, unset talking photo presenter defaults
            if (markDefault) {
                unsetAllPresenterDefaults();
            }

            entity.setUpdatedAt(LocalDateTime.now());
            return videoAvatarRepository.save(entity);

        } catch (Exception e) {
            log.error("Error creating HeyGen digital twin for '{}': {}", name, e.getMessage(), e);
            entity.setStatus("FAILED");
            entity.setStatusMessage("HeyGen generation error: " + e.getMessage());
            entity.setUpdatedAt(LocalDateTime.now());
            return videoAvatarRepository.save(entity);
        }
    }

    @Transactional
    public VideoAvatarEntity checkAndUpdateStatus(Long id) {
        VideoAvatarEntity entity = videoAvatarRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Video Avatar not found: " + id));



        String lookId = entity.getLookId();
        String groupId = entity.getGroupId();

        if ((lookId == null || lookId.isBlank()) && (groupId == null || groupId.isBlank())) {
            entity.setStatusMessage("No HeyGen Group ID or Look ID found to check status.");
            return videoAvatarRepository.save(entity);
        }

        try {
            log.info("Checking HeyGen Digital Twin status for avatar ID {}, lookId '{}', groupId '{}'...", id, lookId, groupId);
            Map<String, String> statusMap = aiProviderService.checkHeyGenDigitalTwinStatus(lookId, groupId);

            String status = statusMap.get("status");
            String realAvatarId = statusMap.get("avatarId");
            String thumbnailUrl = statusMap.get("thumbnailUrl");
            String consentStatus = statusMap.get("consent_status");
            String error = statusMap.get("error");

            if (realAvatarId != null && !realAvatarId.isBlank()) {
                entity.setAvatarId(realAvatarId);
            }
            if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
                entity.setThumbnailUrl(thumbnailUrl);
            }
            if (consentStatus != null && !consentStatus.isBlank()) {
                entity.setConsentStatus(consentStatus);
            }

            if ("completed".equalsIgnoreCase(status) || "ready".equalsIgnoreCase(status)) {
                entity.setStatus("COMPLETED");
                entity.setStatusMessage("HeyGen Digital Twin is ready! Dynamic gestures and full body movement active.");
            } else if ("PENDING_CONSENT".equalsIgnoreCase(status)) {
                entity.setStatus("PENDING_CONSENT");
                entity.setStatusMessage("HeyGen requires verbal consent verification before activating this digital twin. Click 'Provide Consent' below.");
            } else if ("failed".equalsIgnoreCase(status)) {
                entity.setStatus("FAILED");
                String detailedError = error != null ? error : "Unknown error";
                if ("Footage is too short or too long".equalsIgnoreCase(detailedError)) {
                    detailedError += " (HeyGen requires at least 2 minutes to 5 minutes of continuous speaking footage)";
                }
                entity.setStatusMessage("HeyGen training failed: " + detailedError);
            } else {
                entity.setStatus("PROCESSING");
                entity.setStatusMessage("HeyGen is processing: " + (status != null ? status : "In progress"));
            }

            entity.setUpdatedAt(LocalDateTime.now());
            return videoAvatarRepository.save(entity);

        } catch (Exception e) {
            log.error("Failed to check status for video avatar {}: {}", id, e.getMessage(), e);
            entity.setStatusMessage("Status check error: " + e.getMessage());
            return videoAvatarRepository.save(entity);
        }
    }

    @Transactional
    public Map<String, String> getConsentSession(Long id) {
        VideoAvatarEntity entity = videoAvatarRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Video Avatar not found: " + id));



        if (entity.getGroupId() == null || entity.getGroupId().isBlank()) {
            throw new IllegalStateException("HeyGen Avatar Group ID is missing for avatar " + id);
        }

        Map<String, String> session = aiProviderService.getHeyGenConsentSession(entity.getGroupId());
        String consentUrl = session.get("url");
        String consentStatus = session.get("consent_status");

        if (consentUrl != null && !consentUrl.isBlank()) {
            entity.setConsentUrl(consentUrl);
        }
        if (consentStatus != null && !consentStatus.isBlank()) {
            entity.setConsentStatus(consentStatus);
        }
        if ("pending".equalsIgnoreCase(consentStatus)) {
            entity.setStatus("PENDING_CONSENT");
            entity.setStatusMessage("HeyGen requires verbal consent verification before activating this digital twin. Click 'Provide Consent' below.");
        }
        videoAvatarRepository.save(entity);

        session.put("id", String.valueOf(entity.getId()));
        session.put("name", entity.getName());
        session.put("avatarId", entity.getAvatarId());
        session.put("thumbnailUrl", entity.getThumbnailUrl());
        return session;
    }

    @Transactional
    public VideoAvatarEntity setDefault(Long id) {
        VideoAvatarEntity entity = videoAvatarRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Video Avatar not found: " + id));

        unsetAllVideoDefaults();
        unsetAllPresenterDefaults();

        entity.setIsDefault(true);
        entity.setUpdatedAt(LocalDateTime.now());
        return videoAvatarRepository.save(entity);
    }

    private Map<String, Object> createPreTrainedAvatar(String avatarId, String name, String gender, String role,
                                                      String defaultVoiceId, String voiceName, String hindiVoiceId,
                                                      String indianEnglishVoiceId, String gestureType,
                                                      String previewImageUrl, String description) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("avatarId", avatarId);
        m.put("name", name);
        m.put("gender", gender);
        m.put("role", role);
        m.put("defaultVoiceId", defaultVoiceId);
        m.put("voiceName", voiceName);
        m.put("hindiVoiceId", hindiVoiceId);
        m.put("indianEnglishVoiceId", indianEnglishVoiceId);
        m.put("gestureType", gestureType);
        m.put("previewImageUrl", previewImageUrl);
        m.put("description", description);
        return m;
    }

    public List<Map<String, Object>> getPreTrainedAvatars() {
        List<Map<String, Object>> list = new java.util.ArrayList<>();

        list.add(createPreTrainedAvatar(
                "Aditya_public_1",
                "Aditya (Indian Health & Wellness Presenter)",
                "Male",
                "Lead Health Educator / Wellness Consultant",
                "1bd801e7e50f4715b92c10829a6e3b3e",
                "Christopher (Deep Confident)",
                "hi-IN-MadhurNeural",
                "en-IN-PrabhatNeural",
                "Upper Body & Natural Hand Gestures",
                "https://files2.heygen.ai/avatar/v3/8c30ef92b2334d919e0f754e38c1a1ae_62150/preview_target.webp",
                "Sharp professional blue blazer, confident camera engagement, natural open-palm explanations for health explainers."
        ));

        list.add(createPreTrainedAvatar(
                "Kavya_standing_indoor_front",
                "Kavya (Lifestyle & Nutrition Specialist)",
                "Female",
                "Clinical Nutritionist / Wellness Advisor",
                "2d5b0e6cf36f460aa7fc47e3eee4ba54",
                "Jenny (Clear & Reassuring)",
                "hi-IN-SwaraNeural",
                "en-IN-NeerjaNeural",
                "Warm Conversational Gestures",
                "https://files2.heygen.ai/avatar/v3/cc0dc576a0b249759f8e26fd892e1a76_39540/preview_target.webp",
                "Professional Indian female specialist, warm conversational movements, ideal for nutrition, women's health & clinic reels."
        ));

        list.add(createPreTrainedAvatar(
                "Ann_Doctor_Standing2_public",
                "Dr. Ann (Clinical Doctor & Physician)",
                "Female",
                "Medical Doctor / Physician",
                "2d5b0e6cf36f460aa7fc47e3eee4ba54",
                "Jenny (Clinical Authoritative)",
                "hi-IN-SwaraNeural",
                "en-IN-NeerjaNeural",
                "Authentic Clinical Gestures",
                "https://files2.heygen.ai/avatar/v3/699a4c2995914d39b2cb311a930d7720_45570/preview_talk_3.webp",
                "Medical coat with stethoscope, authentic clinical posture and reassuring medical hand explanations."
        ));

        list.add(createPreTrainedAvatar(
                "Bradley_Doctor_Front",
                "Dr. Bradley (Medical Director)",
                "Male",
                "Chief Medical Officer / Physician",
                "1bd801e7e50f4715b92c10829a6e3b3e",
                "Christopher (Authoritative)",
                "hi-IN-MadhurNeural",
                "en-IN-PrabhatNeural",
                "Clear Instructional Medical Gestures",
                "https://files2.heygen.ai/avatar/v3/ab93928a6e14473f81880393e92ebfcf_34490/preview_target.webp",
                "Hospital doctor in professional medical attire, clear instructional hand gestures, ideal for clinical health reels."
        ));

        list.add(createPreTrainedAvatar(
                "Abigail_expressive_2024112501",
                "Abigail (Expressive Studio Presenter)",
                "Female",
                "Executive Health Communicator",
                "2d5b0e6cf36f460aa7fc47e3eee4ba54",
                "Jenny (Dynamic Engaging)",
                "hi-IN-SwaraNeural",
                "en-IN-NeerjaNeural",
                "Upper Body Dynamic Emphasis Gestures",
                "https://files2.heygen.ai/avatar/v3/1ad51ab9fee24ae88af067206e14a1d8_44250/preview_target.webp",
                "High-engagement upper body framing with dynamic expressive hand gestures and body movement."
        ));

        list.add(createPreTrainedAvatar(
                "Elenora_FitnessCoach2_public",
                "Elenora (Fitness & Mobility Coach)",
                "Female",
                "Physical Wellness & Mobility Trainer",
                "2d5b0e6cf36f460aa7fc47e3eee4ba54",
                "Jenny (Active & Motivating)",
                "hi-IN-SwaraNeural",
                "en-IN-NeerjaNeural",
                "Energetic Coaching Gestures",
                "https://files2.heygen.ai/avatar/v3/4e5afdfe8bdb44f3ae18b90281ab034c_45610/preview_talk_1.webp",
                "Active athletic presenter with motivating body movements, perfect for physiotherapy, workout, and active lifestyle tips."
        ));

        list.add(createPreTrainedAvatar(
                "Bryan_FitnessCoach_public",
                "Bryan (Performance & Health Coach)",
                "Male",
                "Movement & Strength Specialist",
                "1bd801e7e50f4715b92c10829a6e3b3e",
                "Christopher (Athletic Drive)",
                "hi-IN-MadhurNeural",
                "en-IN-PrabhatNeural",
                "Assertive Modern Coaching Hands",
                "https://files2.heygen.ai/avatar/v3/7fbef4dd1d6641bc8777b26a6aaac85e_45580/preview_talk_1.webp",
                "High-energy athletic delivery, active hands and engaging posture for sports health, stamina, and posture reels."
        ));

        list.add(createPreTrainedAvatar(
                "Adriana_Nurse_Front_public",
                "Adriana (Healthcare Practitioner)",
                "Female",
                "Clinical Nurse / Patient Care Lead",
                "2d5b0e6cf36f460aa7fc47e3eee4ba54",
                "Jenny (Warm Reassuring)",
                "hi-IN-SwaraNeural",
                "en-IN-NeerjaNeural",
                "Empathic Patient Guidance Gestures",
                "https://files2.heygen.ai/avatar/v3/79c13347a41a43059ddbad785a5836d6_47140/preview_target.webp",
                "Healthcare scrubs, empathic and approachable delivery for patient education and medical routine guidance."
        ));

        return list;
    }

    @Transactional
    public VideoAvatarEntity activatePreTrainedAvatar(String avatarId, String name, String role,
                                                    String voiceId, String previewImageUrl) {
        unsetAllVideoDefaults();
        unsetAllPresenterDefaults();

        VideoAvatarEntity entity = videoAvatarRepository.findByAvatarId(avatarId)
                .orElseGet(() -> VideoAvatarEntity.builder()
                        .avatarId(avatarId)
                        .name(name)
                        .description("HeyGen Public Studio Avatar with dynamic hand gestures.")
                        .thumbnailUrl(previewImageUrl)
                        .createdAt(LocalDateTime.now())
                        .build());

        entity.setName(name);
        entity.setStatus("COMPLETED");
        entity.setStatusMessage("HeyGen Public Studio Avatar active with dynamic hand gestures & natural upper body movement.");
        entity.setIsDefault(true);
        entity.setThumbnailUrl(previewImageUrl);
        entity.setUpdatedAt(LocalDateTime.now());
        VideoAvatarEntity saved = videoAvatarRepository.save(entity);

        log.info("Activated pre-trained HeyGen video avatar '{}' (ID: {}) with gestures enabled", name, avatarId);
        return saved;
    }

    @Transactional
    public void deleteAvatar(Long id) {
        videoAvatarRepository.findById(id).ifPresent(entity -> {
            if (entity.getGroupId() != null && !entity.getGroupId().isBlank()) {
                try {
                    aiProviderService.deleteHeyGenAvatarGroup(entity.getGroupId());
                } catch (Exception e) {
                    log.warn("Failed to delete avatar group {} from HeyGen: {}", entity.getGroupId(), e.getMessage());
                }
            }
        });
        videoAvatarRepository.deleteById(id);
    }

    public void unsetAllVideoDefaults() {
        videoAvatarRepository.findAll().forEach(a -> {
            if (Boolean.TRUE.equals(a.getIsDefault())) {
                a.setIsDefault(false);
                videoAvatarRepository.save(a);
            }
        });
    }

    public void unsetAllPresenterDefaults() {
        presenterRepository.findAll().forEach(p -> {
            if (Boolean.TRUE.equals(p.getIsDefault())) {
                p.setIsDefault(false);
                presenterRepository.save(p);
            }
        });
    }
}
