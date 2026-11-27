package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A package a brand buys: how much content it gets per month. Standard and Premium are fixed; a Custom brand starts
 * from a plan and gets its own counts on its subscription.
 */
@Entity
@Table(name = "cf_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    /** STANDARD, PREMIUM or CUSTOM */
    @Column(name = "tier")
    private String tier;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "billing_cycle")
    private String billingCycle;

    @Column(name = "price_inr")
    private Double priceInr;

    // ---- Monthly content counts ----

    @Column(name = "reels_quota")
    private Integer avatarReelsQuota;

    @Column(name = "explainer_quota")
    private Integer explainerQuota;

    @Column(name = "carousel_quota")
    private Integer carouselQuota;

    @Column(name = "image_posts_quota")
    private Integer imagePostsQuota;

    /** Story frames per month (one story is usually 2-4 frames). */
    @Column(name = "story_frames_quota")
    private Integer storyFramesQuota;

    /** Of all videos, at least this many show the person's face. */
    @Column(name = "min_face_videos")
    private Integer minFaceVideos;

    /** Included services shown on the plan, as a JSON array of strings. */
    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (active == null) active = true;
        if (billingCycle == null) billingCycle = "MONTHLY";
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
