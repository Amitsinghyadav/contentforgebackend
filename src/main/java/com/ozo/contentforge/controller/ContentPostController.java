package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.*;
import com.ozo.contentforge.model.enums.*;
import com.ozo.contentforge.service.ContentPostService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/posts")
@CrossOrigin
public class ContentPostController {

    private final ContentPostService postService;

    public ContentPostController(ContentPostService postService) {
        this.postService = postService;
    }

    @GetMapping
    public ResponseEntity<List<ContentPostEntity>> getPostsByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(postService.getPostsForDate(date));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPostDetails(@PathVariable("id") Long id) {
        ContentPostEntity post = postService.getPostById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));

        Map<String, Object> details = new HashMap<>();
        details.put("post", post);
        details.put("scenes", postService.getScenesForPost(id));
        details.put("slides", postService.getSlidesForPost(id));
        details.put("mediaJobs", postService.getMediaJobsForPost(id));
        details.put("approvals", postService.getApprovalsForPost(id));

        return ResponseEntity.ok(details);
    }

    @GetMapping("/{id}/pipeline")
    public ResponseEntity<Map<String, Object>> getPipelineStatus(@PathVariable("id") Long id) {
        ContentPostEntity post = postService.getPostById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));

        Map<String, Object> pipeline = new HashMap<>();
        pipeline.put("postId", id);
        pipeline.put("overallStatus", post.getStatus());
        pipeline.put("contentType", post.getContentType());
        pipeline.put("scenes", postService.getScenesForPost(id));
        pipeline.put("slides", postService.getSlidesForPost(id));
        pipeline.put("mediaJobs", postService.getMediaJobsForPost(id));
        pipeline.put("finalRenderUrl", post.getFinalRenderUrl());
        pipeline.put("voiceAudioUrl", post.getVoiceAudioUrl());
        pipeline.put("scheduledAt", post.getScheduledAt());
        pipeline.put("publishedAt", post.getPublishedAt());
        pipeline.put("permalink", post.getPublishedPermalink());

        return ResponseEntity.ok(pipeline);
    }

    @PostMapping("/generate")
    public ResponseEntity<?> createCustomPost(@RequestBody Map<String, Object> req) {
        try {
            LocalDate date = req.containsKey("date") ? LocalDate.parse((String) req.get("date")) : LocalDate.now();
            String topic = (String) req.get("topic");
            if (topic == null || topic.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Topic is required to generate custom post."));
            }
            ContentType contentType = ContentType.valueOf((String) req.getOrDefault("contentType", "REEL"));
            PostMode mode = PostMode.valueOf((String) req.getOrDefault("mode", "MODE_A"));
            AvatarEngine engine = AvatarEngine.valueOf((String) req.getOrDefault("avatarEngine", "AVATAR_IV"));
            int duration = req.containsKey("durationSeconds") ? ((Number) req.get("durationSeconds")).intValue() : 45;

            ContentPostEntity created = postService.createAndPlanPost(date, null, null, contentType, topic, mode, engine, duration);
            return ResponseEntity.ok(created);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Post generation failed"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deletePost(@PathVariable("id") Long id) {
        postService.deletePost(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    @PostMapping("/{id}/approve-text")
    public ResponseEntity<ContentPostEntity> approveText(@PathVariable("id") Long id,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        String script = body != null && body.get("presenterScript") != null ? body.get("presenterScript").toString() : null;
        String voiceover = body != null && body.get("voiceoverScript") != null ? body.get("voiceoverScript").toString() : null;
        String caption = body != null && body.get("caption") != null ? body.get("caption").toString() : null;
        String cta = body != null && body.get("cta") != null ? body.get("cta").toString() : null;
        String hook = body != null && body.get("hook") != null ? body.get("hook").toString() : null;
        String reviewer = body != null && body.get("reviewerId") != null ? body.get("reviewerId").toString() : "admin";

        List<Map<String, Object>> slides = null;
        if (body != null && body.get("slides") instanceof List<?> list) {
            slides = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> castMap = (Map<String, Object>) m;
                    slides.add(castMap);
                }
            }
        }

        return ResponseEntity.ok(postService.approveText(id, script, voiceover, caption, cta, reviewer, hook, slides));
    }

    @PostMapping("/{id}/retry-media")
    public ResponseEntity<ContentPostEntity> retryMediaGeneration(@PathVariable("id") Long id) {
        return ResponseEntity.ok(postService.retryMediaGeneration(id));
    }

    @PostMapping("/{id}/regenerate-scene")
    public ResponseEntity<ContentSceneEntity> regenerateScene(@PathVariable("id") Long id,
                                                              @RequestBody Map<String, Object> body) throws Exception {
        int sceneNumber = ((Number) body.get("sceneNumber")).intValue();
        String prompt = (String) body.get("prompt");
        return ResponseEntity.ok(postService.regenerateScene(id, sceneNumber, prompt));
    }

    @PostMapping("/{id}/remerge")
    public ResponseEntity<ContentPostEntity> remergeVideo(@PathVariable("id") Long id) throws Exception {
        return ResponseEntity.ok(postService.remergeVideo(id));
    }

    @PutMapping("/{id}/scenes/{sceneNumber}")
    public ResponseEntity<ContentSceneEntity> updateSceneTiming(
            @PathVariable("id") Long id,
            @PathVariable("sceneNumber") Integer sceneNumber,
            @RequestBody Map<String, Object> body) {
        Integer startSeconds = body.containsKey("startTimeSeconds") && body.get("startTimeSeconds") != null
                ? ((Number) body.get("startTimeSeconds")).intValue() : null;
        Integer durationSeconds = body.containsKey("durationSeconds") && body.get("durationSeconds") != null
                ? ((Number) body.get("durationSeconds")).intValue() : null;
        String prompt = body.containsKey("prompt") ? (String) body.get("prompt") : null;
        return ResponseEntity.ok(postService.updateSceneTiming(id, sceneNumber, startSeconds, durationSeconds, prompt));
    }

    @PostMapping("/{id}/sync-scene-timings")
    public ResponseEntity<List<ContentSceneEntity>> syncSceneTimings(@PathVariable("id") Long id) {
        return ResponseEntity.ok(postService.syncSceneTimings(id));
    }

    @PostMapping("/{id}/approve-media")
    public ResponseEntity<ContentPostEntity> approveMedia(@PathVariable("id") Long id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        LocalDateTime scheduledAt = null;
        if (body != null && body.containsKey("scheduledAt")) {
            scheduledAt = LocalDateTime.parse((String) body.get("scheduledAt"));
        }
        String reviewer = (body != null && body.containsKey("reviewerId")) ? (String) body.get("reviewerId") : "admin";
        return ResponseEntity.ok(postService.approveMedia(id, scheduledAt, reviewer));
    }

    @PostMapping("/{id}/reject-media")
    public ResponseEntity<ContentPostEntity> rejectMedia(@PathVariable("id") Long id,
                                                         @RequestBody Map<String, String> body) {
        String comments = body.getOrDefault("comments", "Revising visuals and pacing");
        String reviewer = body.getOrDefault("reviewerId", "admin");
        return ResponseEntity.ok(postService.rejectMedia(id, comments, reviewer));
    }

    @PostMapping("/{id}/publish-now")
    public ResponseEntity<ContentPostEntity> publishNow(@PathVariable("id") Long id) {
        return ResponseEntity.ok(postService.publishNow(id));
    }

    @PutMapping("/{id}/slides/{slideNumber}")
    public ResponseEntity<CarouselSlideEntity> updateSlide(@PathVariable("id") Long id,
                                                           @PathVariable("slideNumber") int slideNumber,
                                                           @RequestBody Map<String, String> body) throws Exception {
        String title = body.get("title");
        String content = body.get("bodyContent");
        return ResponseEntity.ok(postService.updateSlide(id, slideNumber, title, content));
    }

    @PostMapping("/{id}/slides/{slideNumber}/regenerate")
    public ResponseEntity<CarouselSlideEntity> regenerateSlide(@PathVariable("id") Long id,
                                                               @PathVariable("slideNumber") int slideNumber) throws Exception {
        return ResponseEntity.ok(postService.updateSlide(id, slideNumber, null, null));
    }
}
