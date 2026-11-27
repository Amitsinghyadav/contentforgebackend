package com.ozo.contentforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.ContentSceneEntity;
import com.ozo.contentforge.model.entity.MediaJobEntity;
import com.ozo.contentforge.model.enums.JobProvider;
import com.ozo.contentforge.model.enums.JobStatus;
import com.ozo.contentforge.repository.BrandProfileRepository;
import com.ozo.contentforge.repository.MediaJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Client for contentforgerender, the Remotion render service that turns the HeyGen avatar video into a branded reel:
 * Claude writes each reel's own motion graphics, captions and sound design.
 *
 * Flow: POST /api/jobs (video URL + script + brand) -> poll GET /api/jobs/{id} -> download /jobs/{id}/final.mp4 -> S3.
 * Used by ReelEnhanceService after the basic FFmpeg reel exists; a failure leaves the basic reel in place.
 */
@Service
public class RenderServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RenderServiceClient.class);
    private static final int MAX_BROLL = 6;

    @Value("${render.engine:}")
    private String engineOverride;

    @Value("${render.service.url:http://localhost:4100}")
    private String baseUrl;

    @Value("${render.service.key:}")
    private String serviceKey;

    @Value("${render.service.timeout-minutes:25}")
    private int timeoutMinutes;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final BrandProfileRepository brandRepository;
    private final MediaJobRepository mediaJobRepository;

    public RenderServiceClient(ObjectMapper objectMapper, StorageService storageService, BrandProfileRepository brandRepository,
                               MediaJobRepository mediaJobRepository) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofMinutes(5).toMillis()); // job submit downloads the video first
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.brandRepository = brandRepository;
        this.mediaJobRepository = mediaJobRepository;
    }

    /** Auto-enhance: "remotion" enhances every reel after its basic version; RENDER_ENGINE in the environment wins over the setting. */
    public boolean isAutoEnhance(Map<String, Object> settings) {
        String engine = (engineOverride != null && !engineOverride.isBlank())
                ? engineOverride
                : String.valueOf(settings.getOrDefault("renderEngine", "ffmpeg"));
        return "remotion".equalsIgnoreCase(engine.trim());
    }

    /** Outcome of one enhance: the S3 URL on success, otherwise a reason a person can act on. */
    public record Result(String url, String jobId, String error) {
        public boolean ok() {
            return url != null;
        }
    }

    /**
     * Makes the enhanced reel and uploads it to S3. Logs a RENDER_SERVICE row in the post's tasks log, whose operation
     * shows the live step (EDIT_REEL · transcribe, EDIT_REEL · edit, ...). Never throws.
     */
    public Result render(Long postId, String avatarVideoUrl, String spokenScript,
                         List<ContentSceneEntity> brollScenes, Map<String, Object> settings, boolean captions) {
        MediaJobEntity job = mediaJobRepository.save(MediaJobEntity.builder()
                .postId(postId)
                .provider(JobProvider.RENDER_SERVICE)
                .operation("EDIT_REEL · starting")
                .status(JobStatus.IN_PROGRESS)
                .startedAt(LocalDateTime.now())
                .build());
        String jobId = null;
        try {
            jobId = submit(postId, avatarVideoUrl, spokenScript, brollScenes, settings, captions);
            job.setProviderTaskId(jobId);
            mediaJobRepository.save(job);
            log.info("Render service job {} started for post {}", jobId, postId);

            String failure = waitForJob(jobId, postId, job);
            if (failure != null) return fail(job, jobId, failure);

            byte[] video = restTemplate.exchange(baseUrl + "/jobs/" + jobId + "/final.mp4", HttpMethod.GET,
                    new HttpEntity<>(authHeaders()), byte[].class).getBody();
            if (video == null || video.length == 0) return fail(job, jobId, "The render service finished but returned an empty video");
            String s3Key = "media/renders/" + UUID.randomUUID() + ".mp4";
            storageService.putS3Object(s3Key, video, "video/mp4");
            log.info("Render service reel for post {} uploaded to S3: {} ({} KB)", postId, s3Key, video.length / 1024);

            job.setOperation("EDIT_REEL");
            job.setStatus(JobStatus.COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            mediaJobRepository.save(job);
            return new Result(storageService.buildS3Url(s3Key), jobId, null);
        } catch (ResourceAccessException e) {
            return fail(job, jobId, "The render service isn't reachable at " + baseUrl + " (start it with npm start in contentforgerender)");
        } catch (Exception e) {
            return fail(job, jobId, "Render service error: " + e.getMessage());
        }
    }

    private Result fail(MediaJobEntity job, String jobId, String reason) {
        log.warn("Enhance failed for post {}: {}", job.getPostId(), reason);
        job.setStatus(JobStatus.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        try {
            job.setErrorJson(objectMapper.writeValueAsString(Map.of("reason", reason)));
        } catch (Exception ignored) {
        }
        mediaJobRepository.save(job);
        return new Result(null, jobId, reason);
    }

    private String submit(Long postId, String avatarVideoUrl, String spokenScript, List<ContentSceneEntity> brollScenes,
                          Map<String, Object> settings, boolean captions) throws Exception {
        // Dashboard Settings win; the default brand profile only fills gaps
        BrandProfileEntity brand = brandRepository.findByIsDefaultTrue().orElse(null);
        List<String> profileColors = brandColors(brand);
        String primary = firstNonBlank(hex(settings.get("brandPrimaryColor")), profileColors.size() > 0 ? profileColors.get(0) : null);
        String secondary = firstNonBlank(hex(settings.get("brandSecondaryColor")), profileColors.size() > 1 ? profileColors.get(1) : null);
        String textColor = firstNonBlank(hex(settings.get("brandTextColor")), profileColors.size() > 2 ? profileColors.get(2) : null);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // the render service downloads these itself, so S3 objects get a short-lived presigned link
        form.add("videoUrl", storageService.getPresignedUrl(avatarVideoUrl, Duration.ofHours(2)));
        form.add("captions", String.valueOf(captions));
        // "Caption language and script" setting: Hindi = Devanagari captions; Hinglish / English = Roman letters
        String captionLanguage = firstNonBlank(str(settings.get("captionLanguage")), "Hinglish");
        form.add("captionScript", "Hindi".equalsIgnoreCase(captionLanguage) ? "native" : "roman");
        form.add("language", "English".equalsIgnoreCase(captionLanguage) ? "en" : "hi"); // spoken language for Whisper
        form.add("brandName", firstNonBlank(str(settings.get("brandName")), brand != null ? brand.getBrandName() : null, "My Brand"));
        if (primary != null) form.add("primary", primary);
        if (secondary != null) form.add("secondary", secondary);
        if (textColor != null) form.add("textColor", textColor);
        String handle = firstNonBlank(str(settings.get("brandInstagramHandle")), str(settings.get("instagramUsername")));
        if (handle != null) form.add("handle", "@" + handle.replaceFirst("^@", ""));

        String logoUrl = firstNonBlank(str(settings.get("brandLogoUrl")), brand != null ? brand.getLogoUrl() : null);
        if (logoUrl != null) form.add("logoUrl", storageService.getPresignedUrl(logoUrl, Duration.ofHours(2)));
        if (spokenScript != null && !spokenScript.isBlank()) form.add("script", spokenScript);

        // brand contact details: the editor shows them when the speaker mentions them (e.g. a call or booking ask)
        String cta = firstNonBlank(str(settings.get("cta")), brand != null ? brand.getCta() : null);
        String website = firstNonBlank(str(settings.get("website")), brand != null ? brand.getWebsite() : null);
        String phone = firstNonBlank(str(settings.get("brandPhone")));
        StringBuilder info = new StringBuilder();
        if (cta != null) info.append("Call to action: ").append(cta).append(". ");
        if (phone != null) info.append("Phone number: ").append(phone).append(". ");
        if (website != null) info.append("Website: ").append(website).append(". ");
        if (info.length() > 0) form.add("brandInfo", info.substring(0, Math.min(info.length(), 500)).trim());

        // Runway B-roll clips, described by their prompt so the editor can place them
        List<String> descriptions = new ArrayList<>();
        int n = 0;
        for (ContentSceneEntity scene : brollScenes) {
            if (n >= MAX_BROLL) break;
            try {
                byte[] clip = restTemplate.getForObject(java.net.URI.create(storageService.getPresignedUrl(scene.getMediaUrl(), Duration.ofHours(1))), byte[].class);
                if (clip == null || clip.length == 0) continue;
                final String name = "broll-" + scene.getSceneNumber() + ".mp4";
                form.add("broll", new ByteArrayResource(clip) {
                    @Override
                    public String getFilename() {
                        return name;
                    }
                });
                descriptions.add(scene.getPrompt() != null ? scene.getPrompt().replace('\n', ' ') : "");
                n++;
            } catch (Exception e) {
                log.warn("Skipping B-roll scene {} for post {}: {}", scene.getSceneNumber(), postId, e.getMessage());
            }
        }
        if (!descriptions.isEmpty()) form.add("brollNotes", String.join("\n", descriptions));

        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> res = restTemplate.postForEntity(baseUrl + "/api/jobs", new HttpEntity<>(form, headers), String.class);
        JsonNode body = objectMapper.readTree(res.getBody());
        if (!body.hasNonNull("id")) throw new IllegalStateException("Render service did not return a job id: " + res.getBody());
        return body.get("id").asText();
    }

    /**
     * Renders one 1080x1920 story frame in the brand's style and returns the PNG. {@code frame} carries type,
     * headline, body, sticker (shown as a placeholder area), backgroundUrl and the brand fields.
     */
    public byte[] renderStoryFrame(Map<String, Object> frame) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.IMAGE_PNG));
        try {
            byte[] png = restTemplate.exchange(baseUrl + "/api/stills/story", HttpMethod.POST, new HttpEntity<>(frame, headers), byte[].class).getBody();
            if (png == null || png.length == 0) throw new IllegalStateException("The render service returned an empty image");
            return png;
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("The render service isn't reachable at " + baseUrl + " (start it with npm start in contentforgerender)");
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            throw new IllegalStateException("The render service couldn't draw the story frame: " + e.getResponseBodyAsString());
        }
    }

    /** The brand fields every render needs: name, colours, handle and a downloadable logo link. */
    public Map<String, Object> brandStyle(Map<String, Object> settings) {
        BrandProfileEntity brand = brandRepository.findByIsDefaultTrue().orElse(null);
        List<String> profileColors = brandColors(brand);
        Map<String, Object> style = new HashMap<>();
        style.put("brandName", firstNonBlank(str(settings.get("brandName")), brand != null ? brand.getBrandName() : null, ""));
        style.put("primary", firstNonBlank(hex(settings.get("brandPrimaryColor")), profileColors.size() > 0 ? profileColors.get(0) : null, "#0074BC"));
        style.put("secondary", firstNonBlank(hex(settings.get("brandSecondaryColor")), profileColors.size() > 1 ? profileColors.get(1) : null, "#2BB673"));
        style.put("textColor", firstNonBlank(hex(settings.get("brandTextColor")), profileColors.size() > 2 ? profileColors.get(2) : null, "#231F20"));
        String handle = firstNonBlank(str(settings.get("brandInstagramHandle")), str(settings.get("instagramUsername")));
        if (handle != null) style.put("handle", "@" + handle.replaceFirst("^@", ""));
        String logoUrl = firstNonBlank(str(settings.get("brandLogoUrl")), brand != null ? brand.getLogoUrl() : null);
        if (logoUrl != null) style.put("logoUrl", storageService.getPresignedUrl(logoUrl, Duration.ofHours(1)));
        return style;
    }

    /** Polls every 15 s. Returns null when the job is done, otherwise why it failed. Each new step is shown in the tasks log. */
    private String waitForJob(String jobId, Long postId, MediaJobEntity tracked) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(timeoutMinutes).toMillis();
        String lastStep = "";
        int unreachable = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            try {
                String raw = restTemplate.exchange(baseUrl + "/api/jobs/" + jobId, HttpMethod.GET,
                        new HttpEntity<>(authHeaders()), String.class).getBody();
                unreachable = 0;
                JsonNode job = objectMapper.readTree(raw);
                String status = job.path("status").asText();
                if ("done".equals(status)) return null;
                if ("failed".equals(status)) return friendly(job.path("error").asText("unknown error"));
                for (JsonNode step : job.path("steps")) {
                    String name = step.path("name").asText();
                    if ("running".equals(step.path("status").asText()) && !name.equals(lastStep)) {
                        lastStep = name;
                        log.info("Render service job {} for post {}: {}", jobId, postId, name);
                        tracked.setOperation("EDIT_REEL · " + name);
                        mediaJobRepository.save(tracked);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read render service job {}: {}", jobId, e.getMessage());
                if (++unreachable >= 8) return "Lost contact with the render service for 2 minutes (was it stopped?)";
            }
        }
        return "The render service didn't finish within " + timeoutMinutes + " minutes";
    }

    /** Turns the render service's error into something a reviewer can act on. */
    private static String friendly(String error) {
        String e = error.toLowerCase();
        if (e.contains("credit balance") || e.contains("billing")) return "The render service's Anthropic account has no credits left (add credits, then enhance again)";
        if (e.contains("authentication_error") || e.contains("invalid x-api-key") || (e.contains("401") && e.contains("api key")))
            return "The render service's Anthropic key is invalid (check ANTHROPIC_API_KEY in contentforgerender/.env)";
        if (e.contains("overloaded") || e.contains("529") || e.contains("rate_limit")) return "Claude was busy or rate-limited (enhance again in a few minutes)";
        return "The render service failed: " + (error.length() > 300 ? error.substring(0, 300) + "…" : error);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        if (serviceKey != null && !serviceKey.isBlank()) h.set("x-render-key", serviceKey);
        return h;
    }

    /** colorsJson looks like ["#0052FF", "#00D26A", "#1E293B"]: primary, secondary, text. */
    private List<String> brandColors(BrandProfileEntity brand) {
        if (brand == null || brand.getColorsJson() == null || brand.getColorsJson().isBlank()) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (JsonNode c : objectMapper.readTree(brand.getColorsJson())) {
                String hex = c.asText().trim();
                if (hex.matches("#[0-9a-fA-F]{6}")) out.add(hex);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String hex(Object o) {
        String v = str(o);
        return v != null && v.trim().matches("#[0-9a-fA-F]{6}") ? v.trim() : null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
