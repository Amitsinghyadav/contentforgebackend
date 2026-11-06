package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.VideoAvatarEntity;
import com.ozo.contentforge.service.VideoAvatarService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/video-avatars")
@CrossOrigin
public class VideoAvatarController {

    private final VideoAvatarService videoAvatarService;

    public VideoAvatarController(VideoAvatarService videoAvatarService) {
        this.videoAvatarService = videoAvatarService;
    }

    @GetMapping
    public ResponseEntity<List<VideoAvatarEntity>> getAllAvatars() {
        return ResponseEntity.ok(videoAvatarService.getAllAvatars());
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoAvatarEntity> getAvatarById(@PathVariable("id") Long id) {
        return videoAvatarService.getAvatarById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<VideoAvatarEntity> createVideoAvatar(
            @RequestParam("name") String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestPart("videoFile") MultipartFile videoFile,
            @RequestParam(value = "isDefault", required = false, defaultValue = "false") Boolean isDefault,
            @RequestParam(value = "linkPresenterId", required = false) Long linkPresenterId) {

        VideoAvatarEntity created = videoAvatarService.createVideoAvatar(
                name, description, videoFile, isDefault, linkPresenterId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/{id}/check-status")
    public ResponseEntity<VideoAvatarEntity> checkStatus(@PathVariable("id") Long id) {
        VideoAvatarEntity updated = videoAvatarService.checkAndUpdateStatus(id);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/{id}/consent-session")
    public ResponseEntity<java.util.Map<String, String>> getConsentSession(@PathVariable("id") Long id) {
        return ResponseEntity.ok(videoAvatarService.getConsentSession(id));
    }

    @PostMapping("/{id}/set-default")
    public ResponseEntity<VideoAvatarEntity> setDefault(@PathVariable("id") Long id) {
        VideoAvatarEntity updated = videoAvatarService.setDefault(id);
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/pre-trained")
    public ResponseEntity<List<java.util.Map<String, Object>>> getPreTrainedAvatars() {
        return ResponseEntity.ok(videoAvatarService.getPreTrainedAvatars());
    }

    @PostMapping("/activate-pre-trained")
    public ResponseEntity<VideoAvatarEntity> activatePreTrainedAvatar(@RequestBody java.util.Map<String, String> payload) {
        String avatarId = payload.get("avatarId");
        String name = payload.get("name");
        String role = payload.get("role");
        String voiceId = payload.get("voiceId");
        String previewImageUrl = payload.get("previewImageUrl");

        VideoAvatarEntity activated = videoAvatarService.activatePreTrainedAvatar(
                avatarId, name, role, voiceId, previewImageUrl);
        return ResponseEntity.ok(activated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAvatar(@PathVariable("id") Long id) {
        videoAvatarService.deleteAvatar(id);
        return ResponseEntity.noContent().build();
    }
}
