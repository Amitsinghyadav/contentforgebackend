package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.entity.PresenterProfileEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.repository.PresenterProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class PresenterProfileService {

    private final PresenterProfileRepository presenterRepository;
    private final StorageService storageService;
    private final AiProviderService aiProviderService;

    public PresenterProfileService(PresenterProfileRepository presenterRepository,
                                  StorageService storageService,
                                  AiProviderService aiProviderService) {
        this.presenterRepository = presenterRepository;
        this.storageService = storageService;
        this.aiProviderService = aiProviderService;
    }

    public List<PresenterProfileEntity> getAllPresenters() {
        return presenterRepository.findAll();
    }

    public Optional<PresenterProfileEntity> getPresenterById(Long id) {
        return presenterRepository.findById(id);
    }

    public Optional<PresenterProfileEntity> getDefaultPresenter() {
        return presenterRepository.findByIsDefaultTrue();
    }

    @Transactional
    public PresenterProfileEntity createPresenter(String name, String roleTitle, String avatarProvider,
                                                  String avatarId, String voiceProvider, String voiceId,
                                                  String consentReference, Boolean isDefault,
                                                  MultipartFile photoFile) throws IOException {
        String avatarImageUrl = null;
        String resolvedAvatarId = avatarId;

        if (photoFile != null && !photoFile.isEmpty()) {
            GeneratedAssetEntity asset = storageService.storeUploadedFile(photoFile, AssetType.IMAGE, null, null);
            avatarImageUrl = asset.getStorageUrl();

            // Check if user gave a synthetic ID or left it blank
            boolean isSynthetic = resolvedAvatarId == null || resolvedAvatarId.isBlank()
                    || resolvedAvatarId.startsWith("heygen_avatar_")
                    || resolvedAvatarId.startsWith("heygen_presenter_")
                    || resolvedAvatarId.startsWith("talking_photo_")
                    || resolvedAvatarId.equals("Wayne_20240711");

            if (isSynthetic) {
                // Call real HeyGen API to create talking photo asset and get real talking_photo_id
                resolvedAvatarId = aiProviderService.uploadHeyGenTalkingPhoto(photoFile.getBytes(), photoFile.getContentType(), photoFile.getOriginalFilename());
            }
        }

        if (resolvedAvatarId == null || resolvedAvatarId.isBlank()) {
            resolvedAvatarId = "Wayne_20240711";
        }

        if (Boolean.TRUE.equals(isDefault)) {
            // Unset previous defaults
            presenterRepository.findAll().forEach(p -> {
                if (Boolean.TRUE.equals(p.getIsDefault())) {
                    p.setIsDefault(false);
                    presenterRepository.save(p);
                }
            });
        }

        PresenterProfileEntity entity = PresenterProfileEntity.builder()
                .name(name)
                .roleTitle(roleTitle != null ? roleTitle : "Digital Presenter")
                .avatarProvider(avatarProvider != null ? avatarProvider : "HEYGEN")
                .avatarId(resolvedAvatarId)
                .avatarImageUrl(avatarImageUrl)
                .voiceProvider(voiceProvider != null ? voiceProvider : "ELEVENLABS")
                .voiceId(voiceId != null ? voiceId : "rRAzMEZ6U57ivzmuPBhL")
                .consentReference(consentReference)
                .active(true)
                .isDefault(Boolean.TRUE.equals(isDefault))
                .build();

        return presenterRepository.save(entity);
    }

    @Transactional
    public PresenterProfileEntity updatePresenter(Long id, String name, String roleTitle, String avatarProvider,
                                                  String avatarId, String voiceProvider, String voiceId,
                                                  String consentReference, Boolean active, Boolean isDefault,
                                                  MultipartFile photoFile) throws IOException {
        PresenterProfileEntity presenter = presenterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Presenter not found: " + id));

        if (name != null) presenter.setName(name);
        if (roleTitle != null) presenter.setRoleTitle(roleTitle);
        if (avatarProvider != null) presenter.setAvatarProvider(avatarProvider);
        if (avatarId != null) presenter.setAvatarId(avatarId);
        if (voiceProvider != null) presenter.setVoiceProvider(voiceProvider);
        if (voiceId != null) presenter.setVoiceId(voiceId);
        if (consentReference != null) presenter.setConsentReference(consentReference);
        if (active != null) presenter.setActive(active);

        if (photoFile != null && !photoFile.isEmpty()) {
            GeneratedAssetEntity asset = storageService.storeUploadedFile(photoFile, AssetType.IMAGE, null, null);
            presenter.setAvatarImageUrl(asset.getStorageUrl());

            boolean isSynthetic = presenter.getAvatarId() == null || presenter.getAvatarId().isBlank()
                    || presenter.getAvatarId().startsWith("heygen_avatar_")
                    || presenter.getAvatarId().startsWith("heygen_presenter_")
                    || presenter.getAvatarId().startsWith("talking_photo_")
                    || presenter.getAvatarId().equals("Wayne_20240711");

            if (isSynthetic) {
                // Call real HeyGen API to register newly uploaded photo and get real talking_photo_id
                String realHeyGenId = aiProviderService.uploadHeyGenTalkingPhoto(photoFile.getBytes(), photoFile.getContentType(), photoFile.getOriginalFilename());
                presenter.setAvatarId(realHeyGenId);
            }
        }

        if (Boolean.TRUE.equals(isDefault)) {
            presenterRepository.findAll().forEach(p -> {
                if (!p.getId().equals(id) && Boolean.TRUE.equals(p.getIsDefault())) {
                    p.setIsDefault(false);
                    presenterRepository.save(p);
                }
            });
            presenter.setIsDefault(true);
        }

        return presenterRepository.save(presenter);
    }

    @Transactional
    public void setDefaultPresenter(Long id) {
        presenterRepository.findAll().forEach(p -> {
            p.setIsDefault(p.getId().equals(id));
            presenterRepository.save(p);
        });
    }

    @Transactional
    public void deletePresenter(Long id) {
        presenterRepository.deleteById(id);
    }
}
