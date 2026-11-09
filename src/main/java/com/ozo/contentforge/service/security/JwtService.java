package com.ozo.contentforge.service.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class JwtService {

    private final ObjectMapper objectMapper;
    private final byte[] secretKeyBytes;
    private static final long EXPIRATION_TIME_MS = 7L * 24 * 60 * 60 * 1000; // 7 days

    public JwtService(ObjectMapper objectMapper,
                      @Value("${jwt.secret:ozo-contentforge-super-secret-jwt-key-2026-minimum-32-chars-long!}") String secret) {
        this.objectMapper = objectMapper;
        this.secretKeyBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String generateToken(Long userId, String email, String role, String organizationName, String adminName) {
        try {
            long now = System.currentTimeMillis();
            long exp = now + EXPIRATION_TIME_MS;

            Map<String, Object> header = Map.of(
                    "alg", "HS256",
                    "typ", "JWT"
            );

            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", String.valueOf(userId));
            payload.put("userId", userId);
            payload.put("email", email);
            payload.put("role", role);
            payload.put("organizationName", organizationName != null ? organizationName : "");
            payload.put("adminName", adminName != null ? adminName : "");
            payload.put("iat", now / 1000);
            payload.put("exp", exp / 1000);

            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);

            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = encodedHeader + "." + encodedPayload;
            String signature = sign(dataToSign);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT token", e);
        }
    }

    public Map<String, Object> validateAndExtractClaims(String token) {
        try {
            if (token == null || !token.contains(".")) return null;
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;

            String dataToSign = parts[0] + "." + parts[1];
            String expectedSignature = sign(dataToSign);

            if (!expectedSignature.equals(parts[2])) {
                return null; // Signature invalid
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> claims = objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() {});

            // Check expiration
            if (claims.containsKey("exp")) {
                long exp = ((Number) claims.get("exp")).longValue();
                if ((System.currentTimeMillis() / 1000) > exp) {
                    return null; // Token expired
                }
            }

            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secretKeyBytes, "HmacSHA256");
        mac.init(keySpec);
        byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
    }
}

