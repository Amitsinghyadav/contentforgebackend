package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.model.enums.RecurrenceRule;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_calendar_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentCalendarEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false)
    private String title; // e.g. "Diwali Special", "World Diabetes Day"

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_rule")
    private RecurrenceRule recurrenceRule; // NONE, ANNUAL, MONTHLY, WEEKLY

    @Column(name = "theme_brief", columnDefinition = "TEXT")
    private String themeBrief; // e.g. "Healthy celebrations, mindful eating, family preventive health"

    @Column(name = "extra_content_count")
    private Integer extraContentCount; // default 1

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type")
    private ContentType contentType; // REEL, POST, CAROUSEL

    @Column(name = "carousel_slide_count")
    private Integer carouselSlideCount; // default 6

    @Column(name = "language")
    private String language; // "Hinglish", "Hindi", "English"

    @Enumerated(EnumType.STRING)
    @Column(name = "production_mode")
    private PostMode productionMode; // MODE_A, MODE_B

    @Column(name = "publish_time")
    private String publishTime; // e.g. "11:00 AM"

    @Column(name = "replace_quota")
    private Boolean replaceQuota; // if true, replaces normal quota; default false (additive)

    @Column(name = "status")
    private String status; // DRAFT, ACTIVE, GENERATED, CANCELLED

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.extraContentCount == null) this.extraContentCount = 1;
        if (this.carouselSlideCount == null) this.carouselSlideCount = 6;
        if (this.replaceQuota == null) this.replaceQuota = false;
        if (this.status == null) this.status = "ACTIVE";
        if (this.recurrenceRule == null) this.recurrenceRule = RecurrenceRule.NONE;
    }
}
