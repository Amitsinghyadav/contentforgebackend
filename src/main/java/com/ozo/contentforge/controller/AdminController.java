package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.model.entity.UserSubscriptionEntity;
import com.ozo.contentforge.repository.UserRepository;
import com.ozo.contentforge.service.PlanService;
import com.ozo.contentforge.service.security.PasswordService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
@CrossOrigin
public class AdminController {

    private final UserRepository userRepository;
    private final PlanService planService;
    private final PasswordService passwordService;

    public AdminController(UserRepository userRepository,
                           PlanService planService,
                           PasswordService passwordService) {
        this.userRepository = userRepository;
        this.planService = planService;
        this.passwordService = passwordService;
    }

    // =========================================================================
    // 1. BUSINESS CLIENT ONBOARDING & MANAGEMENT
    // =========================================================================

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        List<UserEntity> users = userRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (UserEntity u : users) {
            Optional<UserSubscriptionEntity> sub = planService.getActiveSubscriptionForUser(u.getId());
            Map<String, Object> map = new HashMap<>();
            map.put("id", u.getId());
            map.put("email", u.getEmail());
            map.put("role", u.getRole());
            map.put("adminName", u.getAdminName());
            map.put("organizationName", u.getOrganizationName());
            map.put("registrationNo", u.getRegistrationNo());
            map.put("gstNo", u.getGstNo());
            map.put("mobileNo", u.getMobileNo());
            map.put("active", u.getActive());
            map.put("createdAt", u.getCreatedAt());
            map.put("subscription", sub.orElse(null));
            result.add(map);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/users")
    public ResponseEntity<?> onboardUser(@RequestBody Map<String, Object> req) {
        String email = (String) req.get("email");
        String password = (String) req.get("password");
        String organizationName = (String) req.get("organizationName");
        String adminName = (String) req.get("adminName");
        String registrationNo = (String) req.get("registrationNo");
        String gstNo = (String) req.get("gstNo");
        String mobileNo = (String) req.get("mobileNo");
        Number planIdNum = (Number) req.get("initialPlanId");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password is required"));
        }
        if (organizationName == null || organizationName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Organization name is required"));
        }
        if (userRepository.existsByEmailIgnoreCase(email.trim())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "A client with this email already exists"));
        }

        UserEntity user = UserEntity.builder()
                .email(email.trim().toLowerCase())
                .passwordHash(passwordService.hashPassword(password))
                .role("BUSINESS")
                .organizationName(organizationName.trim())
                .adminName(adminName != null ? adminName.trim() : null)
                .registrationNo(registrationNo != null ? registrationNo.trim() : null)
                .gstNo(gstNo != null ? gstNo.trim() : null)
                .mobileNo(mobileNo != null ? mobileNo.trim() : null)
                .active(true)
                .build();

        UserEntity savedUser = userRepository.save(user);

        // Optionally assign initial plan if provided
        UserSubscriptionEntity sub = null;
        if (planIdNum != null) {
            sub = planService.assignPlanToUser(savedUser.getId(), planIdNum.longValue(), "MONTHLY", 1);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("message", "Business onboarded successfully");
        res.put("user", savedUser);
        res.put("subscription", sub);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable("id") Long id, @RequestBody Map<String, Object> req) {
        UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (req.containsKey("organizationName") && req.get("organizationName") != null) {
            user.setOrganizationName((String) req.get("organizationName"));
        }
        if (req.containsKey("adminName")) {
            user.setAdminName((String) req.get("adminName"));
        }
        if (req.containsKey("registrationNo")) {
            user.setRegistrationNo((String) req.get("registrationNo"));
        }
        if (req.containsKey("gstNo")) {
            user.setGstNo((String) req.get("gstNo"));
        }
        if (req.containsKey("mobileNo")) {
            user.setMobileNo((String) req.get("mobileNo"));
        }
        if (req.containsKey("active") && req.get("active") != null) {
            user.setActive(Boolean.TRUE.equals(req.get("active")));
        }
        if (req.containsKey("password") && req.get("password") != null && !((String) req.get("password")).isBlank()) {
            user.setPasswordHash(passwordService.hashPassword((String) req.get("password")));
        }

        return ResponseEntity.ok(userRepository.save(user));
    }

    @PostMapping("/users/{id}/assign-plan")
    public ResponseEntity<?> assignPlan(@PathVariable("id") Long id, @RequestBody Map<String, Object> req) {
        Number planIdNum = (Number) req.get("planId");
        if (planIdNum == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "planId is required"));
        }
        String billingCycle = (String) req.getOrDefault("billingCycle", "MONTHLY");
        int durationMonths = req.containsKey("durationMonths") ? ((Number) req.get("durationMonths")).intValue() : 1;

        UserSubscriptionEntity sub = planService.assignPlanToUser(id, planIdNum.longValue(), billingCycle, durationMonths);
        return ResponseEntity.ok(sub);
    }

    // =========================================================================
    // 2. PLANS & QUOTAS MANAGEMENT
    // =========================================================================

    @GetMapping("/plans")
    public ResponseEntity<List<PlanEntity>> getAllPlans() {
        return ResponseEntity.ok(planService.getAllPlans());
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanEntity> createPlan(@RequestBody PlanEntity plan) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(plan));
    }

    @PutMapping("/plans/{id}")
    public ResponseEntity<PlanEntity> updatePlan(@PathVariable("id") Long id, @RequestBody PlanEntity plan) {
        return ResponseEntity.ok(planService.updatePlan(id, plan));
    }

    @DeleteMapping("/plans/{id}")
    public ResponseEntity<Void> deletePlan(@PathVariable("id") Long id) {
        planService.deletePlan(id);
        return ResponseEntity.noContent().build();
    }
}

