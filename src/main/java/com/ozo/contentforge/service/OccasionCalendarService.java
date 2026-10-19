package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.model.enums.RecurrenceRule;
import com.ozo.contentforge.repository.ContentCalendarEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class OccasionCalendarService {

    private final ContentCalendarEventRepository eventRepository;

    public OccasionCalendarService(ContentCalendarEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    public List<ContentCalendarEventEntity> getAllEvents() {
        return eventRepository.findAll();
    }

    public List<ContentCalendarEventEntity> getEventsForRange(LocalDate start, LocalDate end) {
        return eventRepository.findByEventDateBetweenOrderByEventDateAsc(start, end);
    }

    public List<ContentCalendarEventEntity> getEventsForDate(LocalDate date) {
        if (date == null) return Collections.emptyList();
        return eventRepository.findActiveEventsForDate(date, date.getMonthValue(), date.getDayOfMonth());
    }

    public Optional<ContentCalendarEventEntity> getEventById(Long id) {
        return eventRepository.findById(id);
    }

    @Transactional
    public ContentCalendarEventEntity createEvent(String title, LocalDate eventDate, RecurrenceRule recurrenceRule,
                                                  String themeBrief, Integer extraContentCount, ContentType contentType,
                                                  Integer carouselSlideCount, String language, PostMode productionMode,
                                                  String publishTime, Boolean replaceQuota) {
        ContentCalendarEventEntity event = ContentCalendarEventEntity.builder()
                .title(title)
                .eventDate(eventDate)
                .recurrenceRule(recurrenceRule != null ? recurrenceRule : RecurrenceRule.NONE)
                .themeBrief(themeBrief)
                .extraContentCount(extraContentCount != null ? extraContentCount : 1)
                .contentType(contentType != null ? contentType : ContentType.REEL)
                .carouselSlideCount(carouselSlideCount != null ? carouselSlideCount : 6)
                .language(language != null ? language : "Hinglish")
                .productionMode(productionMode != null ? productionMode : PostMode.MODE_A)
                .publishTime(publishTime != null ? publishTime : "11:00 AM")
                .replaceQuota(Boolean.TRUE.equals(replaceQuota))
                .status("ACTIVE")
                .build();

        return eventRepository.save(event);
    }

    @Transactional
    public ContentCalendarEventEntity updateEvent(Long id, String title, LocalDate eventDate, RecurrenceRule recurrenceRule,
                                                  String themeBrief, Integer extraContentCount, ContentType contentType,
                                                  Integer carouselSlideCount, String language, PostMode productionMode,
                                                  String publishTime, Boolean replaceQuota, String status) {
        ContentCalendarEventEntity event = eventRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Calendar event not found: " + id));

        if (title != null) event.setTitle(title);
        if (eventDate != null) event.setEventDate(eventDate);
        if (recurrenceRule != null) event.setRecurrenceRule(recurrenceRule);
        if (themeBrief != null) event.setThemeBrief(themeBrief);
        if (extraContentCount != null) event.setExtraContentCount(extraContentCount);
        if (contentType != null) event.setContentType(contentType);
        if (carouselSlideCount != null) event.setCarouselSlideCount(carouselSlideCount);
        if (language != null) event.setLanguage(language);
        if (productionMode != null) event.setProductionMode(productionMode);
        if (publishTime != null) event.setPublishTime(publishTime);
        if (replaceQuota != null) event.setReplaceQuota(replaceQuota);
        if (status != null) event.setStatus(status);

        return eventRepository.save(event);
    }

    @Transactional
    public void deleteEvent(Long id) {
        eventRepository.deleteById(id);
    }
}
