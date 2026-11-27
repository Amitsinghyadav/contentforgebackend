package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The plan a brand is on. The counts are copied from the plan when it is assigned, so a Custom brand can have its own
 * numbers. Months run from {@code startDate} (e.g. 5th to 4th).
 */
@Entity
@Table(name = "cf_brand_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_name", nullable = false)
    private String planName;

    @Column(name = "tier")
    private String tier;

    @Column(name = "price_inr")
    private Double priceInr;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** ACTIVE or ENDED */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "avatar_reels_quota", nullable = false)
    private Integer avatarReelsQuota;

    @Column(name = "explainer_quota", nullable = false)
    private Integer explainerQuota;

    @Column(name = "carousel_quota", nullable = false)
    private Integer carouselQuota;

    @Column(name = "image_posts_quota", nullable = false)
    private Integer imagePostsQuota;

    @Column(name = "story_frames_quota", nullable = false)
    private Integer storyFramesQuota;

    @Column(name = "min_face_videos")
    private Integer minFaceVideos;

    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = "ACTIVE";
        if (startDate == null) startDate = LocalDate.now();
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
