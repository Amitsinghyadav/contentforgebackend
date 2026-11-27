package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.model.entity.BrandSubscriptionEntity;
import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.PostStatus;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.repository.BrandSubscriptionRepository;
import com.ozo.contentforge.repository.ContentCalendarEventRepository;
import com.ozo.contentforge.repository.ContentPostRepository;
import com.ozo.contentforge.repository.PlanRepository;
import com.ozo.contentforge.tenancy.BrandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Plans (Standard, Premium, Custom), the plan each brand is on, and how much of this month's content it has used.
 *
 * Usage is counted from what is planned, not from counters, so deleting a planner item frees its slot automatically:
 * every planner item in the month counts, plus content made directly without a planner item.
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM");

    private final PlanRepository planRepository;
    private final BrandSubscriptionRepository subscriptionRepository;
    private final BrandRepository brandRepository;
    private final ContentCalendarEventRepository eventRepository;
    private final ContentPostRepository postRepository;

    public PlanService(PlanRepository planRepository, BrandSubscriptionRepository subscriptionRepository,
                       BrandRepository brandRepository, ContentCalendarEventRepository eventRepository,
                       ContentPostRepository postRepository) {
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.brandRepository = brandRepository;
        this.eventRepository = eventRepository;
        this.postRepository = postRepository;
    }

    // =========================================================================
    // Plans
    // =========================================================================

    public List<PlanEntity> getAllPlans() {
        return planRepository.findAll();
    }

    public List<PlanEntity> getActivePlans() {
        return planRepository.findByActiveTrueOrderByIdAsc();
    }

    public PlanEntity createPlan(PlanEntity plan) {
        plan.setId(null);
        validate(plan);
        return planRepository.save(plan);
    }

    public PlanEntity updatePlan(Long id, PlanEntity updated) {
        PlanEntity existing = planRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Plan not found"));
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getTier() != null) existing.setTier(updated.getTier());
        if (updated.getDescription() != null) existing.setDescription(updated.getDescription());
        if (updated.getBillingCycle() != null) existing.setBillingCycle(updated.getBillingCycle());
        if (updated.getPriceInr() != null) existing.setPriceInr(updated.getPriceInr());
        if (updated.getAvatarReelsQuota() != null) existing.setAvatarReelsQuota(updated.getAvatarReelsQuota());
        if (updated.getExplainerQuota() != null) existing.setExplainerQuota(updated.getExplainerQuota());
        if (updated.getCarouselQuota() != null) existing.setCarouselQuota(updated.getCarouselQuota());
        if (updated.getImagePostsQuota() != null) existing.setImagePostsQuota(updated.getImagePostsQuota());
        if (updated.getStoryFramesQuota() != null) existing.setStoryFramesQuota(updated.getStoryFramesQuota());
        if (updated.getMinFaceVideos() != null) existing.setMinFaceVideos(updated.getMinFaceVideos());
        if (updated.getFeaturesJson() != null) existing.setFeaturesJson(updated.getFeaturesJson());
        if (updated.getActive() != null) existing.setActive(updated.getActive());
        validate(existing);
        return planRepository.save(existing);
    }

    /** Plans are retired, never deleted, because subscriptions point at them. */
    public PlanEntity retirePlan(Long id) {
        PlanEntity plan = planRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Plan not found"));
        plan.setActive(false);
        return planRepository.save(plan);
    }

    private static void validate(PlanEntity plan) {
        if (plan.getName() == null || plan.getName().isBlank()) throw new IllegalArgumentException("Plan name is required");
        for (Integer n : new Integer[]{plan.getAvatarReelsQuota(), plan.getExplainerQuota(), plan.getCarouselQuota(),
                plan.getImagePostsQuota(), plan.getStoryFramesQuota()}) {
            if (n == null || n < 0) throw new IllegalArgumentException("Every monthly count must be 0 or more");
        }
    }

    // =========================================================================
    // Brand subscriptions
    // =========================================================================

    public Optional<BrandSubscriptionEntity> getSubscription(Long brandId) {
        return subscriptionRepository.findFirstByBrandIdAndStatusOrderByIdDesc(brandId, "ACTIVE");
    }

    /**
     * Puts a brand on a plan. Counts come from the plan unless {@code custom} overrides them (Custom brands).
     * The new month starts on {@code startDate} (today when null).
     */
    @Transactional
    public BrandSubscriptionEntity assignPlan(Long brandId, Long planId, LocalDate startDate, Counts custom) {
        BrandEntity brand = brandRepository.findById(brandId).orElseThrow(() -> new NoSuchElementException("Brand not found"));
        PlanEntity plan = planRepository.findById(planId).orElseThrow(() -> new NoSuchElementException("Plan not found"));

        for (BrandSubscriptionEntity old : subscriptionRepository.findByBrandIdAndStatus(brandId, "ACTIVE")) {
            old.setStatus("ENDED");
            subscriptionRepository.save(old);
        }

        Counts counts = custom != null ? custom : Counts.of(plan);
        counts.validate();
        BrandSubscriptionEntity sub = subscriptionRepository.save(BrandSubscriptionEntity.builder()
                .brandId(brandId)
                .planId(plan.getId())
                .planName(plan.getName())
                .tier(custom != null ? "CUSTOM" : plan.getTier())
                .priceInr(plan.getPriceInr())
                .startDate(startDate != null ? startDate : LocalDate.now())
                .status("ACTIVE")
                .avatarReelsQuota(counts.avatarReels())
                .explainerQuota(counts.explainers())
                .carouselQuota(counts.carousels())
                .imagePostsQuota(counts.imagePosts())
                .storyFramesQuota(counts.storyFrames())
                .minFaceVideos(counts.minFaceVideos())
                .featuresJson(plan.getFeaturesJson())
                .build());
        log.info("Brand '{}' is now on plan '{}'", brand.getName(), plan.getName());
        return sub;
    }

    /** Monthly counts, either a plan's or a Custom brand's own. */
    public record Counts(int avatarReels, int explainers, int carousels, int imagePosts, int storyFrames, int minFaceVideos) {
        static Counts of(PlanEntity p) {
            return new Counts(nz(p.getAvatarReelsQuota()), nz(p.getExplainerQuota()), nz(p.getCarouselQuota()),
                    nz(p.getImagePostsQuota()), nz(p.getStoryFramesQuota()), nz(p.getMinFaceVideos()));
        }

        void validate() {
            if (avatarReels < 0 || explainers < 0 || carousels < 0 || imagePosts < 0 || storyFrames < 0 || minFaceVideos < 0) {
                throw new IllegalArgumentException("Every monthly count must be 0 or more");
            }
        }
    }

    // =========================================================================
    // Monthly usage (for the current brand)
    // =========================================================================

    public record UsageLine(String type, String label, int used, int quota) {
        public int remaining() {
            return Math.max(0, quota - used);
        }
    }

    public record Usage(Long subscriptionId, String planName, String tier, LocalDate cycleStart, LocalDate cycleEnd,
                        List<UsageLine> lines) {
    }

    /** This month's usage for the current brand, for the month that contains {@code date}. */
    public Optional<Usage> getUsage(LocalDate date) {
        Long brandId = BrandContext.require();
        return getSubscription(brandId).map(sub -> usage(sub, date));
    }

    /**
     * Fails with a clear message when planning {@code units} more of {@code type} on {@code date} would go over this
     * brand's monthly count. A brand with no plan can't plan content.
     */
    public void checkCapacity(ContentType type, LocalDate date, int units) {
        Long brandId = BrandContext.require();
        BrandSubscriptionEntity sub = getSubscription(brandId).orElseThrow(() ->
                new IllegalStateException("This brand has no plan yet. Assign one in the admin app first."));
        Usage usage = usage(sub, date);
        UsageLine line = usage.lines().stream().filter(l -> l.type().equals(type.name())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown content type " + type));
        if (line.used() + units > line.quota()) {
            throw new IllegalStateException(String.format(
                    "Monthly limit reached: %d of %d %s already planned for %s – %s. Remove one or raise the brand's plan.",
                    line.used(), line.quota(), line.label().toLowerCase(),
                    usage.cycleStart().format(DAY), usage.cycleEnd().format(DAY)));
        }
    }

    private Usage usage(BrandSubscriptionEntity sub, LocalDate date) {
        LocalDate start = cycleStart(sub.getStartDate(), date);
        LocalDate end = start.plusMonths(1).minusDays(1);

        int reels = 0, carousels = 0, posts = 0, storyFrames = 0;
        for (ContentCalendarEventEntity ev : eventRepository.findByEventDateBetweenOrderByEventDateAsc(start, end)) {
            if ("CANCELLED".equalsIgnoreCase(ev.getStatus())) continue;
            ContentType t = ev.getContentType() != null ? ev.getContentType() : ContentType.REEL;
            switch (t) {
                case REEL -> reels++;
                case CAROUSEL -> carousels++;
                case POST -> posts++;
                case STORY -> storyFrames += Math.max(1, ev.getCarouselSlideCount() != null ? ev.getCarouselSlideCount() : 1);
            }
        }
        // content made directly (Quick Generate) has no planner item but still uses the plan
        for (ContentPostEntity p : postRepository.findByTargetDateBetween(start, end)) {
            if (p.getEventId() != null || p.getStatus() == PostStatus.CANCELLED) continue;
            ContentType t = p.getContentType() != null ? p.getContentType() : ContentType.REEL;
            switch (t) {
                case REEL -> reels++;
                case CAROUSEL -> carousels++;
                case POST -> posts++;
                case STORY -> storyFrames++;
            }
        }

        List<UsageLine> lines = new ArrayList<>();
        lines.add(new UsageLine("REEL", "Avatar reels", reels, nz(sub.getAvatarReelsQuota())));
        lines.add(new UsageLine("EXPLAINER", "Explainer videos", 0, nz(sub.getExplainerQuota())));
        lines.add(new UsageLine("CAROUSEL", "Carousels", carousels, nz(sub.getCarouselQuota())));
        lines.add(new UsageLine("POST", "Posts", posts, nz(sub.getImagePostsQuota())));
        lines.add(new UsageLine("STORY", "Story frames", storyFrames, nz(sub.getStoryFramesQuota())));
        return new Usage(sub.getId(), sub.getPlanName(), sub.getTier(), start, end, lines);
    }

    /** The start of the plan month containing {@code date}: months run from the subscription's start day. */
    static LocalDate cycleStart(LocalDate subscriptionStart, LocalDate date) {
        if (date.isBefore(subscriptionStart)) return subscriptionStart;
        long months = ChronoUnit.MONTHS.between(subscriptionStart, date);
        LocalDate start = subscriptionStart.plusMonths(months);
        return start.isAfter(date) ? subscriptionStart.plusMonths(months - 1) : start;
    }

    private static int nz(Integer n) {
        return n != null ? n : 0;
    }
}
