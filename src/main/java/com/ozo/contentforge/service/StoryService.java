package com.ozo.contentforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.model.entity.ContentCalendarEventEntity;
import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.entity.StoryFrameEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.FramePublishStatus;
import com.ozo.contentforge.model.enums.JobStatus;
import com.ozo.contentforge.model.enums.PostStatus;
import com.ozo.contentforge.model.enums.StoryFrameType;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.repository.ContentCalendarEventRepository;
import com.ozo.contentforge.repository.ContentPostRepository;
import com.ozo.contentforge.repository.StoryFrameRepository;
import com.ozo.contentforge.security.CurrentUser;
import com.ozo.contentforge.tenancy.BrandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Instagram stories: the AI writes the frames, the team edits and approves them, the render service draws each frame
 * in the brand's style, and the publisher posts them. Sticker frames go to a team-wide "post by hand" list.
 */
@Service
public class StoryService {

    private static final Logger log = LoggerFactory.getLogger(StoryService.class);
    private static final int DEFAULT_FRAMES = 3;

    private final ContentPostRepository postRepository;
    private final StoryFrameRepository frameRepository;
    private final ContentCalendarEventRepository eventRepository;
    private final BrandRepository brandRepository;
    private final AiProviderService aiProviderService;
    private final RenderServiceClient renderServiceClient;
    private final StorageService storageService;
    private final MasterSettingsService settingsService;
    private final ObjectMapper objectMapper;

    public StoryService(ContentPostRepository postRepository, StoryFrameRepository frameRepository,
                        ContentCalendarEventRepository eventRepository, BrandRepository brandRepository,
                        AiProviderService aiProviderService, RenderServiceClient renderServiceClient,
                        StorageService storageService, MasterSettingsService settingsService, ObjectMapper objectMapper) {
        this.postRepository = postRepository;
        this.frameRepository = frameRepository;
        this.eventRepository = eventRepository;
        this.brandRepository = brandRepository;
        this.aiProviderService = aiProviderService;
        this.renderServiceClient = renderServiceClient;
        this.storageService = storageService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /** Creates a story post with AI-written frames, waiting for text approval. */
    @Transactional
    public ContentPostEntity planStory(String idempotencyKey, LocalDate date, Long batchId, Long eventId, String topic,
                                       String industry, String brandTone, String audience, String cta) {
        ContentCalendarEventEntity event = eventId != null ? eventRepository.findById(eventId).orElse(null) : null;
        int frameCount = event != null && event.getCarouselSlideCount() != null ? event.getCarouselSlideCount() : DEFAULT_FRAMES;
        String language = event != null && event.getLanguage() != null ? event.getLanguage()
                : String.valueOf(settingsService.getAllSettings().getOrDefault("captionLanguage", "Hinglish"));

        List<Map<String, Object>> written = aiProviderService.generateStoryFrames(topic, industry, brandTone, audience, language, cta, frameCount);

        ContentPostEntity post = postRepository.save(ContentPostEntity.builder()
                .idempotencyKey(idempotencyKey)
                .batchId(batchId)
                .eventId(eventId)
                .targetDate(date)
                .contentType(ContentType.STORY)
                .topic(topic)
                .hook(str(written.get(0).get("headline")))
                .caption("")
                .hashtags("")
                .status(PostStatus.PENDING_TEXT_APPROVAL)
                .build());

        int n = 1;
        for (Map<String, Object> f : written) {
            frameRepository.save(StoryFrameEntity.builder()
                    .postId(post.getId())
                    .frameNumber(n++)
                    .frameType(parseType(str(f.get("type"))))
                    .headline(str(f.get("headline")))
                    .body(str(f.get("body")))
                    .stickerJson(f.get("sticker") != null ? json(f.get("sticker")) : null)
                    .status(JobStatus.QUEUED)
                    .publishStatus(FramePublishStatus.PENDING)
                    .build());
        }
        log.info("Story post {} planned with {} frames", post.getId(), written.size());
        return post;
    }

    public List<StoryFrameEntity> frames(Long postId) {
        return frameRepository.findByPostIdOrderByFrameNumberAsc(postId);
    }

    /** Edits a frame's text, type or sticker before it is drawn. Editing a drawn frame clears its image. */
    @Transactional
    public StoryFrameEntity updateFrame(Long postId, int frameNumber, String type, String headline, String body,
                                        Map<String, Object> sticker, String backgroundUrl) {
        StoryFrameEntity frame = frames(postId).stream().filter(f -> f.getFrameNumber() == frameNumber).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Frame not found"));
        if (frame.getPublishStatus() == FramePublishStatus.PUBLISHED || frame.getPublishStatus() == FramePublishStatus.MANUAL_DONE) {
            throw new IllegalStateException("This frame is already on Instagram");
        }
        if (type != null) frame.setFrameType(parseType(type));
        if (headline != null) frame.setHeadline(headline.trim());
        if (body != null) frame.setBody(body.trim());
        if (sticker != null) frame.setStickerJson(sticker.isEmpty() ? null : json(sticker));
        if (backgroundUrl != null) frame.setBackgroundUrl(backgroundUrl.isBlank() ? null : backgroundUrl.trim());
        frame.setMediaUrl(null);
        frame.setStatus(JobStatus.QUEUED);
        return frameRepository.save(frame);
    }

    /**
     * Draws every frame that has no image yet, then sends the story to final approval. Runs after text approval
     * (in the background); a frame that fails keeps its error and the story is marked FAILED.
     */
    public void renderFrames(Long postId) {
        ContentPostEntity post = postRepository.findById(postId).orElseThrow(() -> new NoSuchElementException("Post not found"));
        post.setStatus(PostStatus.MEDIA_GENERATING);
        postRepository.save(post);

        Map<String, Object> style = renderServiceClient.brandStyle(settingsService.getAllSettings());
        List<StoryFrameEntity> frames = frames(postId);
        boolean failed = false;
        for (StoryFrameEntity frame : frames) {
            if (frame.getMediaUrl() != null && frame.getStatus() == JobStatus.COMPLETED) continue;
            try {
                Map<String, Object> props = new HashMap<>(style);
                props.put("type", frame.getFrameType().name());
                props.put("headline", frame.getHeadline());
                props.put("body", frame.getBody());
                props.put("frameNumber", frame.getFrameNumber());
                props.put("frameCount", frames.size());
                if (frame.getStickerJson() != null) props.put("sticker", objectMapper.readValue(frame.getStickerJson(), new TypeReference<Map<String, Object>>() {}));
                if (frame.getBackgroundUrl() != null) props.put("backgroundUrl", storageService.getPresignedUrl(frame.getBackgroundUrl(), java.time.Duration.ofHours(1)));

                byte[] png = renderServiceClient.renderStoryFrame(props);
                GeneratedAssetEntity asset = storageService.saveGeneratedMedia(png, ".png", "image/png", AssetType.IMAGE, postId, null, null, null);
                frame.setMediaUrl(asset.getStorageUrl());
                frame.setStatus(JobStatus.COMPLETED);
                frame.setErrorMessage(null);
            } catch (Exception e) {
                failed = true;
                frame.setStatus(JobStatus.FAILED);
                frame.setErrorMessage(e.getMessage());
                log.warn("Story frame {} of post {} failed: {}", frame.getFrameNumber(), postId, e.getMessage());
            }
            frameRepository.save(frame);
        }

        post = postRepository.findById(postId).orElseThrow();
        if (failed) {
            post.setStatus(PostStatus.FAILED);
            post.setErrorMessage("Some story frames couldn't be drawn. Fix them and retry.");
        } else {
            post.setThumbnailUrl(frames.get(0).getMediaUrl());
            post.setFinalRenderUrl(frames.get(0).getMediaUrl());
            post.setErrorMessage(null);
            post.setStatus(PostStatus.PENDING_FINAL_APPROVAL);
        }
        postRepository.save(post);
    }

    // =========================================================================
    // "Post by hand" list (sticker frames), across all brands
    // =========================================================================

    /** Sticker frames waiting to be posted by hand, oldest first, with their brand and what to add. */
    public List<Map<String, Object>> manualQueue() {
        List<StoryFrameEntity> pending = BrandContext.callAsSystem(() ->
                frameRepository.findByPublishStatusOrderByCreatedAtAsc(FramePublishStatus.MANUAL_PENDING));
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoryFrameEntity f : pending) {
            BrandEntity brand = brandRepository.findById(f.getBrandId()).orElse(null);
            Map<String, Object> settings = BrandContext.callAs(f.getBrandId(), settingsService::getAllSettings);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("frameId", f.getId());
            row.put("postId", f.getPostId());
            row.put("brandId", f.getBrandId());
            row.put("brandName", brand != null ? brand.getName() : "");
            row.put("instagramUsername", settings.getOrDefault("instagramUsername", ""));
            row.put("frameNumber", f.getFrameNumber());
            row.put("frameType", f.getFrameType());
            row.put("headline", f.getHeadline());
            row.put("body", f.getBody());
            row.put("sticker", f.getStickerJson() != null ? parse(f.getStickerJson()) : null);
            row.put("imageUrl", f.getMediaUrl() != null ? storageService.getPresignedUrl(f.getMediaUrl(), java.time.Duration.ofHours(12)) : null);
            row.put("waitingSince", f.getUpdatedAt());
            out.add(row);
        }
        return out;
    }

