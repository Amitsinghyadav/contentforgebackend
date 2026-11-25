package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.ContentPostEntity;
import com.ozo.contentforge.model.entity.ContentSceneEntity;
import com.ozo.contentforge.model.entity.VideoRenderEntity;
import com.ozo.contentforge.model.enums.ContentType;
import com.ozo.contentforge.model.enums.JobStatus;
import com.ozo.contentforge.model.enums.PostStatus;
import com.ozo.contentforge.model.enums.SceneType;
import com.ozo.contentforge.repository.ContentPostRepository;
import com.ozo.contentforge.repository.ContentSceneRepository;
import com.ozo.contentforge.repository.VideoRenderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "Enhance": turns a reel's basic FFmpeg video into the full edit made by the render service (AI-directed motion
 * graphics, styled captions, logo outro, sound design).
 *
 * The basic reel always exists first and stays if enhancing fails. On success the enhanced video becomes the reel's
 * final video (a new render version), unless the reel was approved in the meantime: then it is only kept as a version.
 */
@Service
public class ReelEnhanceService {

    private static final Logger log = LoggerFactory.getLogger(ReelEnhanceService.class);

    public static final String ENHANCING = "ENHANCING";
    public static final String ENHANCED = "ENHANCED";
    public static final String ENHANCE_FAILED = "ENHANCE_FAILED";

    /** Approved or scheduled reels go back to final approval while they are enhanced. */
    static final String REAPPROVE_NOTE = "Sent back to final approval for the enhanced version: approve it again to schedule it.";

    private final ContentPostRepository postRepository;
    private final ContentSceneRepository sceneRepository;
    private final VideoRenderRepository renderRepository;
    private final MasterSettingsService settingsService;
    private final RenderServiceClient renderServiceClient;

    public ReelEnhanceService(ContentPostRepository postRepository, ContentSceneRepository sceneRepository,
                              VideoRenderRepository renderRepository, MasterSettingsService settingsService,
                              RenderServiceClient renderServiceClient) {
        this.postRepository = postRepository;
        this.sceneRepository = sceneRepository;
        this.renderRepository = renderRepository;
        this.settingsService = settingsService;
        this.renderServiceClient = renderServiceClient;
    }

    /** Settings (or RENDER_ENGINE=remotion) ask to enhance every reel after its basic version. */
    public boolean isAutoEnhance() {
        return renderServiceClient.isAutoEnhance(settingsService.getAllSettings());
    }

    /** Checks the reel can be enhanced and marks it ENHANCING. Call before {@link #enhanceAsync}. */
    public ContentPostEntity markEnhancing(Long postId) {
        ContentPostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
        if (post.getContentType() != ContentType.REEL) throw new IllegalStateException("Only reels can be enhanced");
        if (post.getAvatarVideoUrl() == null || post.getAvatarVideoUrl().isBlank())
            throw new IllegalStateException("This reel has no avatar video yet");
        PostStatus status = post.getStatus();
        boolean allowed = status == PostStatus.PENDING_FINAL_APPROVAL || status == PostStatus.FINAL_APPROVED
                || status == PostStatus.SCHEDULED || status == PostStatus.PUBLISHED;
        if (!allowed) throw new IllegalStateException("This reel can't be enhanced while it is " + status.name().toLowerCase().replace('_', ' '));
        if (ENHANCING.equals(post.getRenderStatus())) throw new IllegalStateException("This reel is already being enhanced");
        post.setRenderStatus(ENHANCING);
        post.setRenderNote(null);
        if (status == PostStatus.FINAL_APPROVED || status == PostStatus.SCHEDULED) {
            // the approved video is about to change: it needs approving again (the scheduled time is kept)
            post.setStatus(PostStatus.PENDING_FINAL_APPROVAL);
            post.setRenderNote(REAPPROVE_NOTE);
        }
        return postRepository.save(post);
    }

    /** Runs the enhance in the background (takes several minutes). */
    @Async
    public void enhanceAsync(Long postId) {
        enhance(postId);
    }

