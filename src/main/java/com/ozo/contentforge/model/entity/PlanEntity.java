package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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
    private String name; // e.g. "Basic Plan", "Standard Plan", "Premium Plan", "Corporate Plan"

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "billing_cycle", nullable = false)
    private String billingCycle; // "MONTHLY" or "YEARLY"

    @Column(name = "price_inr")
    private Double priceInr;

    @Column(name = "reels_quota", nullable = false)
    private Integer reelsQuota; // e.g. 4

    @Column(name = "event_reels_quota", nullable = false)
    private Integer eventReelsQuota; // e.g. 2

    @Column(name = "event_posts_quota", nullable = false)
    private Integer eventPostsQuota; // e.g. 4

    @Column(name = "carousel_quota", nullable = false)
    private Integer carouselQuota; // e.g. 5

    @Column(name = "image_posts_quota", nullable = false)
    private Integer imagePostsQuota; // e.g. 10

    @Column(name = "premium_support")
    private Boolean premiumSupport;

    @Column(name = "features_json", columnDefinition = "TEXT")
    private String featuresJson;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.active == null) this.active = true;
        if (this.reelsQuota == null) this.reelsQuota = 4;
        if (this.eventReelsQuota == null) this.eventReelsQuota = 2;
        if (this.eventPostsQuota == null) this.eventPostsQuota = 4;
        if (this.carouselQuota == null) this.carouselQuota = 5;
        if (this.imagePostsQuota == null) this.imagePostsQuota = 10;
        if (this.premiumSupport == null) this.premiumSupport = false;
        if (this.billingCycle == null) this.billingCycle = "MONTHLY";
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

