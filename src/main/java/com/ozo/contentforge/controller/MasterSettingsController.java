package com.ozo.contentforge.controller;

import com.ozo.contentforge.service.MasterSettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/master-settings")
@CrossOrigin
public class MasterSettingsController {

    private final MasterSettingsService settingsService;

    public MasterSettingsController(MasterSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> newSettings,
                                                              @RequestHeader(value = "X-Actor-Id", required = false) String actorId) {
        return ResponseEntity.ok(settingsService.updateSettings(newSettings, actorId));
    }
}
