package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.model.entity.UserSubscriptionEntity;
import com.ozo.contentforge.repository.PlanRepository;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.repository.UserSubscriptionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanRepository planRepository;
    private final UserRepository userRepository;
    private final UserSubscriptionRepository subscriptionRepository;

    public PlanService(PlanRepository planRepository,
                       UserRepository userRepository,
                       UserSubscriptionRepository subscriptionRepository) {
        this.planRepository = planRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
    }

    @PostConstruct
    @Transactional
    public void initDefaultData() {
        // 1. Seed Default Plans if table is empty
        if (planRepository.count() == 0) {
            log.info("Seeding default subscription plans...");
            planRepository.save(PlanEntity.builder()
                    .name("Basic Plan")
                    .description("Ideal for emerging clinics and single practitioner brands looking to establish a consistent digital presence.")
                    .billingCycle("MONTHLY")
                    .priceInr(4999.0)
                    .reelsQuota(2)
                    .eventReelsQuota(1)
                    .eventPostsQuota(2)
                    .carouselQuota(2)
                    .imagePostsQuota(5)
                    .premiumSupport(false)
                    .featuresJson("[\"HD Video Avatars\", \"Standard Hinglish Voice\", \"Community Support\"]")
                    .active(true)
                    .build());

            planRepository.save(PlanEntity.builder()
                    .name("Standard Plan")
                    .description("Designed for growing healthcare practices requiring weekly reels and multi-format medical carousels.")
                    .billingCycle("MONTHLY")
                    .priceInr(9999.0)
                    .reelsQuota(4)
                    .eventReelsQuota(2)
                    .eventPostsQuota(4)
                    .carouselQuota(5)
                    .imagePostsQuota(10)
                    .premiumSupport(false)
                    .featuresJson("[\"Avatar IV Dynamic Twin\", \"Voice Clone Audio\", \"Occasion Scheduler\", \"Priority Rendering\"]")
                    .active(true)
                    .build());

            planRepository.save(PlanEntity.builder()
                    .name("Premium Plan")
                    .description("High-frequency omnichannel content engine with studio video twin, professional voice cloning, and VIP review.")
                    .billingCycle("MONTHLY")
                    .priceInr(19999.0)
                    .reelsQuota(8)
                    .eventReelsQuota(4)
                    .eventPostsQuota(8)
                    .carouselQuota(10)
                    .imagePostsQuota(20)
                    .premiumSupport(true)
                    .featuresJson("[\"Professional Voice Clone (PVC)\", \"Custom Background Studio\", \"Clinical Review Workflow\", \"24/7 Dedicated Account Manager\"]")
                    .active(true)
                    .build());

            planRepository.save(PlanEntity.builder()
                    .name("Corporate Enterprise Plan")
                    .description("Unlimited enterprise scale for hospital chains, diagnostic networks, and multi-location health brands.")
                    .billingCycle("YEARLY")
                    .priceInr(199999.0)
                    .reelsQuota(50)
                    .eventReelsQuota(25)
                    .eventPostsQuota(50)
                    .carouselQuota(60)
                    .imagePostsQuota(120)
                    .premiumSupport(true)
                    .featuresJson("[\"Multi-presenter Digital Twins\", \"Custom AI Training Pipelines\", \"White-label Export\", \"API Direct Webhooks\"]")
                    .active(true)
                    .build());
            log.info("Default plans seeded successfully.");
        }

        // Subscription plans configuration is dynamically managed via the Admin Dashboard.
    }

    public List<PlanEntity> getAllPlans() {
        return planRepository.findAll();
    }

    public List<PlanEntity> getActivePlans() {
        return planRepository.findByActiveTrueOrderByIdAsc();
    }

    public PlanEntity createPlan(PlanEntity plan) {
        return planRepository.save(plan);
    }

    public PlanEntity updatePlan(Long id, PlanEntity updated) {
        PlanEntity existing = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + id));

        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getBillingCycle() != null) existing.setBillingCycle(updated.getBillingCycle());
        if (updated.getPriceInr() != null) existing.setPriceInr(updated.getPriceInr());
        if (updated.getReelsQuota() != null) existing.setReelsQuota(updated.getReelsQuota());
        if (updated.getEventReelsQuota() != null) existing.setEventReelsQuota(updated.getEventReelsQuota());
        if (updated.getEventPostsQuota() != null) existing.setEventPostsQuota(updated.getEventPostsQuota());
        if (updated.getCarouselQuota() != null) existing.setCarouselQuota(updated.getCarouselQuota());
        if (updated.getImagePostsQuota() != null) existing.setImagePostsQuota(updated.getImagePostsQuota());
        if (updated.getPremiumSupport() != null) existing.setPremiumSupport(updated.getPremiumSupport());
        if (updated.getFeaturesJson() != null) existing.setFeaturesJson(updated.getFeaturesJson());
        if (updated.getActive() != null) existing.setActive(updated.getActive());

        return planRepository.save(existing);
    }

    public void deletePlan(Long id) {
        planRepository.deleteById(id);
    }

    @Transactional
    public UserSubscriptionEntity assignPlanToUser(Long userId, Long planId, String billingCycle, int durationMonths) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planId));

        // Deactivate existing active subscriptions
        List<UserSubscriptionEntity> activeSubs = subscriptionRepository.findActiveSubscriptionsByUserId(userId);
        for (UserSubscriptionEntity s : activeSubs) {
            s.setStatus("EXPIRED");
            subscriptionRepository.save(s);
        }

        LocalDate start = LocalDate.now();
        LocalDate end = start.plusMonths(Math.max(1, durationMonths));

        UserSubscriptionEntity sub = UserSubscriptionEntity.builder()
                .userId(user.getId())
                .planId(plan.getId())
                .planName(plan.getName())
                .billingCycle(billingCycle != null ? billingCycle : plan.getBillingCycle())
                .startDate(start)
                .endDate(end)
                .status("ACTIVE")
                .reelsQuota(plan.getReelsQuota())
                .eventReelsQuota(plan.getEventReelsQuota())
                .eventPostsQuota(plan.getEventPostsQuota())
                .carouselQuota(plan.getCarouselQuota())
                .imagePostsQuota(plan.getImagePostsQuota())
                .premiumSupport(plan.getPremiumSupport())
                .reelsUsed(0)
                .eventReelsUsed(0)
                .eventPostsUsed(0)
                .carouselUsed(0)
                .imagePostsUsed(0)
                .build();

        log.info("Assigned plan '{}' to organization '{}' (expires: {})", plan.getName(), user.getOrganizationName(), end);
        return subscriptionRepository.save(sub);
    }

    public Optional<UserSubscriptionEntity> getActiveSubscriptionForUser(Long userId) {
        if (userId != null) {
            Optional<UserSubscriptionEntity> sub = subscriptionRepository.findFirstActiveByUserId(userId);
            if (sub.isPresent()) return sub;
        }
        // Fallback to latest active subscription across system if userId is null
        List<UserSubscriptionEntity> all = subscriptionRepository.findAll();
        return all.stream().filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus())).findFirst();
    }

    @Transactional
    public boolean consumeQuota(Long userId, String plannerType) {
        Optional<UserSubscriptionEntity> subOpt = getActiveSubscriptionForUser(userId);
        if (subOpt.isEmpty()) {
            return true; // No active subscription blocking
        }
        UserSubscriptionEntity sub = subOpt.get();
        String type = plannerType != null ? plannerType.toUpperCase() : "REEL";

        switch (type) {
            case "EVENT_REEL" -> {
                if (sub.getRemainingEventReels() <= 0) return false;
                sub.setEventReelsUsed(sub.getEventReelsUsed() + 1);
            }
            case "EVENT_POST" -> {
                if (sub.getRemainingEventPosts() <= 0) return false;
                sub.setEventPostsUsed(sub.getEventPostsUsed() + 1);
            }
            case "CAROUSEL" -> {
                if (sub.getRemainingCarousel() <= 0) return false;
                sub.setCarouselUsed(sub.getCarouselUsed() + 1);
            }
            case "IMAGE_POST", "POST" -> {
                if (sub.getRemainingImagePosts() <= 0) return false;
                sub.setImagePostsUsed(sub.getImagePostsUsed() + 1);
            }
            case "REEL" -> {
                if (sub.getRemainingReels() <= 0) return false;
                sub.setReelsUsed(sub.getReelsUsed() + 1);
            }
            default -> {
                if (sub.getRemainingReels() <= 0) return false;
                sub.setReelsUsed(sub.getReelsUsed() + 1);
            }
        }
        subscriptionRepository.save(sub);
        return true;
    }
}

