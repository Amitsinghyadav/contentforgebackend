package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.model.entity.UserSubscriptionEntity;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.service.PlanService;
import com.ozo.contentforge.service.security.JwtService;
import com.ozo.contentforge.service.security.PasswordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordService passwordService;
    private final JwtService jwtService;
    private final PlanService planService;

    public AuthController(UserRepository userRepository,
                          PasswordService passwordService,
                          JwtService jwtService,
                          PlanService planService) {
        this.userRepository = userRepository;
        this.passwordService = passwordService;
        this.jwtService = jwtService;
        this.planService = planService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        String password = request.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email and password are required."));
        }

        Optional<UserEntity> userOpt = userRepository.findByEmailIgnoreCase(email.trim());
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password."));
        }

        UserEntity user = userOpt.get();
        if (Boolean.FALSE.equals(user.getActive())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Account has been deactivated. Please contact support."));
        }

        if (!passwordService.verifyPassword(password, user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid email or password."));
        }

        String token = jwtService.generateToken(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getOrganizationName(),
                user.getAdminName()
        );

        Optional<UserSubscriptionEntity> activeSub = planService.getActiveSubscriptionForUser(user.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("token", token);
        response.put("user", Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "role", user.getRole(),
                "adminName", user.getAdminName() != null ? user.getAdminName() : "",
                "organizationName", user.getOrganizationName(),
                "registrationNo", user.getRegistrationNo() != null ? user.getRegistrationNo() : "",
                "gstNo", user.getGstNo() != null ? user.getGstNo() : "",
                "mobileNo", user.getMobileNo() != null ? user.getMobileNo() : ""
        ));
        response.put("subscription", activeSub.orElse(null));

        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing or invalid Authorization header."));
        }

        String token = authHeader.substring(7).trim();
        Map<String, Object> claims = jwtService.validateAndExtractClaims(token);
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Session expired or invalid token. Please log in again."));
        }

        Long userId = ((Number) claims.get("userId")).longValue();
        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "User not found."));
        }

        UserEntity user = userOpt.get();
        Optional<UserSubscriptionEntity> activeSub = planService.getActiveSubscriptionForUser(user.getId());

        Map<String, Object> response = new HashMap<>();
        response.put("user", Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "role", user.getRole(),
                "adminName", user.getAdminName() != null ? user.getAdminName() : "",
                "organizationName", user.getOrganizationName(),
                "registrationNo", user.getRegistrationNo() != null ? user.getRegistrationNo() : "",
                "gstNo", user.getGstNo() != null ? user.getGstNo() : "",
                "mobileNo", user.getMobileNo() != null ? user.getMobileNo() : ""
        ));
        response.put("subscription", activeSub.orElse(null));

        return ResponseEntity.ok(response);
    }
}

