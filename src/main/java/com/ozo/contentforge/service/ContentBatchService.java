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

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.tenancy.BrandContext;
import org.springframework.beans.factory.ObjectProvider;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final BrandRepository brandRegistry;
    /** Calls through the Spring proxy so @Transactional applies per brand. */
    private final ObjectProvider<ContentBatchService> selfProvider;

    public ContentBatchService(DailyGenerationBatchRepository batchRepository,
                               MasterSettingsService settingsService,
                               OccasionCalendarService calendarService,
                               ContentPostService postService,
                               AiProviderService aiProviderService,
                               BrandProfileRepository brandRepository,
                               PlanService planService,
                               ObjectMapper objectMapper,
                               BrandRepository brandRegistry,
                               ObjectProvider<ContentBatchService> selfProvider) {
        this.brandRegistry = brandRegistry;
        this.selfProvider = selfProvider;
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
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        log.info("Daily batch: planning today's content for every active brand...");
        for (BrandEntity brand : brandRegistry.findByActiveTrueOrderByNameAsc()) {
            try {
                BrandContext.runAs(brand.getId(), () -> selfProvider.getObject().planAndCreateBatch(today, false));
            } catch (Exception e) {
                log.error("Daily batch failed for brand '{}': {}", brand.getName(), e.getMessage(), e);
            }
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

        // Check Reel Planner Calendar events scheduled for this target date
        List<ContentCalendarEventEntity> plannedEvents = calendarService.getEventsForDate(date);
        List<ContentCalendarEventEntity> pendingEvents = plannedEvents.stream()
                .filter(ev -> !"GENERATED".equalsIgnoreCase(ev.getStatus()))
                .toList();

        // If batch exists, no pending events remain, and user didn't request a manual format:
        if (existing.isPresent() && !existingPosts.isEmpty() && pendingEvents.isEmpty() && requestedType == null && !force) {
            log.info("Batch already exists for date {} with {} posts and no pending events.", date, existingPosts.size());
            return existing.get();
        }

        Map<String, Object> settings = settingsService.getAllSettings();
        String defaultModeStr = (String) settings.getOrDefault("productionMode", "Mode A");
        PostMode defaultMode = defaultModeStr.contains("B") ? PostMode.MODE_B : PostMode.MODE_A;

        String defaultEngineStr = (String) settings.getOrDefault("avatarEngine", "Avatar IV");
        AvatarEngine defaultEngine = defaultEngineStr.contains("III") ? AvatarEngine.AVATAR_III : AvatarEngine.AVATAR_IV;

        int durationTarget = ((Number) settings.getOrDefault("reelDurationTarget", 45)).intValue();

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

        int totalNeeded = Math.max(pendingEvents.size(), 1);
        List<String> existingTitles = plannedEvents.stream()
                .map(ContentCalendarEventEntity::getTitle)
                .filter(t -> t != null && !t.isBlank() && !isGenericPlaceholderTopic(t, settings, industry))
                .toList();
        List<String> dailyTopics = aiProviderService.generateDailyTopics(industry, targetAudience, brandTone, Math.max(totalNeeded, 5), date, existingTitles);

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

        if (!pendingEvents.isEmpty()) {
            log.info("Generating batch for date {} for {} pending items in Reel Planner (skipping completed)...", date, pendingEvents.size());
            for (ContentCalendarEventEntity ev : pendingEvents) {
                ContentType cType = ev.getContentType() != null ? ev.getContentType() : ContentType.REEL;
                String topic = !isGenericPlaceholderTopic(ev.getTitle(), settings, industry)
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

                ev.setTitle(topic);
                ev.setStatus("GENERATED");
                calendarService.updateEvent(ev.getId(), topic, ev.getEventDate(), ev.getRecurrenceRule(),
                        ev.getThemeBrief(), ev.getExtraContentCount(), ev.getContentType(), ev.getCarouselSlideCount(),
                        ev.getLanguage(), ev.getProductionMode(), ev.getPublishTime(), ev.getReplaceQuota(), "GENERATED");
            }
        } else if (requestedType != null) {
            planService.checkCapacity(requestedType, date, 1);

            // User specified what format they want to generate for this date (no pending tasks left)
            String chosenTopic;
            if (requestedTopic != null && !requestedTopic.trim().isEmpty()) {
                chosenTopic = requestedTopic.trim();
            } else {
                chosenTopic = (dailyTopics != null && !dailyTopics.isEmpty())
                        ? dailyTopics.get(0)
                        : "Essential Guidance on " + industry;
            }

            log.info("No pending items for {}. User requested to generate {} on topic: {}", date, requestedType, chosenTopic);

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
            // No pending events scheduled on Reel Planner, and no content type specified by the user.
            log.info("No pending items scheduled in Reel Planner for date {} and no content type specified. Nothing generated.", date);
            batch.setStatus("NO_SCHEDULED_CONTENT");
            Map<String, Object> emptyCounts = new HashMap<>();
            emptyCounts.put("reels", 0);
            emptyCounts.put("posts", 0);
            emptyCounts.put("carousels", 0);
            emptyCounts.put("totalItems", 0);
            emptyCounts.put("message", "All tasks for this date are already completed or no items scheduled.");
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

    private boolean isGenericPlaceholderTopic(String title, Map<String, Object> settings, String industry) {
        if (title == null || title.isBlank()) return true;
        String lower = title.trim().toLowerCase();
        if (lower.contains("daily preventive health")) return true;
        String tagline = String.valueOf(settings.getOrDefault("tagline", "")).trim().toLowerCase();
        String indLower = industry != null ? industry.trim().toLowerCase() : "";
        return !tagline.isEmpty() && lower.startsWith(tagline) && (indLower.isEmpty() || lower.contains(indLower));
    }
}
