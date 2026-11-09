package com.ozo.contentforge.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.*;
import com.ozo.contentforge.model.enums.*;
import com.ozo.contentforge.repository.GenerationUsageRepository;
import com.ozo.contentforge.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/dashboard")
@CrossOrigin
public class DashboardController {

    private final ContentPostService postService;
    private final ContentBatchService batchService;
    private final OccasionCalendarService calendarService;
    private final MasterSettingsService settingsService;
    private final GenerationUsageRepository usageRepository;
    private final PlanService planService;
    private final ObjectMapper objectMapper;

    public DashboardController(ContentPostService postService,
                               ContentBatchService batchService,
                               OccasionCalendarService calendarService,
                               MasterSettingsService settingsService,
                               GenerationUsageRepository usageRepository,
                               PlanService planService,
                               ObjectMapper objectMapper) {
        this.postService = postService;
        this.batchService = batchService;
        this.calendarService = calendarService;
        this.settingsService = settingsService;
        this.usageRepository = usageRepository;
        this.planService = planService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/daily")
    public ResponseEntity<Map<String, Object>> getDailyDashboard(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = (date != null) ? date : LocalDate.now();

        // 1. Fetch batch if exists (do NOT automatically generate fake batches)
        Optional<DailyGenerationBatchEntity> batchOpt = batchService.getBatchForDate(targetDate);
        Long batchId = batchOpt.map(DailyGenerationBatchEntity::getId).orElse(null);
        String batchStatus = batchOpt.map(DailyGenerationBatchEntity::getStatus).orElse("NO_BATCH");

        // 2. Fetch posts from database
        List<ContentPostEntity> posts = postService.getPostsForDate(targetDate);
        List<ContentCalendarEventEntity> calendarEvents = calendarService.getEventsForDate(targetDate);

        // Daily quotas are driven by the Reel Planner calendar
        int quotaReels = (int) calendarEvents.stream().filter(e -> e.getContentType() == ContentType.REEL).count();
        int quotaPosts = (int) calendarEvents.stream().filter(e -> e.getContentType() == ContentType.POST).count();
        int quotaCarousels = (int) calendarEvents.stream().filter(e -> e.getContentType() == ContentType.CAROUSEL).count();
        Map<String, Object> masterSettings = settingsService.getAllSettings();

        // Grouping & counters from actual posts in DB
        int generatedReels = 0, approvedReels = 0, scheduledReels = 0, publishedReels = 0;
        int generatedPosts = 0, approvedPosts = 0, scheduledPosts = 0, publishedPosts = 0;
        int generatedCarousels = 0, approvedCarousels = 0, scheduledCarousels = 0, publishedCarousels = 0;
        double totalDaySpend = 0.0;

        List<Map<String, Object>> contentCards = new ArrayList<>();

        for (ContentPostEntity p : posts) {
            totalDaySpend += (p.getActualCostUsd() != null ? p.getActualCostUsd() : 0.0);

            boolean isApproved = p.getStatus() == PostStatus.FINAL_APPROVED || p.getStatus() == PostStatus.SCHEDULED || p.getStatus() == PostStatus.PUBLISHING || p.getStatus() == PostStatus.PUBLISHED;
            boolean isScheduled = p.getStatus() == PostStatus.SCHEDULED || p.getStatus() == PostStatus.PUBLISHING || p.getStatus() == PostStatus.PUBLISHED;
            boolean isPublished = p.getStatus() == PostStatus.PUBLISHED;

            if (p.getContentType() == ContentType.REEL) {
                generatedReels++;
                if (isApproved) approvedReels++;
                if (isScheduled) scheduledReels++;
                if (isPublished) publishedReels++;
            } else if (p.getContentType() == ContentType.POST) {
                generatedPosts++;
                if (isApproved) approvedPosts++;
                if (isScheduled) scheduledPosts++;
                if (isPublished) publishedPosts++;
            } else if (p.getContentType() == ContentType.CAROUSEL) {
                generatedCarousels++;
                if (isApproved) approvedCarousels++;
                if (isScheduled) scheduledCarousels++;
                if (isPublished) publishedCarousels++;
            }

            Map<String, Object> card = new HashMap<>();
            card.put("id", p.getId());
            card.put("topic", p.getTopic());
            card.put("contentType", p.getContentType().name());
            card.put("status", p.getStatus().name());
            card.put("mode", p.getMode() != null ? p.getMode().name() : "MODE_A");
            card.put("avatarEngine", p.getAvatarEngine() != null ? p.getAvatarEngine().name() : "AVATAR_IV");
            card.put("targetDurationSeconds", p.getTargetDurationSeconds());
            card.put("hook", p.getHook());
            card.put("presenterScript", p.getPresenterScript());
            card.put("voiceoverScript", p.getVoiceoverScript());
            card.put("scriptJson", p.getScriptJson());
            card.put("caption", p.getCaption());
            card.put("cta", p.getCta());
            card.put("hashtags", p.getHashtags());
            card.put("thumbnailUrl", p.getThumbnailUrl());
            card.put("finalRenderUrl", p.getFinalRenderUrl());
            card.put("voiceAudioUrl", p.getVoiceAudioUrl());
            card.put("scheduledAt", p.getScheduledAt());
            card.put("publishedAt", p.getPublishedAt());
            card.put("permalink", p.getPublishedPermalink());
            card.put("estimatedCostUsd", p.getEstimatedCostUsd());
            card.put("actualCostUsd", p.getActualCostUsd());
            card.put("clinicalReviewRequired", p.getClinicalReviewRequired());
            card.put("clinicalReviewApproved", p.getClinicalReviewApproved());
            card.put("eventId", p.getEventId());

            // Add scenes if Reel
            if (p.getContentType() == ContentType.REEL) {
                card.put("scenes", postService.getScenesForPost(p.getId()));
            } else if (p.getContentType() == ContentType.CAROUSEL) {
                card.put("slides", postService.getSlidesForPost(p.getId()));
            }

            card.put("mediaJobs", postService.getMediaJobsForPost(p.getId()));
            card.put("approvals", postService.getApprovalsForPost(p.getId()));

            contentCards.add(card);
        }

        // Daily Summary
        Map<String, Object> summary = new HashMap<>();
        summary.put("reels", Map.of(
                "quota", quotaReels, "generated", generatedReels,
                "approved", approvedReels, "scheduled", scheduledReels, "published", publishedReels
        ));
        summary.put("posts", Map.of(
                "quota", quotaPosts, "generated", generatedPosts,
                "approved", approvedPosts, "scheduled", scheduledPosts, "published", publishedPosts
        ));
        summary.put("carousels", Map.of(
                "quota", quotaCarousels, "generated", generatedCarousels,
                "approved", approvedCarousels, "scheduled", scheduledCarousels, "published", publishedCarousels
        ));
        Optional<UserSubscriptionEntity> activeSub = planService.getActiveSubscriptionForUser(null);

        Map<String, Object> response = new HashMap<>();
        response.put("date", targetDate);
        response.put("batchId", batchId);
        response.put("batchStatus", batchStatus);
        response.put("summary", summary);
        response.put("subscription", activeSub.orElse(null));
        response.put("posts", contentCards);
        response.put("calendarEvents", calendarEvents);
        response.put("masterSettings", masterSettings);

        return ResponseEntity.ok(response);
    }
}
