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
        if (appId == null || appId.isBlank()) {
            return simulateOAuthLogin();
        }

        try {
            String tokenUrl = String.format(
                    "https://graph.facebook.com/%s/oauth/access_token?client_id=%s&redirect_uri=%s&client_secret=%s&code=%s",
                    DEFAULT_GRAPH_VERSION, appId.trim(), redirectUri != null ? redirectUri.trim() : "", appSecret != null ? appSecret.trim() : "", code.trim()
            );
            ResponseEntity<String> response = restTemplate.getForEntity(tokenUrl, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode json = objectMapper.readTree(response.getBody());
                String accessToken = json.path("access_token").asText();
                return inspectToken(accessToken);
            }
        } catch (Exception e) {
            log.error("Failed to exchange Meta OAuth code: {}", e.getMessage());
            throw new RuntimeException("Meta OAuth exchange error: " + e.getMessage(), e);
        }
        throw new RuntimeException("Meta did not return an access token for the provided OAuth code.");
    }

    /**
     * Simulates 1-click OAuth login for development / preview environments without manual token entry.
     */
    public Map<String, Object> simulateOAuthLogin() {
        return buildMockInspection("oauth_token_verified_" + UUID.randomUUID().toString().substring(0, 8));
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
            // Fallback for simulation / mock testing if token is mock
            if (cleanToken.startsWith("mock_") || cleanToken.startsWith("demo_")) {
                return buildMockInspection(cleanToken);
            }
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
        if (metaAppId != null && !metaAppId.isBlank()) {
            current.put("metaAppId", metaAppId.trim());
        }

        masterSettingsService.updateSettings(current, "admin");
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

        masterSettingsService.updateSettings(current, "admin");
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
        status.put("hasValidToken", hasPageToken);
        return status;
    }

    /**
     * Cross-posts Reel / Video or Photo to the connected Facebook Page.
     */
    public Map<String, String> publishToFacebookPage(Long postId, String mediaUrl, String caption, boolean isVideo) {
        Map<String, Object> settings = masterSettingsService.getAllSettings();
        String pageId = (String) settings.get("facebookPageId");
        String pageAccessToken = (String) settings.get("facebookPageAccessToken");

        log.info("Cross-posting to Facebook Page {} for post {} (isVideo={})...", pageId, postId, isVideo);

        String fbPostId = "fb_post_" + System.currentTimeMillis();
        String permalink = String.format("https://facebook.com/%s/posts/%s",
                pageId != null && !pageId.isBlank() ? pageId : "ozohealth", postId);

        if (pageId != null && !pageId.isBlank() && pageAccessToken != null && !pageAccessToken.isBlank() && !pageAccessToken.startsWith("mock_")) {
            try {
                String externalUrl = storageService.getPresignedUrl(mediaUrl, Duration.ofHours(24));

                if (isVideo) {
                    // Publish video / Reel to Facebook Page: POST /{page-id}/videos
                    String postUrl = String.format("https://graph.facebook.com/%s/%s/videos", DEFAULT_GRAPH_VERSION, pageId);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                    body.add("file_url", externalUrl);
                    body.add("description", caption != null ? caption : "");
                    body.add("published", "true");
                    body.add("access_token", pageAccessToken);

                    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
                    ResponseEntity<String> response = restTemplate.postForEntity(postUrl, request, String.class);

                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        JsonNode json = objectMapper.readTree(response.getBody());
                        if (json.has("id")) {
                            fbPostId = json.get("id").asText();
                            permalink = String.format("https://facebook.com/%s/videos/%s", pageId, fbPostId);
                            log.info("Facebook Page video Reel published successfully! FB Video ID: {}", fbPostId);
                        }
                    }
                } else {
                    // Publish photo to Facebook Page: POST /{page-id}/photos
                    String postUrl = String.format("https://graph.facebook.com/%s/%s/photos", DEFAULT_GRAPH_VERSION, pageId);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                    MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                    body.add("url", externalUrl);
                    body.add("caption", caption != null ? caption : "");
                    body.add("published", "true");
                    body.add("access_token", pageAccessToken);

                    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
                    ResponseEntity<String> response = restTemplate.postForEntity(postUrl, request, String.class);

                    if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                        JsonNode json = objectMapper.readTree(response.getBody());
                        if (json.has("post_id")) {
                            fbPostId = json.get("post_id").asText();
                        } else if (json.has("id")) {
                            fbPostId = json.get("id").asText();
                        }
                        permalink = String.format("https://facebook.com/%s", fbPostId);
                        log.info("Facebook Page photo published successfully! FB Post ID: {}", fbPostId);
                    }
                }
            } catch (Exception e) {
                log.warn("Facebook cross-posting encountered warning: {}", e.getMessage());
            }
        } else {
            log.info("Using simulated Facebook publishing (mock/offline mode).");
        }

        return Map.of("postId", fbPostId, "permalink", permalink);
    }

    private Map<String, Object> buildMockInspection(String token) {
        Map<String, Object> pageData = new HashMap<>();
        pageData.put("pageId", "104928571938472");
        pageData.put("pageName", "OZO Health Official");
        pageData.put("category", "Medical & Health");
        pageData.put("pageAccessToken", token);

        Map<String, Object> igData = new HashMap<>();
        igData.put("instagramAccountId", "17841459201948301");
        igData.put("instagramUsername", "ozohealth");
        igData.put("name", "OZO Health");
        pageData.put("instagramAccount", igData);

        Map<String, Object> res = new HashMap<>();
        res.put("userId", "102847192847120");
        res.put("userName", "OZO Administrator");
        res.put("pages", List.of(pageData));
        res.put("pagesCount", 1);
        return res;
    }
}

