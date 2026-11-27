package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.service.MasterSettingsService;
import com.ozo.contentforge.service.StorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/master-settings")
@CrossOrigin
public class MasterSettingsController {

    private final MasterSettingsService settingsService;
    private final StorageService storageService;

    public MasterSettingsController(MasterSettingsService settingsService, StorageService storageService) {
        this.settingsService = settingsService;
        this.storageService = storageService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(settingsService.getAllSettings());
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> newSettings) {
        return ResponseEntity.ok(settingsService.updateEditableSettings(newSettings));
    }

    @PostMapping(value = "/logo", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<Map<String, Object>> uploadLogo(@RequestPart("file") MultipartFile file) throws IOException {
        GeneratedAssetEntity asset = storageService.storeUploadedFile(file, AssetType.LOGO, null, null);
        Map<String, Object> current = settingsService.getAllSettings();
        current.put("brandLogoUrl", asset.getStorageUrl());
        settingsService.updateSettings(current);
        return ResponseEntity.ok(Map.of(
                "brandLogoUrl", asset.getStorageUrl(),
                "assetId", asset.getId(),
                "storagePath", asset.getStoragePath()
        ));
    }

    @DeleteMapping("/logo")
    public ResponseEntity<Map<String, Object>> removeLogo() {
        Map<String, Object> current = settingsService.getAllSettings();
        current.remove("brandLogoUrl");
        settingsService.updateSettings(current);
        return ResponseEntity.ok(Map.of("message", "Brand logo removed"));
    }
}
