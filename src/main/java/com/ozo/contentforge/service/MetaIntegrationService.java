package com.ozo.contentforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

@Service
public class MetaIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(MetaIntegrationService.class);
    private static final String DEFAULT_GRAPH_VERSION = "v21.0";

    private final MasterSettingsService masterSettingsService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public MetaIntegrationService(MasterSettingsService masterSettingsService,
                                  StorageService storageService,
                                  ObjectMapper objectMapper) {
        this.masterSettingsService = masterSettingsService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /**
     * Exchanges OAuth authorization code for an Access Token and automatically inspects accounts.
     */
    public Map<String, Object> exchangeOAuthCode(String code, String redirectUri, String appId, String appSecret) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OAuth code is required");
        }
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("The Meta app id and secret aren't configured on the server (meta.app.id, meta.app.secret)");
        }

        try {
            String tokenUrl = String.format(
                    "https://graph.facebook.com/%s/oauth/access_token?client_id=%s&redirect_uri=%s&client_secret=%s&code=%s",
                    DEFAULT_GRAPH_VERSION, appId.trim(), redirectUri != null ? redirectUri.trim() : "", appSecret != null ? appSecret.trim() : "", code.trim()
            );
            ResponseEntity<String> response = restTemplate.getForEntity(tokenUrl, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String shortLived = json.path("access_token").asText();
                return inspectToken(exchangeForLongLivedToken(shortLived, appId.trim(), appSecret.trim()));
            }
        } catch (Exception e) {
            log.error("Failed to exchange Meta OAuth code: {}", e.getMessage());
            throw new RuntimeException("Meta OAuth exchange error: " + e.getMessage(), e);
        }
        throw new RuntimeException("Meta did not return an access token for the provided OAuth code.");
    }

    /**
     * A user token from the OAuth code lasts about an hour; the long-lived one lasts about 60 days, and Page tokens
     * read with it don't expire, so a brand stays connected.
     */
    private String exchangeForLongLivedToken(String shortLivedToken, String appId, String appSecret) {
        try {
            String url = String.format(
                    "https://graph.facebook.com/%s/oauth/access_token?grant_type=fb_exchange_token&client_id={appId}&client_secret={secret}&fb_exchange_token={token}",
                    DEFAULT_GRAPH_VERSION);
            String body = restTemplate.getForObject(url, String.class, Map.of("appId", appId, "secret", appSecret, "token", shortLivedToken));
            String longLived = objectMapper.readTree(body).path("access_token").asText(null);
            return longLived != null ? longLived : shortLivedToken;
        } catch (Exception e) {
            log.warn("Could not get a long-lived Meta token, using the short-lived one: {}", e.getMessage());
            return shortLivedToken;
        }
    }

    /**
     * Checks the brand's stored connection still works and records the result in its settings
     * (metaTokenStatus VALID or INVALID), so the dashboard can warn the team before a publish fails.
     */
    public Map<String, Object> checkConnectionHealth() {
        Map<String, Object> s = masterSettingsService.getAllSettings();
        String igId = (String) s.get("instagramAccountId");
        String token = (String) s.get("facebookPageAccessToken");
        if (!Boolean.TRUE.equals(s.get("metaConnected")) || igId == null || igId.isBlank() || token == null || token.isBlank()) {
            return getConnectionStatus();
        }
        String status;
        String error = "";
        try {
            restTemplate.getForObject(String.format("https://graph.facebook.com/%s/%s?fields=username&access_token={token}",
                    DEFAULT_GRAPH_VERSION, igId), String.class, Map.of("token", token));
            status = "VALID";
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            status = e.getStatusCode().is4xxClientError() ? "INVALID" : "UNKNOWN";
            error = e.getResponseBodyAsString();
        } catch (Exception e) {
            status = "UNKNOWN"; // network trouble says nothing about the token
            error = e.getMessage();
        }
        s.put("metaTokenStatus", status);
        s.put("metaTokenCheckedAt", java.time.LocalDateTime.now().toString());
        s.put("metaTokenError", error != null && error.length() > 500 ? error.substring(0, 500) : error);
        masterSettingsService.updateSettings(s);
        return getConnectionStatus();
    }

    /**
     * Inspects a Meta User or Page Access Token via Meta Graph API.
     * Returns the user identity, all managed Facebook Pages, and any connected Instagram Business Accounts.
     */
    public Map<String, Object> inspectToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Access token cannot be empty");
        }
        String cleanToken = accessToken.trim();

        // 1. Query Meta Graph API /me
        String meUrl = String.format("https://graph.facebook.com/%s/me?fields=id,name&access_token=%s",
                DEFAULT_GRAPH_VERSION, cleanToken);

        String userId = null;
        String userName = "Meta User";
        try {
            ResponseEntity<String> meResponse = restTemplate.getForEntity(meUrl, String.class);
            if (meResponse.getStatusCode().is2xxSuccessful() && meResponse.getBody() != null) {
                JsonNode meJson = objectMapper.readTree(meResponse.getBody());
                userId = meJson.path("id").asText(null);
                userName = meJson.path("name").asText("Meta User");
            }
        } catch (Exception e) {
            log.warn("Meta /me lookup warning: {}", e.getMessage());
            throw new RuntimeException("Failed to validate Meta access token: " + e.getMessage(), e);
        }

        // 2. Query Meta Graph API /me/accounts (Managed Facebook Pages)
        List<Map<String, Object>> pages = new ArrayList<>();
        String accountsUrl = String.format(
                "https://graph.facebook.com/%s/me/accounts?fields=id,name,category,access_token,instagram_business_account{id,username,name,profile_picture_url}&access_token=%s",
                DEFAULT_GRAPH_VERSION, cleanToken);

        try {
            ResponseEntity<String> accountsResponse = restTemplate.getForEntity(accountsUrl, String.class);
            if (accountsResponse.getStatusCode().is2xxSuccessful() && accountsResponse.getBody() != null) {
                JsonNode accountsJson = objectMapper.readTree(accountsResponse.getBody());
                JsonNode dataNode = accountsJson.path("data");
                if (dataNode.isArray()) {
                    for (JsonNode pageNode : dataNode) {
                        Map<String, Object> pageData = new HashMap<>();
                        pageData.put("pageId", pageNode.path("id").asText());
                        pageData.put("pageName", pageNode.path("name").asText());
                        pageData.put("category", pageNode.path("category").asText(""));
                        pageData.put("pageAccessToken", pageNode.path("access_token").asText(""));

                        if (pageNode.has("instagram_business_account")) {
                            JsonNode igNode = pageNode.path("instagram_business_account");
                            Map<String, Object> igData = new HashMap<>();
                            igData.put("instagramAccountId", igNode.path("id").asText());
                            igData.put("instagramUsername", igNode.path("username").asText(""));
                            igData.put("name", igNode.path("name").asText(""));
                            igData.put("profilePictureUrl", igNode.path("profile_picture_url").asText(""));
                            pageData.put("instagramAccount", igData);
                        }
                        pages.add(pageData);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Meta /me/accounts lookup warning: {}", e.getMessage());
        }

        // If no pages were returned (e.g. if the passed token is already a Page Access Token directly)
        if (pages.isEmpty()) {
            try {
                String pageCheckUrl = String.format(
                        "https://graph.facebook.com/%s/me?fields=id,name,category,instagram_business_account{id,username,name,profile_picture_url}&access_token=%s",
                        DEFAULT_GRAPH_VERSION, cleanToken);
                ResponseEntity<String> pageCheckResp = restTemplate.getForEntity(pageCheckUrl, String.class);
                if (pageCheckResp.getStatusCode().is2xxSuccessful() && pageCheckResp.getBody() != null) {
                    JsonNode pNode = objectMapper.readTree(pageCheckResp.getBody());
                    Map<String, Object> pageData = new HashMap<>();
                    pageData.put("pageId", pNode.path("id").asText());
                    pageData.put("pageName", pNode.path("name").asText());
                    pageData.put("category", pNode.path("category").asText(""));
                    pageData.put("pageAccessToken", cleanToken);

                    if (pNode.has("instagram_business_account")) {
                        JsonNode igNode = pNode.path("instagram_business_account");
                        Map<String, Object> igData = new HashMap<>();
                        igData.put("instagramAccountId", igNode.path("id").asText());
                        igData.put("instagramUsername", igNode.path("username").asText(""));
                        igData.put("name", igNode.path("name").asText(""));
                        igData.put("profilePictureUrl", igNode.path("profile_picture_url").asText(""));
                        pageData.put("instagramAccount", igData);
                    }
                    pages.add(pageData);
                }
            } catch (Exception ignored) {}
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("userName", userName);
        result.put("pages", pages);
        result.put("pagesCount", pages.size());
        return result;
    }

    /**
     * Connects and saves the selected Facebook Page and Instagram Account to Master Settings.
     */
    public Map<String, Object> connectAccount(Map<String, Object> connectionRequest) {
        String pageId = (String) connectionRequest.get("pageId");
        String pageName = (String) connectionRequest.get("pageName");
        String pageAccessToken = (String) connectionRequest.get("pageAccessToken");
        String instagramAccountId = (String) connectionRequest.get("instagramAccountId");
        String instagramUsername = (String) connectionRequest.get("instagramUsername");
        String userName = (String) connectionRequest.get("userName");
        String metaAppId = (String) connectionRequest.get("metaAppId");

        if (pageId == null || pageId.isBlank()) {
            throw new IllegalArgumentException("Facebook Page ID is required to connect");
        }

        Map<String, Object> current = masterSettingsService.getAllSettings();
        current.put("metaConnected", true);
        current.put("metaUserName", userName != null ? userName : "Meta User");
        current.put("facebookPageId", pageId.trim());
        current.put("facebookPageName", pageName != null ? pageName.trim() : "Facebook Page");
        current.put("facebookPageAccessToken", pageAccessToken != null ? pageAccessToken.trim() : "");
        current.put("instagramAccountId", instagramAccountId != null ? instagramAccountId.trim() : "");
        current.put("instagramUsername", instagramUsername != null ? instagramUsername.trim() : "");
        current.put("crossPostToFacebook", true);
        current.put("metaTokenStatus", "VALID");
        current.put("metaTokenError", "");
        current.put("metaTokenCheckedAt", java.time.LocalDateTime.now().toString());
        if (metaAppId != null && !metaAppId.isBlank()) {
            current.put("metaAppId", metaAppId.trim());
        }

        masterSettingsService.updateSettings(current);
        log.info("Meta account connected successfully: Page '{}' ({}), Instagram '{}' ({})",
                pageName, pageId, instagramUsername, instagramAccountId);

        return getConnectionStatus();
    }

    /**
     * Disconnects Meta account by clearing stored credentials in Master Settings.
     */
    public Map<String, Object> disconnect() {
        Map<String, Object> current = masterSettingsService.getAllSettings();
        current.put("metaConnected", false);
        current.put("metaUserName", "");
        current.put("facebookPageId", "");
        current.put("facebookPageName", "");
        current.put("facebookPageAccessToken", "");
        current.put("instagramAccountId", "");
        current.put("instagramUsername", "");
        current.put("metaTokenStatus", "");
        current.put("metaTokenError", "");

        masterSettingsService.updateSettings(current);
        log.info("Meta account disconnected.");
        return getConnectionStatus();
    }

    /**
     * Retrieves current connection status and public profile information.
     */
    public Map<String, Object> getConnectionStatus() {
        Map<String, Object> s = masterSettingsService.getAllSettings();
        boolean connected = Boolean.TRUE.equals(s.get("metaConnected"));
        String pageId = (String) s.get("facebookPageId");
        String pageName = (String) s.get("facebookPageName");
        String igId = (String) s.get("instagramAccountId");
        String igUser = (String) s.get("instagramUsername");
        String userName = (String) s.get("metaUserName");
        String metaAppId = (String) s.get("metaAppId");
        boolean crossPost = !Boolean.FALSE.equals(s.get("crossPostToFacebook"));

        boolean hasPageToken = s.get("facebookPageAccessToken") != null && !((String) s.get("facebookPageAccessToken")).isBlank();

        Map<String, Object> status = new HashMap<>();
        status.put("connected", connected && (pageId != null && !pageId.isBlank()));
        status.put("userName", userName != null ? userName : "");
        status.put("facebookPageId", pageId != null ? pageId : "");
        status.put("facebookPageName", pageName != null ? pageName : "");
        status.put("instagramAccountId", igId != null ? igId : "");
        status.put("instagramUsername", igUser != null ? igUser : "");
        status.put("crossPostToFacebook", crossPost);
        status.put("metaAppId", metaAppId != null ? metaAppId : "");
        status.put("hasValidToken", hasPageToken && !"INVALID".equals(s.get("metaTokenStatus")));
        status.put("tokenStatus", s.getOrDefault("metaTokenStatus", ""));
        status.put("tokenCheckedAt", s.getOrDefault("metaTokenCheckedAt", ""));
        return status;
    }

    /**
     * Cross-posts Reel / Video or Photo to the connected Facebook Page.
     */
    /**
     * Cross-posts to the brand's Facebook Page. Returns null when no Page is connected or cross-posting is off;
     * throws when Facebook refuses it (the caller logs it, Instagram publishing is not affected).
     */
    public Map<String, String> publishToFacebookPage(Long postId, String mediaUrl, String caption, boolean isVideo) {
        Map<String, Object> settings = masterSettingsService.getAllSettings();
        String pageId = (String) settings.get("facebookPageId");
        String pageAccessToken = (String) settings.get("facebookPageAccessToken");
        boolean crossPost = !Boolean.FALSE.equals(settings.get("crossPostToFacebook"));
        if (!crossPost || pageId == null || pageId.isBlank() || pageAccessToken == null || pageAccessToken.isBlank()) {
            return null;
        }

        String externalUrl = storageService.getPresignedUrl(mediaUrl, Duration.ofHours(24));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add(isVideo ? "file_url" : "url", externalUrl);
        body.add(isVideo ? "description" : "caption", caption != null ? caption : "");
        body.add("published", "true");
        body.add("access_token", pageAccessToken);
        String postUrl = String.format("https://graph.facebook.com/%s/%s/%s", DEFAULT_GRAPH_VERSION, pageId, isVideo ? "videos" : "photos");

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(postUrl, new HttpEntity<>(body, headers), String.class);
            JsonNode json = objectMapper.readTree(response.getBody() != null ? response.getBody() : "{}");
            String id = json.hasNonNull("post_id") ? json.get("post_id").asText() : json.path("id").asText(null);
            if (id == null) throw new IllegalStateException("Facebook didn't return a post id");
            String permalink = isVideo ? String.format("https://facebook.com/%s/videos/%s", pageId, id) : "https://facebook.com/" + id;
            log.info("Cross-posted post {} to Facebook Page {} ({})", postId, pageId, id);
            return Map.of("postId", id, "permalink", permalink);
        } catch (Exception e) {
            throw new IllegalStateException("Facebook cross-post failed: " + e.getMessage(), e);
        }
    }
}
