package com.ozo.contentforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.entity.DailyGenerationBatchEntity;
import com.ozo.contentforge.model.enums.AvatarEngine;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.repository.BrandProfileRepository;
import com.ozo.contentforge.repository.DailyGenerationBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
public class ContentBatchService {

    private static final Logger log = LoggerFactory.getLogger(ContentBatchService.class);

    private final DailyGenerationBatchRepository batchRepository;
    private final MasterSettingsService settingsService;
    private final OccasionCalendarService calendarService;
    private final ContentPostService postService;
    private final AiProviderService aiProviderService;
    private final BrandProfileRepository brandRepository;
    private final ObjectMapper objectMapper;

    public ContentBatchService(DailyGenerationBatchRepository batchRepository,
                               MasterSettingsService settingsService,
                               OccasionCalendarService calendarService,
                               ContentPostService postService,
                               AiProviderService aiProviderService,
                               BrandProfileRepository brandRepository,
                               ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.settingsService = settingsService;
        this.calendarService = calendarService;
        this.postService = postService;
        this.aiProviderService = aiProviderService;
        this.brandRepository = brandRepository;
        this.objectMapper = objectMapper;
    }

    public Optional<DailyGenerationBatchEntity> getBatchForDate(LocalDate date) {
        return batchRepository.findByTargetDate(date);
    }

    /**
     * Daily automated scheduler to plan and create batch for today.
     * Runs every day at 06:00 AM IST (or configurable via property).
     */
    @Scheduled(cron = "${contentforge.batch.cron:0 0 6 * * ?}", zone = "Asia/Kolkata")
    public void runDailyScheduledBatch() {
        log.info("Cron triggered: Running automated daily batch generation for today...");
        try {
            planAndCreateBatch(LocalDate.now(), false);
        } catch (Exception e) {
            log.error("Automated daily batch generation failed: {}", e.getMessage(), e);
        }
    }

    public DailyGenerationBatchEntity planAndCreateBatch(LocalDate date) {
        return planAndCreateBatch(date, false);
    }