    /**
     * Runs the enhance on the calling thread. The post must already be marked ENHANCING.
     * While the reel is still MEDIA_GENERATING (enhanced chosen up front), it then goes to final approval with the
     * enhanced video, or with the basic one and the reason if enhancing failed.
     */
    public void enhance(Long postId) {
        try {
            runEnhance(postId);
        } catch (Exception e) {
            log.error("Enhance crashed for post {}: {}", postId, e.getMessage(), e);
            postRepository.findById(postId).ifPresent(p -> {
                p.setRenderStatus(ENHANCE_FAILED);
                p.setRenderNote("Enhance error: " + e.getMessage());
                if (p.getStatus() == PostStatus.MEDIA_GENERATING) p.setStatus(PostStatus.PENDING_FINAL_APPROVAL);
                postRepository.save(p);
            });
        }
    }

    private void runEnhance(Long postId) {
        ContentPostEntity post = postRepository.findById(postId).orElse(null);
        if (post == null) return;
        Map<String, Object> settings = settingsService.getAllSettings();
        List<ContentSceneEntity> scenes = sceneRepository.findByPostIdOrderBySceneNumberAsc(postId);
        List<ContentSceneEntity> broll = scenes.stream()
                .filter(s -> s.getSceneType() == SceneType.BROLL && s.getMediaUrl() != null && !s.getMediaUrl().isBlank()
                        && !s.getMediaUrl().equals(post.getAvatarVideoUrl()) && s.getStatus() == JobStatus.COMPLETED)
                .sorted(Comparator.comparingInt(ContentSceneEntity::getSceneNumber))
                .toList();
        String script = post.getPresenterScript() != null && !post.getPresenterScript().isBlank()
                ? post.getPresenterScript()
                : scenes.stream().map(ContentSceneEntity::getSpokenText).filter(t -> t != null && !t.isBlank()).collect(Collectors.joining(" "));
        boolean captions = !"false".equalsIgnoreCase(String.valueOf(settings.getOrDefault("subtitlesEnabled", true)));

        log.info("Enhancing reel {} with the render service ({} B-roll clips)...", postId, broll.size());
        RenderServiceClient.Result result = renderServiceClient.render(postId, post.getAvatarVideoUrl(), script, broll, settings, captions);

        // reload: the reel may have been approved, published or edited while the render ran
        ContentPostEntity fresh = postRepository.findById(postId).orElse(null);
        if (fresh == null) return;
        fresh.setRenderJobId(result.jobId());
        boolean generating = fresh.getStatus() == PostStatus.MEDIA_GENERATING;
        if (generating) fresh.setStatus(PostStatus.PENDING_FINAL_APPROVAL); // the basic video is there either way
        if (!result.ok()) {
            fresh.setRenderStatus(ENHANCE_FAILED);
            fresh.setRenderNote(result.error());
            postRepository.save(fresh);
            return;
        }

        int version = renderRepository.findFirstByPostIdOrderByRenderVersionDesc(postId)
                .map(r -> r.getRenderVersion() != null ? r.getRenderVersion() + 1 : 2)
                .orElse(1);
        renderRepository.save(VideoRenderEntity.builder()
                .postId(postId)
                .storageUrl(result.url())
                .durationSeconds(fresh.getTargetDurationSeconds() != null ? fresh.getTargetDurationSeconds().doubleValue() : null)
                .resolution("1080x1920")
                .renderVersion(version)
                .status(JobStatus.COMPLETED)
                .build());

        fresh.setRenderStatus(ENHANCED);
        if (fresh.getStatus() == PostStatus.PENDING_FINAL_APPROVAL) {
            fresh.setFinalRenderUrl(result.url());
            fresh.setThumbnailUrl(result.url());
            fresh.setRenderEngine("remotion");
            fresh.setRenderNote(REAPPROVE_NOTE.equals(fresh.getRenderNote()) ? "Enhanced. It was approved before: approve it again to schedule it." : null);
        } else if (fresh.getStatus() == PostStatus.PUBLISHED) {
            // the Instagram post stays as it is; the enhanced video becomes this reel's video (the old one is a version)
            fresh.setFinalRenderUrl(result.url());
            fresh.setThumbnailUrl(result.url());
            fresh.setRenderEngine("remotion");
            fresh.setRenderNote("Enhanced after publishing. The post on Instagram is unchanged: use Repost to post the enhanced version.");
        } else {
            fresh.setRenderNote("The reel was " + fresh.getStatus().name().toLowerCase().replace('_', ' ')
                    + " before the enhanced version finished, so it kept its video. The enhanced one is saved as render v" + version + ".");
        }
        postRepository.save(fresh);
        log.info("Reel {} enhanced (render v{})", postId, version);
    }
}
