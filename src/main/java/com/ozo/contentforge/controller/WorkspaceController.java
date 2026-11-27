package com.ozo.contentforge.controller;

import com.ozo.contentforge.service.BrandService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** The brands the team can switch between in the dashboard. */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final BrandService brandService;

    public WorkspaceController(BrandService brandService) {
        this.brandService = brandService;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return brandService.workspaceList();
    }
}
