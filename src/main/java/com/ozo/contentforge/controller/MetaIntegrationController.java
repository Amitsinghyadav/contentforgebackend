package com.ozo.contentforge.controller;

import com.ozo.contentforge.service.MetaIntegrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/meta")
@CrossOrigin
public class MetaIntegrationController {

    private final MetaIntegrationService metaIntegrationService;

    public MetaIntegrationController(MetaIntegrationService metaIntegrationService) {
        this.metaIntegrationService = metaIntegrationService;
    }

    /**
     * Get current Meta connection status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(metaIntegrationService.getConnectionStatus());
    }

    /**
     * Inspect a Meta User or Page Access Token from OAuth flow or token input.
     * Returns the pages managed by the user and linked Instagram business accounts.
     */
    @PostMapping("/inspect")
    public ResponseEntity<Map<String, Object>> inspectToken(@RequestBody Map<String, String> request) {
        String token = request.get("accessToken");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Access token is required"));
        }
        try {
            Map<String, Object> result = metaIntegrationService.inspectToken(token);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Connect and store the selected Facebook Page and linked Instagram account.
     */
    @PostMapping("/connect")
    public ResponseEntity<Map<String, Object>> connectAccount(@RequestBody Map<String, Object> connectionRequest) {
        try {
            Map<String, Object> status = metaIntegrationService.connectAccount(connectionRequest);
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Disconnect Meta integration.
     */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        return ResponseEntity.ok(metaIntegrationService.disconnect());
    }

    /**
     * Exchanges OAuth authorization code for token and automatically inspects accounts.
     */
    @PostMapping("/oauth/exchange")
    public ResponseEntity<Map<String, Object>> exchangeOAuthCode(@RequestBody Map<String, String> request) {
        String code = request.get("code");
        String redirectUri = request.get("redirectUri");
        String appId = request.get("appId");
        String appSecret = request.get("appSecret");
        try {
            Map<String, Object> result = metaIntegrationService.exchangeOAuthCode(code, redirectUri, appId, appSecret);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 1-Click OAuth simulation for instant connection without token entry.
     */
    @PostMapping("/oauth/simulate")
    public ResponseEntity<Map<String, Object>> simulateOAuth() {
        return ResponseEntity.ok(metaIntegrationService.simulateOAuthLogin());
    }
}

