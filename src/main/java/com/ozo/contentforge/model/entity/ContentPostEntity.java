package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.model.enums.AvatarEngine;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostMode;
import com.ozo.contentforge.model.enums.PostStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_posts", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"idempotency_key"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentPostEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "batch_id")
    private Long batchId;

    @Column(name = "event_id")
    private Long eventId; // nullable, set if created by occasion calendar

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType; // REEL, POST, CAROUSEL

    @Column(name = "topic", nullable = false, columnDefinition = "TEXT")
    private String topic;

    @Column(name = "hook", columnDefinition = "TEXT")
    private String hook;

    @Column(name = "presenter_script", columnDefinition = "TEXT")
    private String presenterScript;

    @Column(name = "voiceover_script", columnDefinition = "TEXT")
    private String voiceoverScript;

    @Column(name = "script_json", columnDefinition = "TEXT")
    private String scriptJson; // Full structured storyboard JSON

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "hashtags", columnDefinition = "TEXT")
    private String hashtags;

    @Column(name = "cta", columnDefinition = "TEXT")
    private String cta;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode")
    private PostMode mode; // MODE_A, MODE_B

    @Enumerated(EnumType.STRING)
    @Column(name = "avatar_engine")
    private AvatarEngine avatarEngine; // AVATAR_III, AVATAR_IV

    @Column(name = "presenter_id")
    private Long presenterId;

    @Column(name = "voice_id")
    private Long voiceId;

    @Column(name = "target_duration_seconds")
    private Integer targetDurationSeconds; // e.g. 45

    @Column(name = "actual_duration_seconds")
    private Double actualDurationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PostStatus status;

    @Column(name = "clinical_review_required")
    private Boolean clinicalReviewRequired;

    @Column(name = "clinical_review_approved")
    private Boolean clinicalReviewApproved;

    @Column(name = "clinical_notes", columnDefinition = "TEXT")
    private String clinicalNotes;

    @Column(name = "voice_audio_url")
    private String voiceAudioUrl;

    @Column(name = "avatar_video_url")
    private String avatarVideoUrl;

    @Column(name = "final_render_url")
    private String finalRenderUrl;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "instagram_container_id")
    private String instagramContainerId;

    @Column(name = "instagram_media_id")
    private String instagramMediaId;

    @Column(name = "published_permalink")
    private String publishedPermalink;

    @Column(name = "facebook_post_id")
    private String facebookPostId;

    @Column(name = "facebook_permalink")
    private String facebookPermalink;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "estimated_cost_usd")
    private Double estimatedCostUsd;

    @Column(name = "actual_cost_usd")
    private Double actualCostUsd;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = PostStatus.QUEUED;
        if (this.clinicalReviewRequired == null) this.clinicalReviewRequired = false;
        if (this.clinicalReviewApproved == null) this.clinicalReviewApproved = false;
        if (this.targetDurationSeconds == null) this.targetDurationSeconds = 45;
        if (this.estimatedCostUsd == null) this.estimatedCostUsd = 0.0;
        if (this.actualCostUsd == null) this.actualCostUsd = 0.0;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
