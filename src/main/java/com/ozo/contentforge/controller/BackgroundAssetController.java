package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.BackgroundAssetEntity;
import com.ozo.contentforge.service.BackgroundAssetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/backgrounds")
@CrossOrigin
public class BackgroundAssetController {

    private final BackgroundAssetService service;

    public BackgroundAssetController(BackgroundAssetService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<BackgroundAssetEntity>> getAllBackgrounds() {
        return ResponseEntity.ok(service.getAllBackgrounds());
    }

    @PostMapping("/upload")
    public ResponseEntity<BackgroundAssetEntity> uploadBackground(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "isDefault", defaultValue = "false") Boolean isDefault) throws IOException {
        return ResponseEntity.ok(service.uploadBackground(name, file, isDefault));
    }

    @PutMapping("/{id}/default")
    public ResponseEntity<BackgroundAssetEntity> setDefault(@PathVariable("id") Long id) {
        return ResponseEntity.ok(service.setDefault(id));
    }

    @PutMapping("/clear-default")
    public ResponseEntity<?> clearDefault() {
        service.clearDefault();
        return ResponseEntity.ok(Map.of("cleared", true, "message", "No default background selected. HeyGen standard backdrop will be used."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBackground(@PathVariable("id") Long id) {
        service.deleteBackground(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
