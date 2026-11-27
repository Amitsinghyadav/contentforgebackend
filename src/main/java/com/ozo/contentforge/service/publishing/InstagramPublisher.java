package com.ozo.contentforge.service.publishing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the Instagram Graph API content-publishing flow: create a media container, wait until Instagram has
 * processed it, then publish it. Every failure becomes a {@link PublishException} carrying Instagram's own message.
 */
@Component
public class InstagramPublisher {

    private static final Logger log = LoggerFactory.getLogger(InstagramPublisher.class);
    private static final Duration CONTAINER_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    /** Graph API error codes that are worth retrying later (rate limits, temporary outages). */
    private static final List<Integer> RETRYABLE_CODES = List.of(1, 2, 4, 17, 32, 341, 368, 9007);

    private final RestTemplate http;
    private final ObjectMapper objectMapper;

    @Value("${meta.instagram.graph.api.version:v21.0}")
    private String graphVersion;

    public InstagramPublisher(ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(90).toMillis());
        this.http = new RestTemplate(factory);
        this.objectMapper = objectMapper;
    }

    /** The account and token a brand publishes with. */
    public record Account(String instagramAccountId, String accessToken) {
    }

    public record Published(String mediaId, String containerId, String permalink) {
    }

    /** True when Instagram's rolling 24-hour publishing limit for this account is used up. */
    public boolean isAtPublishingLimit(Account account) {
        try {
            JsonNode res = get(account, "/" + account.instagramAccountId() + "/content_publishing_limit",
                    Map.of("fields", "quota_usage,config"));
            JsonNode data = res.path("data").path(0);
            int used = data.path("quota_usage").asInt(0);
            int total = data.path("config").path("quota_total").asInt(Integer.MAX_VALUE);
            return used >= total;
        } catch (PublishException e) {
            log.warn("Could not read the publishing limit: {}", e.getMessage());
            return false;
        }
    }

    public Published publishReel(Account account, String videoUrl, String caption, String coverUrl) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("media_type", "REELS");
        body.add("video_url", videoUrl);
        body.add("caption", caption != null ? caption : "");
        body.add("share_to_feed", "true");
        if (coverUrl != null) body.add("cover_url", coverUrl);
        return createAndPublish(account, body);
    }

    public Published publishImage(Account account, String imageUrl, String caption) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("image_url", imageUrl);
        body.add("caption", caption != null ? caption : "");
        return createAndPublish(account, body);
    }

    /** 2 to 10 images in one post. */
    public Published publishCarousel(Account account, List<String> imageUrls, String caption) {
        if (imageUrls.size() < 2 || imageUrls.size() > 10) {
            throw new PublishException("A carousel needs 2 to 10 images (this one has " + imageUrls.size() + ")", false);
        }
        StringBuilder children = new StringBuilder();
        for (String url : imageUrls) {
            MultiValueMap<String, String> item = new LinkedMultiValueMap<>();
            item.add("image_url", url);
            item.add("is_carousel_item", "true");
            String childId = createContainer(account, item);
            waitUntilReady(account, childId);
            if (!children.isEmpty()) children.append(',');
            children.append(childId);
        }
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("media_type", "CAROUSEL");
        body.add("children", children.toString());
        body.add("caption", caption != null ? caption : "");
        return createAndPublish(account, body);
    }

    /** One story frame: an image, or a video when {@code isVideo}. */
    public Published publishStoryFrame(Account account, String mediaUrl, boolean isVideo) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("media_type", "STORIES");
        body.add(isVideo ? "video_url" : "image_url", mediaUrl);
        return createAndPublish(account, body);
    }

    // =========================================================================

    private Published createAndPublish(Account account, MultiValueMap<String, String> containerBody) {
        String containerId = createContainer(account, containerBody);
        waitUntilReady(account, containerId);

        MultiValueMap<String, String> publishBody = new LinkedMultiValueMap<>();
        publishBody.add("creation_id", containerId);
        JsonNode published = post(account, "/" + account.instagramAccountId() + "/media_publish", publishBody);
        String mediaId = published.path("id").asText(null);
        if (mediaId == null) throw new PublishException("Instagram didn't return the published post's id", true);

        String permalink = null;
        try {
            permalink = get(account, "/" + mediaId, Map.of("fields", "permalink")).path("permalink").asText(null);
        } catch (PublishException e) {
            log.debug("Published {} but could not read its permalink: {}", mediaId, e.getMessage());
        }
        return new Published(mediaId, containerId, permalink);
    }

    private String createContainer(Account account, MultiValueMap<String, String> body) {
        JsonNode res = post(account, "/" + account.instagramAccountId() + "/media", body);
        String id = res.path("id").asText(null);
        if (id == null) throw new PublishException("Instagram didn't create the media container", true);
        return id;
    }

    /** Instagram downloads and processes the media before it can be published (videos take longest). */
    private void waitUntilReady(Account account, String containerId) {
        long deadline = System.currentTimeMillis() + CONTAINER_TIMEOUT.toMillis();
        while (true) {
            JsonNode res = get(account, "/" + containerId, Map.of("fields", "status_code,status"));
            String code = res.path("status_code").asText("");
            switch (code) {
                case "FINISHED", "PUBLISHED" -> {
                    return;
                }
                case "ERROR" -> throw new PublishException("Instagram couldn't process the media: "
                        + res.path("status").asText("unknown reason"), false);
                case "EXPIRED" -> throw new PublishException("The media container expired before publishing", true);
                default -> {
                    // IN_PROGRESS (or empty for images, which are usually ready at once)
                    if (code.isEmpty()) return;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new PublishException("Instagram was still processing the media after 5 minutes", true);
            }
            sleep(POLL_INTERVAL);
        }
    }

    private JsonNode post(Account account, String path, MultiValueMap<String, String> body) {
        body.set("access_token", account.accessToken());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return call(() -> http.postForEntity(url(path), new HttpEntity<>(body, headers), String.class).getBody());
    }

    private JsonNode get(Account account, String path, Map<String, String> params) {
        StringBuilder query = new StringBuilder(url(path)).append("?access_token={token}");
        params.keySet().forEach(k -> query.append('&').append(k).append("={").append(k).append('}'));
        Map<String, String> vars = new java.util.HashMap<>(params);
        vars.put("token", account.accessToken());
        return call(() -> http.getForObject(query.toString(), String.class, vars));
    }

    private interface GraphCall {
        String run();
    }

    private JsonNode call(GraphCall request) {
        try {
            String raw = request.run();
            return objectMapper.readTree(raw != null ? raw : "{}");
        } catch (HttpStatusCodeException e) {
            throw fromGraphError(e);
        } catch (ResourceAccessException e) {
            throw new PublishException("Couldn't reach Instagram: " + e.getMessage(), true);
        } catch (PublishException e) {
            throw e;
        } catch (Exception e) {
            throw new PublishException("Unexpected Instagram response: " + e.getMessage(), true);
        }
    }

    private PublishException fromGraphError(HttpStatusCodeException e) {
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
            int code = error.path("code").asInt(-1);
            String message = error.path("error_user_msg").asText(error.path("message").asText(e.getStatusText()));
            if (code == 190) {
                return new PublishException("The brand's Instagram connection has expired. Reconnect it in Settings. (" + message + ")", false);
            }
            boolean retryable = RETRYABLE_CODES.contains(code) || e.getStatusCode().is5xxServerError();
            return new PublishException("Instagram error " + code + ": " + message, retryable);
        } catch (Exception parse) {
            return new PublishException("Instagram error " + e.getStatusCode().value() + ": " + e.getStatusText(),
                    e.getStatusCode().is5xxServerError());
        }
    }

    private String url(String path) {
        return "https://graph.facebook.com/" + graphVersion + path;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new PublishException("Publishing was interrupted", true);
        }
    }
}