    @Transactional
    public DailyGenerationBatchEntity planAndCreateBatch(LocalDate date, boolean force) {
        Optional<DailyGenerationBatchEntity> existing = batchRepository.findByTargetDate(date);
        List<ContentPostEntity> existingPosts = postService.getPostsForDate(date);

        // If batch exists and already has generated posts, return existing unless forced
        if (existing.isPresent() && !existingPosts.isEmpty() && !force) {
            log.info("Batch already exists for date {} with {} posts.", date, existingPosts.size());
            return existing.get();
        }

        Map<String, Object> settings = settingsService.getAllSettings();
        int dailyReels = ((Number) settings.getOrDefault("dailyReels", 2)).intValue();
        int dailyPosts = ((Number) settings.getOrDefault("dailyPosts", 0)).intValue();
        int dailyCarousels = ((Number) settings.getOrDefault("dailyCarousels", 0)).intValue();
        String defaultModeStr = (String) settings.getOrDefault("productionMode", "Mode A");
        PostMode defaultMode = defaultModeStr.contains("B") ? PostMode.MODE_B : PostMode.MODE_A;

        String defaultEngineStr = (String) settings.getOrDefault("avatarEngine", "Avatar IV");
        AvatarEngine defaultEngine = defaultEngineStr.contains("III") ? AvatarEngine.AVATAR_III : AvatarEngine.AVATAR_IV;

        int durationTarget = ((Number) settings.getOrDefault("reelDurationTarget", 45)).intValue();

        // Check Occasion Calendar events for this date
        List<ContentCalendarEventEntity> events = calendarService.getEventsForDate(date);

        int plannedReels = dailyReels;
        int plannedPosts = dailyPosts;
        int plannedCarousels = dailyCarousels;

        List<ContentCalendarEventEntity> additiveEvents = new ArrayList<>();
        for (ContentCalendarEventEntity ev : events) {
            if (Boolean.TRUE.equals(ev.getReplaceQuota())) {
                if (ev.getContentType() == ContentType.REEL) plannedReels = ev.getExtraContentCount();
                else if (ev.getContentType() == ContentType.POST) plannedPosts = ev.getExtraContentCount();
                else if (ev.getContentType() == ContentType.CAROUSEL) plannedCarousels = ev.getExtraContentCount();
            } else {
                additiveEvents.add(ev);
                if (ev.getContentType() == ContentType.REEL) plannedReels += ev.getExtraContentCount();
                else if (ev.getContentType() == ContentType.POST) plannedPosts += ev.getExtraContentCount();
                else if (ev.getContentType() == ContentType.CAROUSEL) plannedCarousels += ev.getExtraContentCount();
            }
        }

        Map<String, Object> plannedCounts = new HashMap<>();
        plannedCounts.put("reels", plannedReels);
        plannedCounts.put("posts", plannedPosts);
        plannedCounts.put("carousels", plannedCarousels);
        plannedCounts.put("totalItems", plannedReels + plannedPosts + plannedCarousels);
        plannedCounts.put("activeEventsCount", events.size());

        DailyGenerationBatchEntity batch;
        try {
            if (existing.isPresent()) {
                batch = existing.get();
                batch.setSettingsSnapshot(objectMapper.writeValueAsString(settings));
                batch.setPlannedCountsJson(objectMapper.writeValueAsString(plannedCounts));
                batch.setStatus("IN_PROGRESS");
            } else {
                batch = DailyGenerationBatchEntity.builder()
                        .targetDate(date)
                        .settingsSnapshot(objectMapper.writeValueAsString(settings))
                        .plannedCountsJson(objectMapper.writeValueAsString(plannedCounts))
                        .status("IN_PROGRESS")
                        .build();
            }
            batch = batchRepository.save(batch);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize batch snapshot: " + e.getMessage(), e);
        }

        // Pull active domain / business info from Master Settings or Default Brand
        BrandProfileEntity brand = brandRepository.findByIsDefaultTrue().orElse(null);
        String industry = (brand != null && brand.getIndustry() != null && !brand.getIndustry().isBlank())
                ? brand.getIndustry()
                : (String) settings.getOrDefault("industry", "General Business & Industry");
        String brandTone = (brand != null && brand.getBrandTone() != null && !brand.getBrandTone().isBlank())
                ? brand.getBrandTone()
                : (String) settings.getOrDefault("brandTone", "Engaging, authoritative, relatable, and authentic");
        String targetAudience = (brand != null && brand.getTargetAudience() != null && !brand.getTargetAudience().isBlank())
                ? brand.getTargetAudience()
                : (String) settings.getOrDefault("targetAudience", "Target customers and community followers");

        int totalNeeded = plannedReels + plannedPosts + plannedCarousels;

        // Ask AI for fresh, dynamic topics suited for the partner's specific industry and audience
        // If AI fails, this will throw a RuntimeException with the exact provider error and status
        List<String> dailyTopics = aiProviderService.generateDailyTopics(industry, targetAudience, brandTone, Math.max(totalNeeded, 5), date);
        if (dailyTopics == null || dailyTopics.isEmpty()) {
            throw new IllegalStateException("AI returned zero topics for domain: " + industry);
        }

        // When forced or regenerating, purge stale unpublished/draft posts for this date now that AI succeeded
        if (force) {
            postService.purgeUnpublishedPostsForDate(date);
        }

        int topicCursor = 0;

        // Generate normal quota Reels with AI-selected topics (NO hardcoded topic fallbacks)
        for (int i = 0; i < dailyReels; i++) {
            if (topicCursor >= dailyTopics.size()) {
                throw new IllegalStateException("OpenAI did not return enough distinct topics for " + dailyReels + " reels.");
            }
            String topic = dailyTopics.get(topicCursor++);
            postService.createAndPlanPost(date, batch.getId(), null, ContentType.REEL, topic, defaultMode, defaultEngine, durationTarget);
        }

        // Generate normal quota Posts
        for (int i = 0; i < dailyPosts; i++) {
            if (topicCursor >= dailyTopics.size()) {
                throw new IllegalStateException("OpenAI did not return enough distinct topics for " + dailyPosts + " posts.");
            }
            String topic = dailyTopics.get(topicCursor++);
            postService.createAndPlanPost(date, batch.getId(), null, ContentType.POST, topic, defaultMode, defaultEngine, 0);
        }

        // Generate normal quota Carousels
        for (int i = 0; i < dailyCarousels; i++) {
            if (topicCursor >= dailyTopics.size()) {
                throw new IllegalStateException("OpenAI did not return enough distinct topics for " + dailyCarousels + " carousels.");
            }
            String topic = dailyTopics.get(topicCursor++);
            postService.createAndPlanPost(date, batch.getId(), null, ContentType.CAROUSEL, topic, defaultMode, defaultEngine, 0);
        }

        // Generate additive occasion items
        for (ContentCalendarEventEntity ev : additiveEvents) {
            for (int k = 0; k < ev.getExtraContentCount(); k++) {
                String evTopic = ev.getTitle() + " - " + (ev.getThemeBrief() != null ? ev.getThemeBrief() : "Special Campaign");
                PostMode pMode = ev.getProductionMode() != null ? ev.getProductionMode() : defaultMode;
                postService.createAndPlanPost(date, batch.getId(), ev.getId(), ev.getContentType(), evTopic, pMode, defaultEngine, durationTarget);
            }
        }

        batch.setStatus("COMPLETED");
        return batchRepository.save(batch);
    }
}
