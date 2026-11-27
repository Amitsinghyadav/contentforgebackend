package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.PlanEntity;
import com.ozo.contentforge.service.PlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The plans on offer (read-only for the team). */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final PlanService planService;

    public PlanController(PlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public List<PlanEntity> activePlans() {
        return planService.getActivePlans();
    }
}
