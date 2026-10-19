package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.service.StorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/media")
@CrossOrigin
public class MediaUploadController {

    private final StorageService storageService;

    public MediaUploadController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping(value = "/upload", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<GeneratedAssetEntity> uploadMedia(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "assetType", defaultValue = "IMAGE") String assetTypeStr,
            @RequestParam(value = "postId", required = false) Long postId,
            @RequestParam(value = "sceneId", required = false) Long sceneId) throws IOException {

        AssetType assetType = AssetType.valueOf(assetTypeStr.toUpperCase());
        GeneratedAssetEntity asset = storageService.storeUploadedFile(file, assetType, postId, sceneId);
        return ResponseEntity.ok(asset);
    }

    @GetMapping("/s3")
    public ResponseEntity<?> getMediaFromS3(@RequestParam("key") String s3Key) {
        java.nio.file.Path localFile = storageService.getLocalFilePath(s3Key);
        if (localFile != null && java.nio.file.Files.exists(localFile)) {
            try {
                byte[] data = java.nio.file.Files.readAllBytes(localFile);
                if (data.length > 0) {
                    return createMediaResponse(s3Key, data);
                }
            } catch (Exception ignored) {}
        }

        try {
            // Generate presigned AWS S3 URL on demand and redirect (302 Found) for high-performance direct streaming
            String presignedUrl = storageService.getPresignedUrl(s3Key, java.time.Duration.ofHours(2));
            if (presignedUrl != null && presignedUrl.startsWith("http") && !presignedUrl.contains("/api/v1/media/s3")) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                        .location(java.net.URI.create(presignedUrl))
                        .build();
            }
        } catch (Exception e) {
            // Fall through to byte streaming if presigning unavailable
        }

        try {
            byte[] data = storageService.getS3Object(s3Key);
            return createMediaResponse(s3Key, data);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<?> createMediaResponse(String s3Key, byte[] data) {
        String contentType = "application/octet-stream";
        String lower = s3Key.toLowerCase();
        if (lower.endsWith(".png")) contentType = "image/png";
        else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) contentType = "image/jpeg";
        else if (lower.endsWith(".webp")) contentType = "image/webp";
        else if (lower.endsWith(".svg")) contentType = "image/svg+xml";
        else if (lower.endsWith(".mp3")) contentType = "audio/mpeg";
        else if (lower.endsWith(".wav")) contentType = "audio/wav";
        else if (lower.endsWith(".mp4")) contentType = "video/mp4";
        else if (lower.endsWith(".vtt")) contentType = "text/vtt";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header("Content-Disposition", "inline; filename=\"" + s3Key.substring(s3Key.lastIndexOf("/") + 1) + "\"")
                .body(data);
    }

    /**
     * Get an authentic, signed AWS S3 URL on demand as JSON.
     * Clients can call this endpoint to fetch an ephemeral signed URL for players or direct downloads.
     */
    @GetMapping("/presign")
    public ResponseEntity<?> getPresignedUrl(
            @RequestParam("key") String s3Key,
            @RequestParam(value = "durationHours", defaultValue = "2") int durationHours) {
        String cleanKey = storageService.extractS3Key(s3Key);
        String presignedUrl = storageService.getPresignedUrl(cleanKey, java.time.Duration.ofHours(durationHours));
        return ResponseEntity.ok(java.util.Map.of(
                "key", cleanKey,
                "presignedUrl", presignedUrl,
                "durationHours", durationHours
        ));
    }
}