    /** The team posted this sticker frame by hand. When no sticker frames are left, the story counts as published. */
    public Map<String, Object> markPostedByHand(Long frameId) {
        StoryFrameEntity frame = BrandContext.callAsSystem(() -> frameRepository.findById(frameId))
                .orElseThrow(() -> new NoSuchElementException("Frame not found"));
        if (frame.getPublishStatus() != FramePublishStatus.MANUAL_PENDING) {
            throw new IllegalStateException("This frame isn't waiting to be posted by hand");
        }
        return BrandContext.callAs(frame.getBrandId(), () -> {
            StoryFrameEntity f = frameRepository.findById(frameId).orElseThrow();
            f.setPublishStatus(FramePublishStatus.MANUAL_DONE);
            f.setManualPostedBy(CurrentUser.actor());
            f.setManualPostedAt(LocalDateTime.now());
            frameRepository.save(f);

            boolean anyLeft = frames(f.getPostId()).stream().anyMatch(x -> x.getPublishStatus() == FramePublishStatus.MANUAL_PENDING);
            if (!anyLeft) {
                postRepository.findById(f.getPostId()).ifPresent(p -> {
                    if (p.getStatus() == PostStatus.AWAITING_MANUAL_POST) {
                        p.setStatus(PostStatus.PUBLISHED);
                        if (p.getPublishedAt() == null) p.setPublishedAt(LocalDateTime.now());
                        postRepository.save(p);
                    }
                });
            }
            return Map.<String, Object>of("frameId", frameId, "publishStatus", FramePublishStatus.MANUAL_DONE);
        });
    }

    // =========================================================================

    private static StoryFrameType parseType(String type) {
        try {
            return StoryFrameType.valueOf(type != null ? type.trim().toUpperCase() : "TIP");
        } catch (IllegalArgumentException e) {
            return StoryFrameType.TIP;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid sticker data");
        }
    }

    private Object parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
