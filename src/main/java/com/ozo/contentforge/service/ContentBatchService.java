package com.ozo.contentforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.entity.DailyGenerationBatchEntity;
import com.ozo.contentforge.model.enums.AvatarEngine;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.model.enums.RecurrenceRule;
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
    private final PlanService planService;
    private final ObjectMapper objectMapper;

    public ContentBatchService(DailyGenerationBatchRepository batchRepository,
                               MasterSettingsService settingsService,
                               OccasionCalendarService calendarService,
                               ContentPostService postService,
                               AiProviderService aiProviderService,
                               BrandProfileRepository brandRepository,
                               PlanService planService,
                               ObjectMapper objectMapper) {
        this.batchRepository = batchRepository;
        this.settingsService = settingsService;
        this.calendarService = calendarService;
        this.postService = postService;
        this.aiProviderService = aiProviderService;
        this.brandRepository = brandRepository;
        this.planService = planService;
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
        return planAndCreateBatch(date, false, null, null);
    }

    public DailyGenerationBatchEntity planAndCreateBatch(LocalDate date, boolean force) {
        return planAndCreateBatch(date, force, null, null);
    }

    @Transactional
    public DailyGenerationBatchEntity planAndCreateBatch(LocalDate date, boolean force, ContentType requestedType, String requestedTopic) {
        Optional<DailyGenerationBatchEntity> existing = batchRepository.findByTargetDate(date);
        List<ContentPostEntity> existingPosts = postService.getPostsForDate(date);

        // If batch exists and already has generated posts, return existing unless forced
        if (existing.isPresent() && !existingPosts.isEmpty() && !force) {
            log.info("Batch already exists for date {} with {} posts.", date, existingPosts.size());
            return existing.get();
        }

        Map<String, Object> settings = settingsService.getAllSettings();
        String defaultModeStr = (String) settings.getOrDefault("productionMode", "Mode A");
        PostMode defaultMode = defaultModeStr.contains("B") ? PostMode.MODE_B : PostMode.MODE_A;

        String defaultEngineStr = (String) settings.getOrDefault("avatarEngine", "Avatar IV");
        AvatarEngine defaultEngine = defaultEngineStr.contains("III") ? AvatarEngine.AVATAR_III : AvatarEngine.AVATAR_IV;

        int durationTarget = ((Number) settings.getOrDefault("reelDurationTarget", 45)).intValue();

        // Check Reel Planner Calendar events scheduled for this target date
        List<ContentCalendarEventEntity> plannedEvents = calendarService.getEventsForDate(date);

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

        int totalNeeded = Math.max(plannedEvents.size(), 1);
        List<String> dailyTopics = aiProviderService.generateDailyTopics(industry, targetAudience, brandTone, Math.max(totalNeeded, 5), date);

        // When forced or regenerating, purge stale unpublished/draft posts for this date now that AI succeeded
        if (force) {
            postService.purgeUnpublishedPostsForDate(date);
        }

        int plannedReels = 0;
        int plannedPosts = 0;
        int plannedCarousels = 0;
        int topicCursor = 0;

        DailyGenerationBatchEntity batch;
        try {
            if (existing.isPresent()) {
                batch = existing.get();
                batch.setSettingsSnapshot(objectMapper.writeValueAsString(settings));
                batch.setStatus("IN_PROGRESS");
            } else {
                batch = DailyGenerationBatchEntity.builder()
                        .targetDate(date)
                        .settingsSnapshot(objectMapper.writeValueAsString(settings))
                        .status("IN_PROGRESS")
                        .build();
            }
            batch = batchRepository.save(batch);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize batch snapshot: " + e.getMessage(), e);
        }

        if (!plannedEvents.isEmpty()) {
            log.info("Generating batch for date {} relying on {} scheduled items in Reel Planner...", date, plannedEvents.size());
            for (ContentCalendarEventEntity ev : plannedEvents) {
                ContentType cType = ev.getContentType() != null ? ev.getContentType() : ContentType.REEL;
                String topic = (ev.getTitle() != null && !ev.getTitle().isBlank() && !ev.getTitle().contains("Daily Preventive Health"))
                        ? ev.getTitle()
                        : (topicCursor < dailyTopics.size() ? dailyTopics.get(topicCursor++) : "Essential Guidance on " + industry);

                if (ev.getThemeBrief() != null && !ev.getThemeBrief().isBlank() && !topic.contains(ev.getThemeBrief())) {
                    topic = topic + " (" + ev.getThemeBrief() + ")";
                }

                PostMode pMode = ev.getProductionMode() != null ? ev.getProductionMode() : defaultMode;
                int dur = (cType == ContentType.REEL) ? durationTarget : 0;

                postService.createAndPlanPost(date, batch.getId(), ev.getId(), cType, topic, pMode, defaultEngine, dur);

                if (cType == ContentType.REEL) plannedReels++;
                else if (cType == ContentType.POST) plannedPosts++;
                else if (cType == ContentType.CAROUSEL) plannedCarousels++;

                ev.setStatus("GENERATED");
                calendarService.updateEvent(ev.getId(), ev.getTitle(), ev.getEventDate(), ev.getRecurrenceRule(),
                        ev.getThemeBrief(), ev.getExtraContentCount(), ev.getContentType(), ev.getCarouselSlideCount(),
                        ev.getLanguage(), ev.getProductionMode(), ev.getPublishTime(), ev.getReplaceQuota(), "GENERATED");
            }
        } else if (requestedType != null) {
            // User specified what format they want to generate for this empty date
            String chosenTopic;
            if (requestedTopic != null && !requestedTopic.trim().isEmpty()) {
                chosenTopic = requestedTopic.trim();
            } else {
                chosenTopic = (dailyTopics != null && !dailyTopics.isEmpty())
                        ? dailyTopics.get(0)
                        : "Essential Guidance on " + industry;
            }

            log.info("No items were pre-scheduled for {}. User requested to generate {} on topic: {}", date, requestedType, chosenTopic);

            // Deduct quota
            planService.consumeQuota(null, requestedType.name());

            // Register the chosen event on the Reel Planner calendar as GENERATED
            ContentCalendarEventEntity userEvent = calendarService.createEvent(
                    chosenTopic, date, RecurrenceRule.NONE, "Manual Trigger", 1,
                    requestedType, 6, "Hinglish", defaultMode, "11:00 AM", false
            );
            userEvent.setStatus("GENERATED");
            calendarService.updateEvent(userEvent.getId(), userEvent.getTitle(), userEvent.getEventDate(), userEvent.getRecurrenceRule(),
                    userEvent.getThemeBrief(), userEvent.getExtraContentCount(), userEvent.getContentType(), userEvent.getCarouselSlideCount(),
                    userEvent.getLanguage(), userEvent.getProductionMode(), userEvent.getPublishTime(), userEvent.getReplaceQuota(), "GENERATED");

            int dur = (requestedType == ContentType.REEL) ? durationTarget : 0;
            postService.createAndPlanPost(date, batch.getId(), userEvent.getId(), requestedType, chosenTopic, defaultMode, defaultEngine, dur);

            if (requestedType == ContentType.REEL) plannedReels++;
            else if (requestedType == ContentType.POST) plannedPosts++;
            else if (requestedType == ContentType.CAROUSEL) plannedCarousels++;
        } else {
            // No events scheduled on Reel Planner, and no content type specified by the user.
            // Do NOT automatically register any Reel!
            log.info("No items scheduled in Reel Planner for date {} and no content type specified. Nothing generated.", date);
            batch.setStatus("NO_SCHEDULED_CONTENT");
            Map<String, Object> emptyCounts = new HashMap<>();
            emptyCounts.put("reels", 0);
            emptyCounts.put("posts", 0);
            emptyCounts.put("carousels", 0);
            emptyCounts.put("totalItems", 0);
            emptyCounts.put("message", "No content scheduled on Reel Planner for this date.");
            try {
                batch.setPlannedCountsJson(objectMapper.writeValueAsString(emptyCounts));
            } catch (Exception ignored) {}
            return batchRepository.save(batch);
        }

        Map<String, Object> plannedCounts = new HashMap<>();
        plannedCounts.put("reels", plannedReels);
        plannedCounts.put("posts", plannedPosts);
        plannedCounts.put("carousels", plannedCarousels);
        plannedCounts.put("totalItems", plannedReels + plannedPosts + plannedCarousels);
        plannedCounts.put("relyOnReelPlanner", true);

        try {
            batch.setPlannedCountsJson(objectMapper.writeValueAsString(plannedCounts));
        } catch (Exception ignored) {}

        batch.setStatus("COMPLETED");
        return batchRepository.save(batch);
    }
}
