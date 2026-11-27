package com.ozo.contentforge.service.publishing;

import com.ozo.contentforge.model.entity.CarouselSlideEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.entity.InstagramPublicationEntity;
import com.ozo.contentforge.model.entity.StoryFrameEntity;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.FramePublishStatus;
import com.ozo.contentforge.model.enums.PostStatus;
import com.ozo.contentforge.repository.CarouselSlideRepository;
import com.ozo.contentforge.repository.ContentPostRepository;
import com.ozo.contentforge.repository.InstagramPublicationRepository;
import com.ozo.contentforge.repository.StoryFrameRepository;
import com.ozo.contentforge.service.MasterSettingsService;
import com.ozo.contentforge.service.MetaIntegrationService;
import com.ozo.contentforge.service.ReelEnhanceService;
import com.ozo.contentforge.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Publishes approved content of the current brand to its own Instagram account, by format: reel, single image,
 * carousel, or story frames. Temporary failures are retried with a growing delay; anything else (or the last retry)
 * marks the post PUBLISH_FAILED with Instagram's message. Nothing is ever marked published without Instagram's id.
 */
@Service
public class PublishingService {

    private static final Logger log = LoggerFactory.getLogger(PublishingService.class);
    private static final Duration LINK_LIFETIME = Duration.ofHours(24);
    private static final Set<PostStatus> PUBLISHABLE = Set.of(PostStatus.SCHEDULED, PostStatus.FINAL_APPROVED,
            PostStatus.PUBLISH_FAILED);

    private final ContentPostRepository postRepository;
    private final CarouselSlideRepository slideRepository;
    private final StoryFrameRepository frameRepository;
    private final InstagramPublicationRepository publicationRepository;
    private final MasterSettingsService settingsService;
    private final MetaIntegrationService metaIntegrationService;
    private final StorageService storageService;
    private final InstagramPublisher instagram;

    @Value("${contentforge.publish.max-attempts:4}")
    private int maxAttempts;

    public PublishingService(ContentPostRepository postRepository, CarouselSlideRepository slideRepository,
                             StoryFrameRepository frameRepository, InstagramPublicationRepository publicationRepository,
                             MasterSettingsService settingsService, MetaIntegrationService metaIntegrationService,
                             StorageService storageService, InstagramPublisher instagram) {
        this.postRepository = postRepository;
        this.slideRepository = slideRepository;
        this.frameRepository = frameRepository;
        this.publicationRepository = publicationRepository;
        this.settingsService = settingsService;
        this.metaIntegrationService = metaIntegrationService;
        this.storageService = storageService;
        this.instagram = instagram;
    }

    /**
     * Publishes one post now. Used by the scheduler and by "Publish now". Returns the updated post; when Instagram
     * refuses it, the post carries the reason (PUBLISH_FAILED) or is rescheduled for a retry (SCHEDULED).
     */
    public ContentPostEntity publish(Long postId) {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new NoSuchElementException("Post not found"));
        if (!PUBLISHABLE.contains(post.getStatus())) {
            throw new IllegalStateException("Only approved content can be published (this one is " + label(post.getStatus()) + ")");
        }
        if (postRepository.claimForPublishing(postId, PUBLISHABLE) == 0) {
            throw new IllegalStateException("This post is already being published");
        }
        post = postRepository.findById(postId).orElseThrow();

