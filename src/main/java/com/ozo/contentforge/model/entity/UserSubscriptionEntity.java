package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cf_user_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_name", nullable = false)
    private String planName;

    @Column(name = "billing_cycle")
    private String billingCycle; // "MONTHLY", "YEARLY"

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "status", nullable = false)
    private String status; // "ACTIVE", "EXPIRED", "CANCELLED"

    // Quotas (Copied from Plan on activation, customizable per organization)
    @Column(name = "reels_quota", nullable = false)
    private Integer reelsQuota;

    @Column(name = "event_reels_quota", nullable = false)
    private Integer eventReelsQuota;

    @Column(name = "event_posts_quota", nullable = false)
    private Integer eventPostsQuota;

    @Column(name = "carousel_quota", nullable = false)
    private Integer carouselQuota;

    @Column(name = "image_posts_quota", nullable = false)
    private Integer imagePostsQuota;

    @Column(name = "premium_support")
    private Boolean premiumSupport;

    // Usage counters for current billing cycle
    @Column(name = "reels_used", nullable = false)
    private Integer reelsUsed;

    @Column(name = "event_reels_used", nullable = false)
    private Integer eventReelsUsed;

    @Column(name = "event_posts_used", nullable = false)
    private Integer eventPostsUsed;

    @Column(name = "carousel_used", nullable = false)
    private Integer carouselUsed;

    @Column(name = "image_posts_used", nullable = false)
    private Integer imagePostsUsed;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = "ACTIVE";
        if (this.reelsUsed == null) this.reelsUsed = 0;
        if (this.eventReelsUsed == null) this.eventReelsUsed = 0;
        if (this.eventPostsUsed == null) this.eventPostsUsed = 0;
        if (this.carouselUsed == null) this.carouselUsed = 0;
        if (this.imagePostsUsed == null) this.imagePostsUsed = 0;
        if (this.premiumSupport == null) this.premiumSupport = false;
        if (this.startDate == null) this.startDate = LocalDate.now();
        if (this.endDate == null) this.endDate = LocalDate.now().plusMonths(1);
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public int getRemainingReels() {
        return Math.max(0, (reelsQuota != null ? reelsQuota : 0) - (reelsUsed != null ? reelsUsed : 0));
    }

    public int getRemainingEventReels() {
        return Math.max(0, (eventReelsQuota != null ? eventReelsQuota : 0) - (eventReelsUsed != null ? eventReelsUsed : 0));
    }

    public int getRemainingEventPosts() {
        return Math.max(0, (eventPostsQuota != null ? eventPostsQuota : 0) - (eventPostsUsed != null ? eventPostsUsed : 0));
    }

    public int getRemainingCarousel() {
        return Math.max(0, (carouselQuota != null ? carouselQuota : 0) - (carouselUsed != null ? carouselUsed : 0));
    }

    public int getRemainingImagePosts() {
        return Math.max(0, (imagePostsQuota != null ? imagePostsQuota : 0) - (imagePostsUsed != null ? imagePostsUsed : 0));
    }
}

