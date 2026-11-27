package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.model.entity.BrandSubscriptionEntity;
import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.model.entity.UserEntity;
import com.ozo.contentforge.service.BrandService;
import com.ozo.contentforge.service.PlanService;
import com.ozo.contentforge.service.TeamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin app: client brands, their plans, the team, and the plan catalogue. Admins only (see AuthenticationFilter). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final BrandService brandService;
    private final PlanService planService;
    private final TeamService teamService;

    public AdminController(BrandService brandService, PlanService planService, TeamService teamService) {
        this.brandService = brandService;
        this.planService = planService;
        this.teamService = teamService;
    }

    // ---- Brands ----

    public record BrandRequest(String name, String industry, String contactName, String contactEmail, String contactPhone,
                               String registrationNo, String gstNo, String notes, Long planId, LocalDate planStartDate) {
        BrandService.BrandDetails details() {
            return new BrandService.BrandDetails(name, industry, contactName, contactEmail, contactPhone, registrationNo, gstNo, notes);
        }
    }

    @GetMapping("/brands")
    public List<Map<String, Object>> listBrands() {
        return brandService.listAll().stream().map(brandService::adminView).toList();
    }

    @PostMapping("/brands")
    public ResponseEntity<Map<String, Object>> createBrand(@RequestBody BrandRequest req) {
        BrandEntity brand = brandService.create(req.details(), req.planId(), req.planStartDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(brandService.adminView(brand));
    }

    @PutMapping("/brands/{id}")
    public Map<String, Object> updateBrand(@PathVariable("id") Long id, @RequestBody BrandRequest req) {
        return brandService.adminView(brandService.update(id, req.details()));
    }

    @PatchMapping("/brands/{id}/active")
    public Map<String, Object> setBrandActive(@PathVariable("id") Long id, @RequestBody Map<String, Boolean> req) {
        return brandService.adminView(brandService.setActive(id, Boolean.TRUE.equals(req.get("active"))));
    }

    /** Custom counts are optional: send them to give the brand its own monthly numbers (Custom tier). */
    public record AssignPlanRequest(Long planId, LocalDate startDate, Integer avatarReels, Integer explainers,
                                    Integer carousels, Integer imagePosts, Integer storyFrames, Integer minFaceVideos) {
        PlanService.Counts customCounts() {
            if (avatarReels == null && explainers == null && carousels == null && imagePosts == null && storyFrames == null) {
                return null;
            }
            return new PlanService.Counts(nz(avatarReels), nz(explainers), nz(carousels), nz(imagePosts), nz(storyFrames), nz(minFaceVideos));
        }

        private static int nz(Integer n) {
            return n != null ? n : 0;
        }
    }

    @PostMapping("/brands/{id}/plan")
    public BrandSubscriptionEntity assignPlan(@PathVariable("id") Long id, @RequestBody AssignPlanRequest req) {
        if (req.planId() == null) throw new IllegalArgumentException("Choose a plan");
        return planService.assignPlan(id, req.planId(), req.startDate(), req.customCounts());
    }

    // ---- Team ----

    public record TeamRequest(String email, String name, String role, String password, Boolean active) {
    }

    @GetMapping("/team")
    public List<Map<String, Object>> listTeam() {
        return teamService.list().stream().map(AdminController::teamView).toList();
    }

    @PostMapping("/team")
    public ResponseEntity<Map<String, Object>> createTeamMember(@RequestBody TeamRequest req) {
        UserEntity user = teamService.create(req.email(), req.name(), req.role(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(teamView(user));
    }

    @PutMapping("/team/{id}")
    public Map<String, Object> updateTeamMember(@PathVariable("id") Long id, @RequestBody TeamRequest req) {
        return teamView(teamService.update(id, req.name(), req.role(), req.active(), req.password()));
    }

    /** Never returns the password hash. */
    static Map<String, Object> teamView(UserEntity u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("name", u.getAdminName() != null ? u.getAdminName() : "");
        m.put("role", u.getRole());
        m.put("active", u.getActive());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    // ---- Plans ----

    @GetMapping("/plans")
    public List<PlanEntity> listPlans() {
        return planService.getAllPlans();
    }

    @PostMapping("/plans")
    public ResponseEntity<PlanEntity> createPlan(@RequestBody PlanEntity plan) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(plan));
    }

    @PutMapping("/plans/{id}")
    public PlanEntity updatePlan(@PathVariable("id") Long id, @RequestBody PlanEntity plan) {
        return planService.updatePlan(id, plan);
    }

    @DeleteMapping("/plans/{id}")
    public PlanEntity retirePlan(@PathVariable("id") Long id) {
        return planService.retirePlan(id);
    }
}
