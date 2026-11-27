package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.enums.AvatarEngine;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.model.enums.RecurrenceRule;
import com.ozo.contentforge.repository.BrandProfileRepository;
import com.ozo.contentforge.service.AiProviderService;
import com.ozo.contentforge.service.ContentPostService;
import com.ozo.contentforge.service.MasterSettingsService;
import com.ozo.contentforge.service.OccasionCalendarService;
import com.ozo.contentforge.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/calendar-events")
@CrossOrigin
public class CalendarEventController {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventController.class);

    private final OccasionCalendarService calendarService;
    private final ContentPostService postService;
    private final PlanService planService;
    private final MasterSettingsService settingsService;
    private final AiProviderService aiProviderService;
    private final BrandProfileRepository brandRepository;

    public CalendarEventController(OccasionCalendarService calendarService,
                                   ContentPostService postService,
                                   PlanService planService,
                                   MasterSettingsService settingsService,
                                   AiProviderService aiProviderService,
                                   BrandProfileRepository brandRepository) {
        this.calendarService = calendarService;
        this.postService = postService;
        this.planService = planService;
        this.settingsService = settingsService;
        this.aiProviderService = aiProviderService;
        this.brandRepository = brandRepository;
    }

    @GetMapping
    public ResponseEntity<List<ContentCalendarEventEntity>> getAllEvents() {
        return ResponseEntity.ok(calendarService.getAllEvents());
    }

    @GetMapping("/range")
    public ResponseEntity<List<ContentCalendarEventEntity>> getEventsForRange(
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(calendarService.getEventsForRange(start, end));
    }

    @PostMapping
    public ResponseEntity<?> createEvent(@RequestBody Map<String, Object> req) {
        LocalDate eventDate = LocalDate.parse((String) req.get("eventDate"));
        RecurrenceRule recurrence = req.containsKey("recurrenceRule") ? RecurrenceRule.valueOf((String) req.get("recurrenceRule")) : RecurrenceRule.NONE;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (recurrence == RecurrenceRule.NONE && eventDate.isBefore(today)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot schedule content for a past date"
            ));
        }

        String themeBrief = (String) req.get("themeBrief");
        Integer extraCount = req.containsKey("extraContentCount") ? ((Number) req.get("extraContentCount")).intValue() : 1;
        ContentType contentType = req.containsKey("contentType") ? ContentType.valueOf((String) req.get("contentType")) : ContentType.REEL;
        Integer slideCount = req.containsKey("carouselSlideCount") ? ((Number) req.get("carouselSlideCount")).intValue() : 6;
        String language = (String) req.getOrDefault("language", "Hinglish");
        PostMode mode = req.containsKey("productionMode") ? PostMode.valueOf((String) req.get("productionMode")) : PostMode.MODE_A;
        String publishTime = (String) req.getOrDefault("publishTime", "11:00 AM");
        Boolean replaceQuota = Boolean.TRUE.equals(req.get("replaceQuota"));

        String title = (String) req.get("title");
        if (isGenericOrBlankTopic(title)) {
            title = generateUniqueAiTopic(eventDate, themeBrief, contentType);
        }

        boolean quotaConsumed = planService.consumeQuota(null, contentType.name());
        if (!quotaConsumed) {
            throw new IllegalStateException("All allowed limit already planned. Please delete from Reel Planner.");
        }

        return ResponseEntity.ok(calendarService.createEvent(title, eventDate, recurrence, themeBrief, extraCount,
                contentType, slideCount, language, mode, publishTime, replaceQuota));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ContentCalendarEventEntity> updateEvent(@PathVariable("id") Long id,
                                                                  @RequestBody Map<String, Object> req) {
        String title = (String) req.get("title");
        LocalDate eventDate = req.containsKey("eventDate") ? LocalDate.parse((String) req.get("eventDate")) : null;
        RecurrenceRule recurrence = req.containsKey("recurrenceRule") ? RecurrenceRule.valueOf((String) req.get("recurrenceRule")) : null;
        String themeBrief = (String) req.get("themeBrief");
        Integer extraCount = req.containsKey("extraContentCount") ? ((Number) req.get("extraContentCount")).intValue() : null;
        ContentType contentType = req.containsKey("contentType") ? ContentType.valueOf((String) req.get("contentType")) : null;
        Integer slideCount = req.containsKey("carouselSlideCount") ? ((Number) req.get("carouselSlideCount")).intValue() : null;
        String language = (String) req.get("language");
        PostMode mode = req.containsKey("productionMode") ? PostMode.valueOf((String) req.get("productionMode")) : null;
        String publishTime = (String) req.get("publishTime");
        Boolean replaceQuota = req.containsKey("replaceQuota") ? Boolean.TRUE.equals(req.get("replaceQuota")) : null;
        String status = (String) req.get("status");

        return ResponseEntity.ok(calendarService.updateEvent(id, title, eventDate, recurrence, themeBrief, extraCount,
                contentType, slideCount, language, mode, publishTime, replaceQuota, status));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteEvent(@PathVariable("id") Long id) {
        Optional<ContentCalendarEventEntity> eventOpt = calendarService.getEventById(id);
        if (eventOpt.isPresent()) {
            ContentCalendarEventEntity event = eventOpt.get();
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            if (event.getEventDate() != null && event.getEventDate().isBefore(today)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Cannot delete past scheduled events because slots cannot be freed for past dates"
                ));
            }
            String cType = event.getContentType() != null ? event.getContentType().name() : "REEL";
            planService.releaseQuota(null, cType);
            calendarService.deleteEvent(id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/post")
    public ResponseEntity<Map<String, Object>> getPostForEvent(@PathVariable("id") Long id) {
        ContentCalendarEventEntity event = calendarService.getEventById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));

        Optional<ContentPostEntity> postOpt = postService.getPostByEventId(id);
        if (postOpt.isEmpty() && event.getEventDate() != null) {
            List<ContentPostEntity> datePosts = postService.getPostsForDate(event.getEventDate());
            postOpt = datePosts.stream()
                    .filter(p -> p.getContentType() == event.getContentType())
                    .findFirst();
            if (postOpt.isEmpty() && !datePosts.isEmpty()) {
                postOpt = Optional.of(datePosts.get(0));
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("event", event);
        if (postOpt.isPresent()) {
            ContentPostEntity post = postOpt.get();
            res.put("hasPost", true);
            res.put("post", post);
            res.put("scenes", postService.getScenesForPost(post.getId()));
            res.put("slides", postService.getSlidesForPost(post.getId()));
            res.put("mediaJobs", postService.getMediaJobsForPost(post.getId()));
            res.put("approvals", postService.getApprovalsForPost(post.getId()));
        } else {
            res.put("hasPost", false);
        }
        return ResponseEntity.ok(res);
    }

    @PostMapping("/{id}/generate")
    public ResponseEntity<ContentPostEntity> generateEventContent(@PathVariable("id") Long id) {
        ContentCalendarEventEntity event = calendarService.getEventById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));

        String baseTitle = event.getTitle();
        if (isGenericOrBlankTopic(baseTitle)) {
            baseTitle = generateUniqueAiTopic(
                    event.getEventDate() != null ? event.getEventDate() : LocalDate.now(ZoneId.of("Asia/Kolkata")),
                    event.getThemeBrief(),
                    event.getContentType()
            );
            event.setTitle(baseTitle);
            calendarService.updateEvent(event.getId(), baseTitle, event.getEventDate(), event.getRecurrenceRule(),
                    event.getThemeBrief(), event.getExtraContentCount(), event.getContentType(), event.getCarouselSlideCount(),
                    event.getLanguage(), event.getProductionMode(), event.getPublishTime(), event.getReplaceQuota(), event.getStatus());
        }

        String topic = (event.getThemeBrief() != null && !event.getThemeBrief().isBlank())
                ? baseTitle + " (" + event.getThemeBrief() + ")"
                : baseTitle;
        ContentPostEntity post = postService.createAndPlanPost(
                event.getEventDate(), null, event.getId(),
                event.getContentType(), topic,
                event.getProductionMode() != null ? event.getProductionMode() : PostMode.MODE_A,
                AvatarEngine.AVATAR_IV, 45
        );
        return ResponseEntity.ok(post);
    }

    private boolean isGenericOrBlankTopic(String title) {
        if (title == null || title.isBlank()) return true;
        String lower = title.trim().toLowerCase();
        if (lower.contains("daily preventive health")) return true;
        Map<String, Object> allSettings = settingsService.getAllSettings();
        String industry = String.valueOf(allSettings.getOrDefault("industry", "")).trim().toLowerCase();
        String tagline = String.valueOf(allSettings.getOrDefault("tagline", "")).trim().toLowerCase();
        return !tagline.isEmpty() && lower.startsWith(tagline) && (industry.isEmpty() || lower.contains(industry));
    }

    private String generateUniqueAiTopic(LocalDate eventDate, String themeBrief, ContentType contentType) {
        Map<String, Object> allSettings = settingsService.getAllSettings();
        BrandProfileEntity brand = brandRepository.findByIsDefaultTrue().orElse(null);
        String industry = (brand != null && brand.getIndustry() != null && !brand.getIndustry().isBlank())
                ? brand.getIndustry()
                : String.valueOf(allSettings.getOrDefault("industry", "Preventive Healthcare & Diagnostics"));
        String brandTone = (brand != null && brand.getBrandTone() != null && !brand.getBrandTone().isBlank())
                ? brand.getBrandTone()
                : String.valueOf(allSettings.getOrDefault("brandTone", "Engaging, authoritative, relatable, and authentic"));
        String targetAudience = (brand != null && brand.getTargetAudience() != null && !brand.getTargetAudience().isBlank())
                ? brand.getTargetAudience()
                : String.valueOf(allSettings.getOrDefault("targetAudience", "Health-conscious adults and families"));

        String contextualIndustry = industry;
        if (themeBrief != null && !themeBrief.isBlank()) {
            contextualIndustry = industry + " — Focus/Theme: " + themeBrief.trim();
        }
        if (contentType != null) {
            contextualIndustry = contextualIndustry + " (Format: " + contentType.name() + ")";
        }

        List<String> existingTitles = calendarService.getAllEvents().stream()
                .map(ContentCalendarEventEntity::getTitle)
                .filter(t -> t != null && !t.isBlank() && !t.toLowerCase().contains("daily preventive health"))
                .distinct()
                .limit(15)
                .toList();

        try {
            List<String> generated = aiProviderService.generateDailyTopics(contextualIndustry, targetAudience, brandTone, 3, eventDate, existingTitles);
            for (String candidate : generated) {
                if (existingTitles.stream().noneMatch(ex -> ex.equalsIgnoreCase(candidate))) {
                    return candidate;
                }
            }
            if (!generated.isEmpty()) {
                return generated.get(0);
            }
        } catch (Exception e) {
            log.error("Auto-topic AI generation failed for {}: {}", eventDate, e.getMessage(), e);
        }

        String defaultTagline = String.valueOf(allSettings.getOrDefault("tagline", "Daily Preventive Health"));
        return defaultTagline + " (" + industry + ")";
    }
}
