package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.PresenterProfileEntity;
import com.ozo.contentforge.service.PresenterProfileService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/presenters")
@CrossOrigin
public class PresenterController {

    private final PresenterProfileService presenterService;

    public PresenterController(PresenterProfileService presenterService) {
        this.presenterService = presenterService;
    }

    @GetMapping
    public ResponseEntity<List<PresenterProfileEntity>> getAllPresenters() {
        return ResponseEntity.ok(presenterService.getAllPresenters());
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<PresenterProfileEntity> createPresenter(
            @RequestParam("name") String name,
            @RequestParam(value = "roleTitle", required = false) String roleTitle,
            @RequestParam(value = "avatarProvider", required = false) String avatarProvider,
            @RequestParam(value = "avatarId", required = false) String avatarId,
            @RequestParam(value = "voiceProvider", required = false) String voiceProvider,
            @RequestParam(value = "voiceId", required = false) String voiceId,
            @RequestParam(value = "consentReference", required = false) String consentReference,
            @RequestParam(value = "isDefault", required = false) Boolean isDefault,
            @RequestPart(value = "photoFile", required = false) MultipartFile photoFile) throws IOException {

        return ResponseEntity.ok(presenterService.createPresenter(name, roleTitle, avatarProvider, avatarId,
                voiceProvider, voiceId, consentReference, isDefault, photoFile));
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<PresenterProfileEntity> updatePresenter(
            @PathVariable("id") Long id,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "roleTitle", required = false) String roleTitle,
            @RequestParam(value = "avatarProvider", required = false) String avatarProvider,
            @RequestParam(value = "avatarId", required = false) String avatarId,
            @RequestParam(value = "voiceProvider", required = false) String voiceProvider,
            @RequestParam(value = "voiceId", required = false) String voiceId,
            @RequestParam(value = "consentReference", required = false) String consentReference,
            @RequestParam(value = "active", required = false) Boolean active,
            @RequestParam(value = "isDefault", required = false) Boolean isDefault,
            @RequestPart(value = "photoFile", required = false) MultipartFile photoFile) throws IOException {

        return ResponseEntity.ok(presenterService.updatePresenter(id, name, roleTitle, avatarProvider, avatarId,
                voiceProvider, voiceId, consentReference, active, isDefault, photoFile));
    }

    @PostMapping("/{id}/set-default")
    public ResponseEntity<Void> setDefaultPresenter(@PathVariable("id") Long id) {
        presenterService.setDefaultPresenter(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePresenter(@PathVariable("id") Long id) {
        presenterService.deletePresenter(id);
        return ResponseEntity.noContent().build();
    }
}
