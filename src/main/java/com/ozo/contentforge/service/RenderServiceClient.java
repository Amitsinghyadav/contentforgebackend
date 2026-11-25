package com.ozo.contentforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.ContentSceneEntity;
import com.ozo.contentforge.repository.BrandProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * Client for contentforgerender, the Remotion render service that turns the HeyGen avatar video into a branded reel
 * with AI-directed motion graphics, captions and sound design.
 *
 * Flow: POST /api/jobs (video URL + script + brand) -> poll GET /api/jobs/{id} -> download /jobs/{id}/final.mp4 -> S3.
 * Any failure returns empty, and the caller falls back to the FFmpeg composite.
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

    @Value("${render.service.style:clean-brand}")
    private String style;

    @Value("${render.service.timeout-minutes:25}")
    private int timeoutMinutes;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final StorageService storageService;
    private final BrandProfileRepository brandRepository;

    public RenderServiceClient(ObjectMapper objectMapper, StorageService storageService, BrandProfileRepository brandRepository) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout((int) Duration.ofMinutes(5).toMillis()); // job submit downloads the video first
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.brandRepository = brandRepository;
    }

    /** "remotion" when the render service should make the reel; RENDER_ENGINE in the environment wins over the setting. */
    public boolean isEnabled(Map<String, Object> settings) {
        String engine = (engineOverride != null && !engineOverride.isBlank())
                ? engineOverride
                : String.valueOf(settings.getOrDefault("renderEngine", "ffmpeg"));
        return "remotion".equalsIgnoreCase(engine.trim());
    }

    /**
     * Renders the reel and uploads it to S3. Returns the S3 URL, or empty if anything fails (caller uses FFmpeg).
     */
    public Optional<String> render(Long postId, String avatarVideoUrl, String spokenScript,
                                   List<ContentSceneEntity> brollScenes, Map<String, Object> settings, boolean captions) {
        try {
            String jobId = submit(postId, avatarVideoUrl, spokenScript, brollScenes, settings, captions);
            log.info("Render service job {} started for post {}", jobId, postId);
            if (!waitForJob(jobId, postId)) return Optional.empty();

            byte[] video = restTemplate.exchange(baseUrl + "/jobs/" + jobId + "/final.mp4", HttpMethod.GET,
                    new HttpEntity<>(authHeaders()), byte[].class).getBody();
            if (video == null || video.length == 0) {
                log.warn("Render service job {} finished but returned an empty video", jobId);
                return Optional.empty();
            }
            String s3Key = "media/renders/" + UUID.randomUUID() + ".mp4";
            storageService.putS3Object(s3Key, video, "video/mp4");
            log.info("Render service reel for post {} uploaded to S3: {} ({} KB)", postId, s3Key, video.length / 1024);
            return Optional.of(storageService.buildS3Url(s3Key));
        } catch (Exception e) {
            log.warn("Render service failed for post {}: {}. Falling back to FFmpeg.", postId, e.getMessage());
            return Optional.empty();
        }
    }

    private String submit(Long postId, String avatarVideoUrl, String spokenScript, List<ContentSceneEntity> brollScenes,
                          Map<String, Object> settings, boolean captions) throws Exception {
        BrandProfileEntity brand = brandRepository.findByIsDefaultTrue().orElse(null);
        List<String> colors = brandColors(brand);

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        // the render service downloads these itself, so S3 objects get a short-lived presigned link
        form.add("videoUrl", storageService.getPresignedUrl(avatarVideoUrl, Duration.ofHours(2)));
        form.add("style", style);
        form.add("layout", "vertical"); // HeyGen renders 9:16
        form.add("captions", String.valueOf(captions));
        form.add("brandName", firstNonBlank(brand != null ? brand.getBrandName() : null, str(settings.get("brandName")), "My Brand"));
        if (colors.size() > 0) form.add("primary", colors.get(0));
        if (colors.size() > 1) form.add("secondary", colors.get(1));
        if (colors.size() > 2) form.add("textColor", colors.get(2));

        String logoUrl = firstNonBlank(str(settings.get("brandLogoUrl")), brand != null ? brand.getLogoUrl() : null);
        if (logoUrl != null) form.add("logoUrl", storageService.getPresignedUrl(logoUrl, Duration.ofHours(2)));
        if (spokenScript != null && !spokenScript.isBlank()) form.add("script", spokenScript);

        // the director treats notes as requirements: the brand's call to action and website must appear
        String cta = firstNonBlank(brand != null ? brand.getCta() : null, str(settings.get("cta")));
        String website = firstNonBlank(brand != null ? brand.getWebsite() : null, str(settings.get("website")));
        StringBuilder notes = new StringBuilder();
        if (cta != null) notes.append("Call to action: ").append(cta).append(". ");
        if (website != null) notes.append("Website: ").append(website).append(". ");
        if (notes.length() > 0) form.add("notes", notes.substring(0, Math.min(notes.length(), 500)).trim());

        // Runway B-roll clips, described by their prompt so the director can place them
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

    /** Polls every 15 s until the job is done, failed or the timeout passes. */
    private boolean waitForJob(String jobId, Long postId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(timeoutMinutes).toMillis();
        String lastStep = "";
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000);
            try {
                String raw = restTemplate.exchange(baseUrl + "/api/jobs/" + jobId, HttpMethod.GET,
                        new HttpEntity<>(authHeaders()), String.class).getBody();
                JsonNode job = objectMapper.readTree(raw);
                String status = job.path("status").asText();
                if ("done".equals(status)) return true;
                if ("failed".equals(status)) {
                    log.warn("Render service job {} for post {} failed: {}", jobId, postId, job.path("error").asText());
                    return false;
                }
                for (JsonNode step : job.path("steps")) {
                    if ("running".equals(step.path("status").asText()) && !step.path("name").asText().equals(lastStep)) {
                        lastStep = step.path("name").asText();
                        log.info("Render service job {} for post {}: {}", jobId, postId, lastStep);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not read render service job {}: {}", jobId, e.getMessage());
            }
        }
        log.warn("Render service job {} for post {} did not finish within {} min", jobId, postId, timeoutMinutes);
        return false;
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
