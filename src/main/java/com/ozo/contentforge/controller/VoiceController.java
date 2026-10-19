package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.VoiceLibraryEntity;
import com.ozo.contentforge.service.VoiceLibraryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/voices")
@CrossOrigin
public class VoiceController {

    private final VoiceLibraryService voiceService;

    public VoiceController(VoiceLibraryService voiceService) {
        this.voiceService = voiceService;
    }

    @GetMapping
    public ResponseEntity<List<VoiceLibraryEntity>> getAllVoices() {
        return ResponseEntity.ok(voiceService.getAllVoices());
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<VoiceLibraryEntity> createVoice(
            @RequestParam("name") String name,
            @RequestParam(value = "voiceProvider", required = false) String voiceProvider,
            @RequestParam(value = "providerVoiceId", required = false) String providerVoiceId,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "accent", required = false) String accent,
            @RequestParam(value = "isDefault", required = false) Boolean isDefault,
            @RequestPart(value = "audioFile", required = false) MultipartFile audioFile) throws IOException {

        return ResponseEntity.ok(voiceService.createVoice(name, voiceProvider, providerVoiceId,
                description, gender, language, accent, isDefault, audioFile));
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<VoiceLibraryEntity> updateVoice(
            @PathVariable("id") Long id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "voiceProvider", required = false) String voiceProvider,
            @RequestParam(value = "providerVoiceId", required = false) String providerVoiceId,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "gender", required = false) String gender,
            @RequestParam(value = "language", required = false) String language,
            @RequestParam(value = "accent", required = false) String accent,
            @RequestParam(value = "isDefault", required = false) Boolean isDefault,
            @RequestPart(value = "audioFile", required = false) MultipartFile audioFile) throws IOException {

        return ResponseEntity.ok(voiceService.updateVoice(id, name, voiceProvider, providerVoiceId,
                description, gender, language, accent, isDefault, audioFile));
    }

    @PostMapping("/{id}/set-default")
    public ResponseEntity<Void> setDefaultVoice(@PathVariable("id") Long id) {
        voiceService.setDefaultVoice(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVoice(@PathVariable("id") Long id) {
        voiceService.deleteVoice(id);
        return ResponseEntity.noContent().build();
    }
}