        try {
            InstagramPublisher.Account account = account();
            if (instagram.isAtPublishingLimit(account)) {
                throw new PublishException("Instagram's 24-hour publishing limit for this account is used up", true);
            }
            ContentType type = post.getContentType() != null ? post.getContentType() : ContentType.REEL;
            switch (type) {
                case REEL -> publishReel(post, account);
                case POST -> publishImagePost(post, account);
                case CAROUSEL -> publishCarousel(post, account);
                case STORY -> publishStory(post, account);
            }
            post.setPublishError(null);
            post.setNextPublishAttemptAt(null);
            if (post.getPublishedAt() == null) post.setPublishedAt(LocalDateTime.now());
            log.info("Published post {} ({}) to Instagram", post.getId(), type);
        } catch (PublishException e) {
            handleFailure(post, e);
        } catch (RuntimeException e) {
            handleFailure(post, new PublishException("Unexpected error: " + e.getMessage(), true));
        }
        return postRepository.save(post);
    }

    /** Puts a failed post back in the queue to publish at the next scheduler run. */
    public ContentPostEntity retry(Long postId) {
        ContentPostEntity post = postRepository.findById(postId).orElseThrow(() -> new NoSuchElementException("Post not found"));
        if (post.getStatus() != PostStatus.PUBLISH_FAILED) {
            throw new IllegalStateException("Only failed publishes can be retried");
        }
        post.setStatus(PostStatus.SCHEDULED);
        post.setPublishAttempts(0);
        post.setNextPublishAttemptAt(null);
        post.setPublishError(null);
        if (post.getScheduledAt() == null || post.getScheduledAt().isAfter(LocalDateTime.now())) {
            post.setScheduledAt(LocalDateTime.now());
        }
        return postRepository.save(post);
    }

    // =========================================================================
    // Formats
    // =========================================================================

    private void publishReel(ContentPostEntity post, InstagramPublisher.Account account) {
        boolean enhanced = ReelEnhanceService.ENHANCED.equals(post.getRenderStatus()) && notBlank(post.getEnhancedRenderUrl());
        String video = enhanced ? post.getEnhancedRenderUrl() : post.getFinalRenderUrl();
        if (!notBlank(video)) throw new PublishException("This reel has no final video yet", false);
        String cover = isImage(post.getThumbnailUrl()) ? link(post.getThumbnailUrl()) : null;
        InstagramPublisher.Published p = instagram.publishReel(account, link(video), caption(post), cover);
        recordPublished(post, p);
        crossPostToFacebook(post, video, true);
    }

    private void publishImagePost(ContentPostEntity post, InstagramPublisher.Account account) {
        String image = notBlank(post.getFinalRenderUrl()) ? post.getFinalRenderUrl() : post.getThumbnailUrl();
        if (!isImage(image)) throw new PublishException("This post has no image yet", false);
        InstagramPublisher.Published p = instagram.publishImage(account, link(image), caption(post));
        recordPublished(post, p);
        crossPostToFacebook(post, image, false);
    }

    private void publishCarousel(ContentPostEntity post, InstagramPublisher.Account account) {
        List<String> images = slideRepository.findByPostIdOrderBySlideNumberAsc(post.getId()).stream()
                .map(CarouselSlideEntity::getImageUrl)
                .filter(this::notBlank)
                .map(this::link)
                .toList();
        InstagramPublisher.Published p = instagram.publishCarousel(account, images, caption(post));
        recordPublished(post, p);
    }

    /**
     * Frames without stickers are published in order. Sticker frames (poll, quiz, question, link) can't be posted
     * through the API, so they go to the team's "post by hand" list.
     */
    private void publishStory(ContentPostEntity post, InstagramPublisher.Account account) {
        List<StoryFrameEntity> frames = frameRepository.findByPostIdOrderByFrameNumberAsc(post.getId());
        if (frames.isEmpty()) throw new PublishException("This story has no frames", false);

        boolean anyAuto = false;
        for (StoryFrameEntity frame : frames) {
            if (frame.getPublishStatus() == FramePublishStatus.PUBLISHED || frame.getPublishStatus() == FramePublishStatus.MANUAL_DONE) {
                if (frame.getPublishStatus() == FramePublishStatus.PUBLISHED) anyAuto = true;
                continue;
            }
            if (frame.getFrameType() != null && frame.getFrameType().needsSticker()) {
                frame.setPublishStatus(FramePublishStatus.MANUAL_PENDING);
                frameRepository.save(frame);
                continue;
            }
            if (!notBlank(frame.getMediaUrl())) throw new PublishException("Story frame " + frame.getFrameNumber() + " has no image yet", false);
            try {
                InstagramPublisher.Published p = instagram.publishStoryFrame(account, link(frame.getMediaUrl()), isVideo(frame.getMediaUrl()));
                frame.setInstagramMediaId(p.mediaId());
                frame.setPublishedAt(LocalDateTime.now());
                frame.setPublishStatus(FramePublishStatus.PUBLISHED);
                frame.setPublishError(null);
                frameRepository.save(frame);
                anyAuto = true;
            } catch (PublishException e) {
                frame.setPublishStatus(FramePublishStatus.FAILED);
                frame.setPublishError(e.getMessage());
                frameRepository.save(frame);
                throw e; // frames must stay in order: stop here, a retry continues from this frame
            }
        }

        boolean manualLeft = frames.stream().anyMatch(f -> f.getPublishStatus() == FramePublishStatus.MANUAL_PENDING);
        post.setStatus(!anyAuto && manualLeft ? PostStatus.AWAITING_MANUAL_POST : PostStatus.PUBLISHED);
        frames.stream().filter(f -> f.getInstagramMediaId() != null).findFirst()
                .ifPresent(f -> post.setInstagramMediaId(f.getInstagramMediaId()));
    }

    // =========================================================================

    private void recordPublished(ContentPostEntity post, InstagramPublisher.Published p) {
        post.setInstagramContainerId(p.containerId());
        post.setInstagramMediaId(p.mediaId());
        post.setPublishedPermalink(p.permalink());
        post.setPublishedAt(LocalDateTime.now());
        post.setStatus(PostStatus.PUBLISHED);
        publicationRepository.save(InstagramPublicationEntity.builder()
                .postId(post.getId())
                .containerId(p.containerId())
                .mediaId(p.mediaId())
                .permalink(p.permalink())
                .scheduledAt(post.getScheduledAt())
                .publishedAt(LocalDateTime.now())
                .status("PUBLISHED")
                .build());
    }

    /** Facebook is a bonus: a failure there never fails the Instagram publish. */
    private void crossPostToFacebook(ContentPostEntity post, String mediaUrl, boolean isVideo) {
        try {
            Map<String, String> fb = metaIntegrationService.publishToFacebookPage(post.getId(), mediaUrl, caption(post), isVideo);
            if (fb != null) {
                post.setFacebookPostId(fb.get("postId"));
                post.setFacebookPermalink(fb.get("permalink"));
            }
        } catch (Exception e) {
            log.warn("Facebook cross-post failed for post {}: {}", post.getId(), e.getMessage());
        }
    }

    private void handleFailure(ContentPostEntity post, PublishException e) {
        int attempts = (post.getPublishAttempts() != null ? post.getPublishAttempts() : 0) + 1;
        post.setPublishAttempts(attempts);
        post.setPublishError(e.getMessage());
        if (e.isRetryable() && attempts < maxAttempts) {
            long minutes = (long) (5 * Math.pow(3, attempts - 1)); // 5, 15, 45 minutes
            post.setStatus(PostStatus.SCHEDULED);
            post.setNextPublishAttemptAt(LocalDateTime.now().plusMinutes(minutes));
            log.warn("Publishing post {} failed (attempt {}), retrying in {} min: {}", post.getId(), attempts, minutes, e.getMessage());
        } else {
            post.setStatus(PostStatus.PUBLISH_FAILED);
            post.setNextPublishAttemptAt(null);
            log.error("Publishing post {} failed for good after {} attempt(s): {}", post.getId(), attempts, e.getMessage());
        }
    }

    private InstagramPublisher.Account account() {
        Map<String, Object> s = settingsService.getAllSettings();
        String igId = str(s.get("instagramAccountId"));
        String token = str(s.get("facebookPageAccessToken"));
        if (!Boolean.TRUE.equals(s.get("metaConnected")) || igId == null || token == null) {
            throw new PublishException("Instagram isn't connected for this brand. Connect it in Settings → Instagram.", false);
        }
        return new InstagramPublisher.Account(igId, token);
    }

    private String caption(ContentPostEntity post) {
        String caption = post.getCaption() != null ? post.getCaption().trim() : "";
        String tags = post.getHashtags() != null ? post.getHashtags().trim() : "";
        return tags.isEmpty() ? caption : caption + "\n\n" + tags;
    }

    /** Instagram downloads media itself, so private S3 objects get a temporary link. */
    private String link(String url) {
        return storageService.getPresignedUrl(url, LINK_LIFETIME);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean isImage(String url) {
        if (url == null) return false;
        String u = url.toLowerCase().split("\\?")[0];
        return u.endsWith(".png") || u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".webp");
    }

    private static boolean isVideo(String url) {
        if (url == null) return false;
        String u = url.toLowerCase().split("\\?")[0];
        return u.endsWith(".mp4") || u.endsWith(".mov");
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String label(PostStatus status) {
        return status == null ? "unknown" : status.name().toLowerCase().replace('_', ' ');
    }
}
