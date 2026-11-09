package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.model.entity.UserSubscriptionEntity;
import com.ozo.contentforge.service.PlanService;
import com.ozo.contentforge.service.security.JwtService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/plans")
@CrossOrigin
public class PlanController {

    private final PlanService planService;
    private final JwtService jwtService;

    public PlanController(PlanService planService, JwtService jwtService) {
        this.planService = planService;
        this.jwtService = jwtService;
    }

    @GetMapping
    public ResponseEntity<List<PlanEntity>> getActivePlans() {
        return ResponseEntity.ok(planService.getActivePlans());
    }

    @GetMapping("/my-subscription")
    public ResponseEntity<?> getMySubscription(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            Map<String, Object> claims = jwtService.validateAndExtractClaims(token);
            if (claims != null && claims.containsKey("userId")) {
                userId = ((Number) claims.get("userId")).longValue();
            }
        }

        Optional<UserSubscriptionEntity> sub = planService.getActiveSubscriptionForUser(userId);
        if (sub.isEmpty()) {
            return ResponseEntity.ok(Map.of("hasActivePlan", false, "message", "No active plan assigned yet. Contact administrator."));
        }
        return ResponseEntity.ok(sub.get());
    }
}

