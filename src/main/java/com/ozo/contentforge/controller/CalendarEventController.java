package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.enums.AvatarEngine;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.model.enums.RecurrenceRule;
import com.ozo.contentforge.service.ContentPostService;
import com.ozo.contentforge.service.MasterSettingsService;
import com.ozo.contentforge.service.OccasionCalendarService;
import com.ozo.contentforge.service.PlanService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/calendar-events")
@CrossOrigin
public class CalendarEventController {

    private final OccasionCalendarService calendarService;
    private final ContentPostService postService;
    private final PlanService planService;
    private final MasterSettingsService settingsService;

    public CalendarEventController(OccasionCalendarService calendarService,
                                   ContentPostService postService,
                                   PlanService planService,
                                   MasterSettingsService settingsService) {
        this.calendarService = calendarService;
        this.postService = postService;
        this.planService = planService;
        this.settingsService = settingsService;
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
    public ResponseEntity<ContentCalendarEventEntity> createEvent(@RequestBody Map<String, Object> req) {
        String title = (String) req.get("title");
        if (title == null || title.isBlank()) {
            Map<String, Object> allSettings = settingsService.getAllSettings();
            String industry = (String) allSettings.getOrDefault("industry", "Healthcare");
            String defaultTagline = (String) allSettings.getOrDefault("tagline", "Daily Preventive Health");
            title = defaultTagline + " (" + industry + ")";
        }
        LocalDate eventDate = LocalDate.parse((String) req.get("eventDate"));
        RecurrenceRule recurrence = req.containsKey("recurrenceRule") ? RecurrenceRule.valueOf((String) req.get("recurrenceRule")) : RecurrenceRule.NONE;
        String themeBrief = (String) req.get("themeBrief");
        Integer extraCount = req.containsKey("extraContentCount") ? ((Number) req.get("extraContentCount")).intValue() : 1;
        ContentType contentType = req.containsKey("contentType") ? ContentType.valueOf((String) req.get("contentType")) : ContentType.REEL;
        Integer slideCount = req.containsKey("carouselSlideCount") ? ((Number) req.get("carouselSlideCount")).intValue() : 6;
        String language = (String) req.getOrDefault("language", "Hinglish");
        PostMode mode = req.containsKey("productionMode") ? PostMode.valueOf((String) req.get("productionMode")) : PostMode.MODE_A;
        String publishTime = (String) req.getOrDefault("publishTime", "11:00 AM");
        Boolean replaceQuota = Boolean.TRUE.equals(req.get("replaceQuota"));

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
    public ResponseEntity<Void> deleteEvent(@PathVariable("id") Long id) {
        Optional<ContentCalendarEventEntity> eventOpt = calendarService.getEventById(id);
        if (eventOpt.isPresent()) {
            ContentCalendarEventEntity event = eventOpt.get();
            String cType = event.getContentType() != null ? event.getContentType().name() : "REEL";
            planService.releaseQuota(null, cType);
            calendarService.deleteEvent(id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/generate")
    public ResponseEntity<ContentPostEntity> generateEventContent(@PathVariable("id") Long id) {
        ContentCalendarEventEntity event = calendarService.getEventById(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));

        String topic = event.getTitle() + " - " + (event.getThemeBrief() != null ? event.getThemeBrief() : "Festival Special");
        ContentPostEntity post = postService.createAndPlanPost(
                event.getEventDate(), null, event.getId(),
                event.getContentType(), topic,
                event.getProductionMode() != null ? event.getProductionMode() : PostMode.MODE_A,
                AvatarEngine.AVATAR_IV, 45
        );
        return ResponseEntity.ok(post);
    }
}
