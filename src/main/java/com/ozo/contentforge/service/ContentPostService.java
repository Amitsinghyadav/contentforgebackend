package com.ozo.contentforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.*;
import com.ozo.contentforge.model.enums.*;
import com.ozo.contentforge.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContentPostService {

    private static final Logger log = LoggerFactory.getLogger(ContentPostService.class);

    private final ContentPostRepository postRepository;
    private final ContentSceneRepository sceneRepository;
    private final CarouselSlideRepository slideRepository;
    private final VideoRenderRepository renderRepository;
    private final ContentApprovalRepository approvalRepository;
    private final InstagramPublicationRepository publicationRepository;
    private final MediaJobRepository mediaJobRepository;
    private final BrandProfileRepository brandRepository;
    private final PresenterProfileRepository presenterRepository;
    private final VoiceLibraryRepository voiceRepository;
    private final GeneratedAssetRepository assetRepository;
    private final MasterSettingsService settingsService;
    private final AiProviderService aiProviderService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public ContentPostService(ContentPostRepository postRepository,
                              ContentSceneRepository sceneRepository,
                              CarouselSlideRepository slideRepository,
                              VideoRenderRepository renderRepository,
                              ContentApprovalRepository approvalRepository,
                              InstagramPublicationRepository publicationRepository,
                              MediaJobRepository mediaJobRepository,
                              GeneratedAssetRepository assetRepository,
                              MasterSettingsService settingsService,
                              BrandProfileRepository brandRepository,
                              PresenterProfileRepository presenterRepository,
                              VoiceLibraryRepository voiceRepository,
                              AiProviderService aiProviderService,
                              StorageService storageService,
                              ObjectMapper objectMapper) {
        this.postRepository = postRepository;
        this.sceneRepository = sceneRepository;
        this.slideRepository = slideRepository;
        this.renderRepository = renderRepository;
        this.approvalRepository = approvalRepository;
        this.publicationRepository = publicationRepository;
        this.mediaJobRepository = mediaJobRepository;
        this.assetRepository = assetRepository;
        this.settingsService = settingsService;
        this.brandRepository = brandRepository;
        this.presenterRepository = presenterRepository;
        this.voiceRepository = voiceRepository;
        this.aiProviderService = aiProviderService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    public List<ContentPostEntity> getPostsForDate(LocalDate date) {
        return postRepository.findByTargetDateOrderByScheduledAtAscCreatedAtAsc(date);
    }

    public Optional<ContentPostEntity> getPostById(Long id) {
        return postRepository.findById(id);
    }

    public List<ContentSceneEntity> getScenesForPost(Long postId) {
        return sceneRepository.findByPostIdOrderBySceneNumberAsc(postId);
    }

    public List<CarouselSlideEntity> getSlidesForPost(Long postId) {
        return slideRepository.findByPostIdOrderBySlideNumberAsc(postId);
    }

    public List<MediaJobEntity> getMediaJobsForPost(Long postId) {
        return mediaJobRepository.findByPostIdOrderByStartedAtDesc(postId);
    }

    public List<ContentApprovalEntity> getApprovalsForPost(Long postId) {
        return approvalRepository.findByPostIdOrderByDecidedAtDesc(postId);
    }

    @Transactional
    public int purgeUnpublishedPostsForDate(LocalDate date) {
        List<ContentPostEntity> posts = postRepository.findByTargetDateOrderByScheduledAtAscCreatedAtAsc(date);
        int deleted = 0;
        for (ContentPostEntity post : posts) {
            if (post.getStatus() == PostStatus.PUBLISHED || post.getStatus() == PostStatus.SCHEDULED) {
                continue;
            }
            deletePost(post.getId());
            deleted++;
        }
        log.info("Purged {} unpublished draft posts for date {}", deleted, date);
        return deleted;
    }

    @Transactional
    public void deletePost(Long postId) {
        sceneRepository.deleteByPostId(postId);
        slideRepository.deleteByPostId(postId);
        approvalRepository.deleteByPostId(postId);
        mediaJobRepository.deleteByPostId(postId);
        assetRepository.deleteByPostId(postId);
        postRepository.deleteById(postId);
    }

    /**
     * Generate an initial post with structured storyboard
     */
    @Transactional
    public ContentPostEntity createAndPlanPost(LocalDate date, Long batchId, Long eventId,
                                              ContentType contentType, String topic, PostMode mode,
                                              AvatarEngine engine, int durationSeconds) {
        String idempotencyKey = String.format("%s_%s_%s_%s", date, contentType,
                UUID.randomUUID().toString().substring(0, 8),
                eventId != null ? eventId : "normal");

        Optional<ContentPostEntity> existing = postRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        BrandProfileEntity brand = brandRepository.findByIsDefaultTrue().orElse(null);
        Map<String, Object> settings = settingsService.getAllSettings();
        String industry = (brand != null && brand.getIndustry() != null && !brand.getIndustry().isBlank())
                ? brand.getIndustry()
                : extractString(settings.getOrDefault("industry", "General Business & Industry"));
        String brandTone = (brand != null && brand.getBrandTone() != null && !brand.getBrandTone().isBlank())
                ? brand.getBrandTone()
                : extractString(settings.getOrDefault("brandTone", "Engaging, authoritative, relatable, and authentic"));
        String audience = (brand != null && brand.getTargetAudience() != null && !brand.getTargetAudience().isBlank())
                ? brand.getTargetAudience()
                : extractString(settings.getOrDefault("targetAudience", "Target customers and community followers"));

        int brollCount = extractInt(settings.get("brollCount"), 2);
        int brollClipDuration = extractInt(settings.get("brollClipDuration"), 5);
        int defaultReelDur = extractInt(settings.get("reelDurationTarget"), 45);
        int effectiveDuration = durationSeconds > 0 ? durationSeconds : defaultReelDur;

        PresenterProfileEntity presenter = presenterRepository.findByIsDefaultTrue().orElse(null);
        VoiceLibraryEntity voice = voiceRepository.findByIsDefaultTrue().orElse(null);

        Map<String, Object> storyboard = aiProviderService.generateScriptAndStoryboard(
                topic, industry, brandTone, contentType, mode, effectiveDuration, audience, brollCount, brollClipDuration
        );

        String scriptJson = "";
        try {
            scriptJson = objectMapper.writeValueAsString(storyboard);
        } catch (Exception ignored) {}

        ContentPostEntity post = ContentPostEntity.builder()
                .idempotencyKey(idempotencyKey)
                .batchId(batchId)
                .eventId(eventId)
                .targetDate(date)
                .contentType(contentType)
                .topic(topic)
                .hook(extractString(storyboard.get("hook")))
                .presenterScript(extractString(storyboard.get("presenterScript")))
                .voiceoverScript(extractString(storyboard.get("voiceoverScript")))
                .scriptJson(scriptJson)
                .caption(extractCaption(storyboard.get("caption")))
                .hashtags(extractHashtags(storyboard.get("hashtags")))
                .cta(extractString(storyboard.get("cta")))
                .mode(mode != null ? mode : PostMode.MODE_A)
                .avatarEngine(engine != null ? engine : AvatarEngine.AVATAR_IV)
                .presenterId(presenter != null ? presenter.getId() : null)
                .voiceId(voice != null ? voice.getId() : null)
                .targetDurationSeconds(effectiveDuration)
                .status(PostStatus.PENDING_TEXT_APPROVAL)
                .clinicalReviewRequired(Boolean.TRUE.equals(storyboard.get("clinicalReviewRequired")))
                .estimatedCostUsd(calculateEstimatedCost(contentType, mode, engine, effectiveDuration))
                .build();

        post = postRepository.save(post);

        // Populate scenes if REEL
        if (contentType == ContentType.REEL && storyboard.containsKey("scenes")) {
            Object scenesObj = storyboard.get("scenes");
            if (scenesObj instanceof List<?> rawSceneList) {
                int runningStart = 0;
                StringBuilder presenterScriptBuilder = new StringBuilder();
                StringBuilder voiceoverScriptBuilder = new StringBuilder();

                for (int i = 0; i < rawSceneList.size(); i++) {
                    Object item = rawSceneList.get(i);
                    if (!(item instanceof Map<?, ?> rawMap)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sMap = (Map<String, Object>) rawMap;
                    int sceneNum = i + 1;
                    String typeStr = extractString(sMap.get("type")).toUpperCase();
                    SceneType sType = typeStr.contains("BROLL") ? SceneType.BROLL : SceneType.AVATAR;
                    int defaultDur = (sType == SceneType.BROLL ? brollClipDuration : 10);
                    int dur = extractInt(sMap.get("durationSeconds"), defaultDur);
                    String spoken = extractString(sMap.get("spokenText"));
                    String promptText = extractString(sMap.get("visualPrompt"));
                    String camDir = extractString(sMap.get("cameraDirection"));
                    if (camDir.isBlank()) camDir = "Cinematic 9:16";
                    String aspect = extractString(sMap.get("aspectRatio"));
                    if (aspect.isBlank()) aspect = "9:16";

                    ContentSceneEntity scene = ContentSceneEntity.builder()
                            .postId(post.getId())
                            .sceneNumber(sceneNum)
                            .sceneType(sType)
                            .prompt(promptText)
                            .spokenText(spoken)
                            .spokenLineReference("scene_" + sceneNum)
                            .cameraDirection(camDir)
                            .aspectRatio(aspect)
                            .durationSeconds(dur)
                            .startTimeSeconds(runningStart)
                            .status(JobStatus.QUEUED)
                            .costUsd(0.0)
                            .build();
                    sceneRepository.save(scene);

                    runningStart += dur;

                    if (!spoken.isBlank()) {
                        if (sType == SceneType.AVATAR) {
                            if (presenterScriptBuilder.length() > 0) presenterScriptBuilder.append(" ");
                            presenterScriptBuilder.append(spoken.trim());
                        } else {
                            if (voiceoverScriptBuilder.length() > 0) voiceoverScriptBuilder.append(" ");
                            voiceoverScriptBuilder.append(spoken.trim());
                        }
                    }
                }

                if (presenterScriptBuilder.length() > 0) {
                    post.setPresenterScript(presenterScriptBuilder.toString());
                }
                if (voiceoverScriptBuilder.length() > 0) {
                    post.setVoiceoverScript(voiceoverScriptBuilder.toString());
                }
                postRepository.save(post);
            }
        } else if (contentType == ContentType.CAROUSEL) {
            List<Map<String, Object>> dynamicSlides = new ArrayList<>();
            if (storyboard.containsKey("slides") && storyboard.get("slides") instanceof List<?> rawSlides) {
                for (Object slObj : rawSlides) {
                    if (slObj instanceof Map<?, ?> slMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> castMap = (Map<String, Object>) slMap;
                        dynamicSlides.add(castMap);
                    }
                }
            }

            if (!dynamicSlides.isEmpty()) {
                for (int i = 0; i < dynamicSlides.size(); i++) {
                    Map<String, Object> sl = dynamicSlides.get(i);
                    String sTitle = extractString(sl.getOrDefault("title", "Insight #" + (i + 1)));
                    String sBody = extractString(sl.containsKey("body") ? sl.get("body") : sl.get("bodyContent"));
                    try {
                        GeneratedAssetEntity slideAsset = storageService.generateBrandedSlideSvg(
                                sTitle, sBody, industry.toUpperCase(), i + 1, post.getId()
                        );
                        CarouselSlideEntity slide = CarouselSlideEntity.builder()
                                .postId(post.getId())
                                .slideNumber(i + 1)
                                .title(sTitle)
                                .bodyContent(sBody)
                                .visualPrompt("Modern high-impact presentation card for " + industry)
                                .assetId(slideAsset.getId())
                                .imageUrl(slideAsset.getStorageUrl())
                                .status(JobStatus.COMPLETED)
                                .build();
                        slideRepository.save(slide);
                    } catch (Exception e) {
                        log.error("Failed to generate slide asset: {}", e.getMessage());
                    }
                }
            } else {
                String[] slideTitles = new String[] {
                        topic,
                        "Key Challenge & Context",
                        "The Critical Insight",
                        "Common Misconceptions",
                        "Actionable Implementation",
                        "Next Steps & Summary"
                };
                String[] slideContents = new String[] {
                        "An in-depth breakdown of " + topic + " and why it matters in " + industry + " today.",
                        "Analyzing the primary obstacles and current market shifts surrounding " + topic + ".",
                        "Why traditional approaches often fall short and what modern data reveals about " + topic + ".",
                        "What standard assumptions get wrong and the pitfalls to avoid.",
                        "1. Audit your current position.\n2. Apply strategic adjustments.\n3. Monitor key performance metrics.",
                        "Stay ahead in " + industry + " with verified insights and proactive execution."
                };

                for (int i = 0; i < 6; i++) {
                    try {
                        GeneratedAssetEntity slideAsset = storageService.generateBrandedSlideSvg(
                                slideTitles[i], slideContents[i], industry.toUpperCase(), i + 1, post.getId()
                        );
                        CarouselSlideEntity slide = CarouselSlideEntity.builder()
                                .postId(post.getId())
                                .slideNumber(i + 1)
                                .title(slideTitles[i])
                                .bodyContent(slideContents[i])
                                .visualPrompt("Modern minimalist business infographic card")
                                .assetId(slideAsset.getId())
                                .imageUrl(slideAsset.getStorageUrl())
                                .status(JobStatus.COMPLETED)
                                .build();
                        slideRepository.save(slide);
                    } catch (Exception e) {
                        log.error("Failed to generate slide asset: {}", e.getMessage());
                    }
                }
            }
        }

        return post;
    }

    /**
     * Phase 3: Text Approval
     * Approves edited script/caption/storyboard and kicks off Voice, Avatar, Runway B-roll generation
     */
    @Transactional
    public ContentPostEntity approveText(Long postId, String updatedPresenterScript, String updatedVoiceoverScript,
                                         String updatedCaption, String updatedCta, String reviewerId) {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        if (updatedPresenterScript != null && !updatedPresenterScript.trim().isEmpty()) {
            post.setPresenterScript(updatedPresenterScript);
        }
        if (updatedVoiceoverScript != null && !updatedVoiceoverScript.trim().isEmpty()) {
            post.setVoiceoverScript(updatedVoiceoverScript);
        }
        if (updatedCaption != null && !updatedCaption.trim().isEmpty()) {
            post.setCaption(updatedCaption);
        }
        if (updatedCta != null && !updatedCta.trim().isEmpty()) {
            post.setCta(updatedCta);
        }

        post.setStatus(PostStatus.TEXT_APPROVED);
        post.setClinicalReviewApproved(true);
        postRepository.save(post);

        // Record Approval
        approvalRepository.save(ContentApprovalEntity.builder()
                .postId(postId)
                .approvalType(ApprovalType.TEXT_APPROVAL)
                .decision(ApprovalDecision.APPROVED)
                .reviewerId(reviewerId != null ? reviewerId : "admin")
                .comments("Text, script and storyboard approved for media generation")
                .decidedAt(LocalDateTime.now())
                .build());

        // Trigger Media Generation asynchronously in background thread so approval transaction commits immediately
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                triggerMediaGeneration(postId);
            } catch (Exception e) {
                log.error("Media generation background error for post {}: {}", postId, e.getMessage(), e);
            }
        });

        return post;
    }

    /**
     * Asynchronous Media Pipeline: Voice -> Avatar & B-roll in parallel -> Composition
     */
    @Async
    public void triggerMediaGeneration(Long postId) {
        log.info("Triggering media pipeline for post {}...", postId);
        ContentPostEntity post = postRepository.findById(postId).orElse(null);
        if (post == null) return;

        // Auto-recover presenter and voiceover scripts from scriptJson if empty
        if ((post.getPresenterScript() == null || post.getPresenterScript().isBlank()) && post.getScriptJson() != null && !post.getScriptJson().isBlank()) {
            try {
                Map<String, Object> map = objectMapper.readValue(post.getScriptJson(), new TypeReference<Map<String, Object>>() {});
                if (map.containsKey("presenterScript") && map.get("presenterScript") != null) {
                    post.setPresenterScript(map.get("presenterScript").toString());
                }
                if (map.containsKey("voiceoverScript") && map.get("voiceoverScript") != null) {
                    post.setVoiceoverScript(map.get("voiceoverScript").toString());
                }
                post = postRepository.save(post);
            } catch (Exception ignored) {}
        }

        post.setStatus(PostStatus.MEDIA_GENERATING);
        postRepository.save(post);

        try {
            double totalCost = 0.0;

            // 1. Voice generation (default to Amit Singh Yadav's real cloned ElevenLabs voice)
            PresenterProfileEntity presenter = null;
            if (post.getPresenterId() != null) {
                presenter = presenterRepository.findById(post.getPresenterId()).orElse(null);
            }
            if (presenter == null) {
                presenter = presenterRepository.findByIsDefaultTrue().orElse(null);
            }
            VoiceLibraryEntity voice = voiceRepository.findByIsDefaultTrue().orElse(null);
            String voiceId = (voice != null && voice.getProviderVoiceId() != null && !voice.getProviderVoiceId().isBlank())
                    ? voice.getProviderVoiceId()
                    : "rRAzMEZ6U57ivzmuPBhL";

            List<ContentSceneEntity> scenes = sceneRepository.findByPostIdOrderBySceneNumberAsc(postId);
            StringBuilder narrationBuilder = new StringBuilder();
            for (ContentSceneEntity sc : scenes) {
                if (sc.getSpokenText() != null && !sc.getSpokenText().isBlank()) {
                    if (narrationBuilder.length() > 0) narrationBuilder.append(" ");
                    narrationBuilder.append(sc.getSpokenText().trim());
                }
            }
            String narrationText = narrationBuilder.length() > 0
                    ? narrationBuilder.toString()
                    : ((post.getPresenterScript() != null ? post.getPresenterScript() : "") + " "
                       + (post.getVoiceoverScript() != null ? post.getVoiceoverScript() : ""));

            GeneratedAssetEntity voiceAsset = aiProviderService.generateVoiceAudio(postId, narrationText, voiceId);
            post.setVoiceAudioUrl(voiceAsset.getStorageUrl());

            // 2. Generate Master Avatar Video ONCE with complete master audio track
            boolean hasAvatarScenes = scenes.stream().anyMatch(s -> s.getSceneType() == SceneType.AVATAR);
            GeneratedAssetEntity avatarAsset = null;

            if (hasAvatarScenes) {
                int totalDuration = post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45;
                log.info("Generating master HeyGen avatar video for post {} ({}s duration)...", postId, totalDuration);

                String resolvedAvatar = (presenter != null && presenter.getAvatarId() != null && !presenter.getAvatarId().isBlank())
                        ? presenter.getAvatarId()
                        : null;

                avatarAsset = aiProviderService.generateAvatarVideo(
                        postId, null, post.getAvatarEngine(),
                        resolvedAvatar,
                        post.getPresenterScript(), totalDuration,
                        post.getVoiceAudioUrl()
                );
                post.setAvatarVideoUrl(avatarAsset.getStorageUrl());
                double avatarCost = totalDuration * (post.getAvatarEngine() == AvatarEngine.AVATAR_IV ? (4.0 / 60.0) : (1.0 / 60.0));
                totalCost += avatarCost;
            }

            // 3. Process scenes: assign master avatar to AVATAR scenes, call Runway for B-ROLL scenes
            for (ContentSceneEntity scene : scenes) {
                scene.setStatus(JobStatus.IN_PROGRESS);
                sceneRepository.save(scene);

                if (scene.getSceneType() == SceneType.AVATAR) {
                    if (avatarAsset != null) {
                        scene.setMediaUrl(avatarAsset.getStorageUrl());
                        scene.setStatus(JobStatus.COMPLETED);
                        scene.setCostUsd(0.0); // Cost captured at post level
                    }
                } else if (scene.getSceneType() == SceneType.BROLL) {
                    try {
                        GeneratedAssetEntity brollAsset = aiProviderService.generateRunwayBroll(
                                postId, scene.getId(), scene.getPrompt(), scene.getDurationSeconds()
                        );
                        scene.setMediaUrl(brollAsset.getStorageUrl());
                        scene.setStatus(JobStatus.COMPLETED);
                        double cost = scene.getDurationSeconds() * 0.12; // $0.12/sec Gen-4.5
                        scene.setCostUsd(cost);
                        totalCost += cost;
                    } catch (Exception brollEx) {
                        log.warn("Runway B-roll scene {} generation failed: {}. Gracefully falling back to master avatar.", scene.getId(), brollEx.getMessage());
                        scene.setStatus(JobStatus.FAILED);
                        scene.setErrorMessage(brollEx.getMessage());
                        if (avatarAsset != null) {
                            scene.setMediaUrl(avatarAsset.getStorageUrl());
                        }
                    }
                }
                sceneRepository.save(scene);
            }

            // 3. Composition
            GeneratedAssetEntity renderAsset = aiProviderService.compositeVideo(
                    postId, post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45, post.getTopic(), post.getAvatarVideoUrl(), scenes
            );

            // Record Video Render
            renderRepository.save(VideoRenderEntity.builder()
                    .postId(postId)
                    .storageUrl(renderAsset.getStorageUrl())
                    .durationSeconds((double) (post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45))
                    .resolution("1080x1920")
                    .renderVersion(1)
                    .status(JobStatus.COMPLETED)
                    .build());

            post.setFinalRenderUrl(renderAsset.getStorageUrl());
            post.setThumbnailUrl(renderAsset.getStorageUrl());
            post.setActualCostUsd(totalCost + 0.10);
            post.setStatus(PostStatus.PENDING_FINAL_APPROVAL);
            postRepository.save(post);
            log.info("Media pipeline completed for post {}. Ready for final approval.", postId);

        } catch (Exception e) {
            log.error("Media generation failed for post {}: {}", postId, e.getMessage(), e);
            post.setStatus(PostStatus.FAILED);
            post.setErrorMessage("Media generation error: " + e.getMessage());
            postRepository.save(post);
        }
    }

    /**
     * Regenerate an individual scene without redoing the entire Reel
     */
    @Transactional
    public ContentSceneEntity regenerateScene(Long postId, Integer sceneNumber, String newPrompt) throws Exception {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        ContentSceneEntity scene = sceneRepository.findByPostIdAndSceneNumber(postId, sceneNumber)
                .orElseThrow(() -> new IllegalArgumentException("Scene not found: " + sceneNumber));

        if (newPrompt != null && !newPrompt.trim().isEmpty()) {
            scene.setPrompt(newPrompt);
        }

        scene.setStatus(JobStatus.IN_PROGRESS);
        scene.setRetryCount(scene.getRetryCount() + 1);
        sceneRepository.save(scene);

        GeneratedAssetEntity asset;
        if (scene.getSceneType() == SceneType.AVATAR) {
            log.info("Regenerating master avatar video for post {}...", postId);
            PresenterProfileEntity presenter = null;
            if (post.getPresenterId() != null) {
                presenter = presenterRepository.findById(post.getPresenterId()).orElse(null);
            }
            if (presenter == null) {
                presenter = presenterRepository.findByIsDefaultTrue().orElse(null);
            }
            String resolvedAvatar = (presenter != null && presenter.getAvatarId() != null && !presenter.getAvatarId().isBlank())
                    ? presenter.getAvatarId()
                    : null;

            int totalDuration = post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45;
            asset = aiProviderService.generateAvatarVideo(
                    postId, null, post.getAvatarEngine(),
                    resolvedAvatar,
                    post.getPresenterScript(), totalDuration,
                    post.getVoiceAudioUrl()
            );
            post.setAvatarVideoUrl(asset.getStorageUrl());

            // Update all avatar scenes to reference the new master avatar video
            List<ContentSceneEntity> allScenes = sceneRepository.findByPostIdOrderBySceneNumberAsc(postId);
            for (ContentSceneEntity s : allScenes) {
                if (s.getSceneType() == SceneType.AVATAR) {
                    s.setMediaUrl(asset.getStorageUrl());
                    s.setStatus(JobStatus.COMPLETED);
                    sceneRepository.save(s);
                }
            }
        } else {
            log.info("Regenerating Runway B-roll for scene {} of post {}...", sceneNumber, postId);
            asset = aiProviderService.generateRunwayBroll(
                    postId, scene.getId(), scene.getPrompt(), scene.getDurationSeconds()
            );
            scene.setMediaUrl(asset.getStorageUrl());
            scene.setStatus(JobStatus.COMPLETED);
            sceneRepository.save(scene);
        }

        // Trigger FFmpeg recomposition to stitch the newly regenerated clip into the final Reel
        GeneratedAssetEntity renderAsset = aiProviderService.compositeVideo(
                postId, post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45, post.getTopic(), post.getAvatarVideoUrl(), sceneRepository.findByPostIdOrderBySceneNumberAsc(postId)
        );
        post.setFinalRenderUrl(renderAsset.getStorageUrl());
        post.setThumbnailUrl(renderAsset.getStorageUrl());

        int nextVersion = renderRepository.findFirstByPostIdOrderByRenderVersionDesc(postId)
                .map(r -> (r.getRenderVersion() != null ? r.getRenderVersion() + 1 : 2))
                .orElse(1);

        renderRepository.save(VideoRenderEntity.builder()
                .postId(postId)
                .storageUrl(renderAsset.getStorageUrl())
                .durationSeconds((double) (post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45))
                .resolution("1080x1920")
                .renderVersion(nextVersion)
                .status(JobStatus.COMPLETED)
                .build());

        postRepository.save(post);

        return scene;
    }

    /**
     * Remerge existing avatar and scene B-rolls into a final composite Reel without regenerating any media assets.
     */
    @Transactional
    public ContentPostEntity remergeVideo(Long postId) throws Exception {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        if (post.getAvatarVideoUrl() == null || post.getAvatarVideoUrl().isBlank()) {
            throw new IllegalStateException("Cannot remerge video: No avatar video has been generated yet for post " + postId);
        }

        List<ContentSceneEntity> scenes = sceneRepository.findByPostIdOrderBySceneNumberAsc(postId);
        log.info("Remerging final 9:16 Reel for post {} from existing avatar and {} scenes...", postId, scenes.size());

        GeneratedAssetEntity renderAsset = aiProviderService.compositeVideo(
                postId,
                post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45,
                post.getTopic(),
                post.getAvatarVideoUrl(),
                scenes
        );

        int nextVersion = renderRepository.findFirstByPostIdOrderByRenderVersionDesc(postId)
                .map(r -> (r.getRenderVersion() != null ? r.getRenderVersion() + 1 : 2))
                .orElse(1);

        renderRepository.save(VideoRenderEntity.builder()
                .postId(postId)
                .storageUrl(renderAsset.getStorageUrl())
                .durationSeconds((double) (post.getTargetDurationSeconds() != null ? post.getTargetDurationSeconds() : 45))
                .resolution("1080x1920")
                .renderVersion(nextVersion)
                .status(JobStatus.COMPLETED)
                .build());

        post.setFinalRenderUrl(renderAsset.getStorageUrl());
        post.setThumbnailUrl(renderAsset.getStorageUrl());
        return postRepository.save(post);
    }

    /**
     * Phase 8: Final Media Approval
     */
    @Transactional
    public ContentPostEntity approveMedia(Long postId, LocalDateTime scheduledAt, String reviewerId) {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        post.setStatus(PostStatus.SCHEDULED);
        post.setScheduledAt(scheduledAt != null ? scheduledAt : LocalDateTime.now().plusHours(2));
        postRepository.save(post);

        approvalRepository.save(ContentApprovalEntity.builder()
                .postId(postId)
                .approvalType(ApprovalType.FINAL_MEDIA_APPROVAL)
                .decision(ApprovalDecision.APPROVED)
                .reviewerId(reviewerId != null ? reviewerId : "admin")
                .comments("Final video media approved and scheduled for Instagram release")
                .decidedAt(LocalDateTime.now())
                .build());

        return post;
    }

    /**
     * Phase 8: Final Media Rejection
     */
    @Transactional
    public ContentPostEntity rejectMedia(Long postId, String comments, String reviewerId) {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        post.setStatus(PostStatus.PENDING_TEXT_APPROVAL);
        post.setErrorMessage("Rejected by reviewer: " + comments);
        postRepository.save(post);

        approvalRepository.save(ContentApprovalEntity.builder()
                .postId(postId)
                .approvalType(ApprovalType.FINAL_MEDIA_APPROVAL)
                .decision(ApprovalDecision.REJECTED)
                .reviewerId(reviewerId != null ? reviewerId : "admin")
                .comments(comments)
                .decidedAt(LocalDateTime.now())
                .build());

        return post;
    }

    /**
     * Phase 10: Publish Now to Instagram
     */
    @Transactional
    public ContentPostEntity publishNow(Long postId) {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        post.setStatus(PostStatus.PUBLISHING);
        postRepository.save(post);

        Map<String, String> pubResult = aiProviderService.publishToInstagram(
                postId, post.getFinalRenderUrl(), post.getThumbnailUrl(), post.getCaption() + "\n\n" + post.getHashtags()
        );

        post.setInstagramContainerId(pubResult.get("containerId"));
        post.setInstagramMediaId(pubResult.get("mediaId"));
        post.setPublishedPermalink(pubResult.get("permalink"));
        post.setPublishedAt(LocalDateTime.now());
        post.setStatus(PostStatus.PUBLISHED);

        publicationRepository.save(InstagramPublicationEntity.builder()
                .postId(postId)
                .containerId(pubResult.get("containerId"))
                .mediaId(pubResult.get("mediaId"))
                .permalink(pubResult.get("permalink"))
                .scheduledAt(post.getScheduledAt())
                .publishedAt(LocalDateTime.now())
                .status("PUBLISHED")
                .build());

        return postRepository.save(post);
    }

    /**
     * Carousel Slide update and regeneration
     */
    @Transactional
    public CarouselSlideEntity updateSlide(Long postId, int slideNumber, String title, String bodyContent) throws Exception {
        CarouselSlideEntity slide = slideRepository.findByPostIdAndSlideNumber(postId, slideNumber)
                .orElseThrow(() -> new IllegalArgumentException("Slide not found: " + slideNumber));

        if (title != null) slide.setTitle(title);
        if (bodyContent != null) slide.setBodyContent(bodyContent);

        // Regenerate slide graphic
        GeneratedAssetEntity asset = storageService.generateBrandedSlideSvg(
                slide.getTitle(), slide.getBodyContent(), "METABOLIC HEALTH", slideNumber, postId
        );
        slide.setAssetId(asset.getId());
        slide.setImageUrl(asset.getStorageUrl());
        slide.setStatus(JobStatus.COMPLETED);

        return slideRepository.save(slide);
    }

    private String extractString(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(" "));
        }
        return val.toString();
    }

    private String extractCaption(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.joining("\n\n"));
        }
        return val.toString();
    }

    private String extractHashtags(Object val) {
        if (val == null) return "";
        if (val instanceof String s) return s;
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.startsWith("#") ? s : "#" + s)
                    .collect(Collectors.joining(" "));
        }
        return val.toString();
    }

    private int extractInt(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private double calculateEstimatedCost(ContentType type, PostMode mode, AvatarEngine engine, int duration) {
        if (type == ContentType.POST) return 0.04;
        if (type == ContentType.CAROUSEL) return 0.24; // 6 slides * $0.04
        // REEL
        int avatarSec = (mode == PostMode.MODE_A) ? 15 : duration;
        int brollSec = (mode == PostMode.MODE_A) ? (duration - 15) : 10;
        double avatarRate = (engine == AvatarEngine.AVATAR_IV) ? (4.0 / 60.0) : (1.0 / 60.0);
        return (avatarSec * avatarRate) + (brollSec * 0.12) + 0.10;
    }
}
