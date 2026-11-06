package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.entity.VoiceLibraryEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.repository.VoiceLibraryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class VoiceLibraryService {

    private final VoiceLibraryRepository voiceRepository;
    private final StorageService storageService;
    private final AiProviderService aiProviderService;

    public VoiceLibraryService(VoiceLibraryRepository voiceRepository, StorageService storageService,
                               AiProviderService aiProviderService) {
        this.voiceRepository = voiceRepository;
        this.storageService = storageService;
        this.aiProviderService = aiProviderService;
    }

    public List<VoiceLibraryEntity> getAllVoices() {
        return voiceRepository.findAll();
    }

    public Optional<VoiceLibraryEntity> getVoiceById(Long id) {
        return voiceRepository.findById(id);
    }

    public Optional<VoiceLibraryEntity> getDefaultVoice() {
        return voiceRepository.findByIsDefaultTrue();
    }

    @Transactional
    public VoiceLibraryEntity createVoice(String name, String voiceProvider, String providerVoiceId,
                                          String description, String gender, String language,
                                          String accent, Boolean isDefault, MultipartFile audioFile) throws IOException {
        return createVoice(name, voiceProvider, providerVoiceId, description, gender, language, accent,
                isDefault, audioFile, "INSTANT", null, null);
    }

    @Transactional
    public VoiceLibraryEntity createVoice(String name, String voiceProvider, String providerVoiceId,
                                          String description, String gender, String language,
                                          String accent, Boolean isDefault, MultipartFile audioFile,
                                          String cloneTier, MultipartFile consentFile, Double audioDurationMinutes) throws IOException {
        String sampleAudioUrl = null;
        String consentAudioUrl = null;
        String resolvedVoiceId = providerVoiceId;
        String effectiveTier = (cloneTier != null && cloneTier.equalsIgnoreCase("PROFESSIONAL")) ? "PROFESSIONAL" : "INSTANT";

        if (consentFile != null && !consentFile.isEmpty()) {
            GeneratedAssetEntity consentAsset = storageService.storeUploadedFile(consentFile, AssetType.AUDIO, null, null);
            consentAudioUrl = consentAsset.getStorageUrl();
        }

        if (audioFile != null && !audioFile.isEmpty()) {
            GeneratedAssetEntity asset = storageService.storeUploadedFile(audioFile, AssetType.AUDIO, null, null);
            sampleAudioUrl = asset.getStorageUrl();

            // Auto-clone via ElevenLabs API if providerVoiceId is not manually specified
            if (resolvedVoiceId == null || resolvedVoiceId.isBlank() || resolvedVoiceId.startsWith("el_voice_") || resolvedVoiceId.startsWith("el_pvc_")) {
                if ("PROFESSIONAL".equalsIgnoreCase(effectiveTier)) {
                    byte[] consentBytes = (consentFile != null && !consentFile.isEmpty()) ? consentFile.getBytes() : null;
                    String consentFilename = (consentFile != null) ? consentFile.getOriginalFilename() : null;
                    resolvedVoiceId = aiProviderService.cloneElevenLabsPvcVoice(
                            name, audioFile.getBytes(), audioFile.getOriginalFilename(), description, consentBytes, consentFilename
                    );
                } else {
                    resolvedVoiceId = aiProviderService.cloneElevenLabsVoice(name, audioFile.getBytes(), audioFile.getOriginalFilename(), description);
                }
            }
        }

        if (resolvedVoiceId == null || resolvedVoiceId.isBlank()) {
            resolvedVoiceId = "rRAzMEZ6U57ivzmuPBhL"; // Default Amit Singh Yadav cloned voice
        }

        if (Boolean.TRUE.equals(isDefault)) {
            voiceRepository.findAll().forEach(v -> {
                if (Boolean.TRUE.equals(v.getIsDefault())) {
                    v.setIsDefault(false);
                    voiceRepository.save(v);
                }
            });
        }

        VoiceLibraryEntity entity = VoiceLibraryEntity.builder()
                .name(name)
                .voiceProvider(voiceProvider != null ? voiceProvider : "ElevenLabs")
                .providerVoiceId(resolvedVoiceId)
                .sampleAudioUrl(sampleAudioUrl)
                .description(description)
                .gender(gender != null ? gender : "Male")
                .language(language != null ? language : "Hinglish")
                .accent(accent != null ? accent : "Indian")
                .isDefault(Boolean.TRUE.equals(isDefault))
                .voiceCloningTier(effectiveTier)
                .trainingStatus("READY")
                .audioDurationMinutes(audioDurationMinutes)
                .consentAudioUrl(consentAudioUrl)
                .build();

        return voiceRepository.save(entity);
    }

    @Transactional
    public VoiceLibraryEntity updateVoice(Long id, String name, String voiceProvider, String providerVoiceId,
                                         String description, String gender, String language,
                                         String accent, Boolean isDefault, MultipartFile audioFile) throws IOException {
        VoiceLibraryEntity voice = voiceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Voice not found: " + id));

        if (name != null) voice.setName(name);
        if (voiceProvider != null) voice.setVoiceProvider(voiceProvider);
        if (providerVoiceId != null) voice.setProviderVoiceId(providerVoiceId);
        if (description != null) voice.setDescription(description);
        if (gender != null) voice.setGender(gender);
        if (language != null) voice.setLanguage(language);
        if (accent != null) voice.setAccent(accent);

        if (audioFile != null && !audioFile.isEmpty()) {
            GeneratedAssetEntity asset = storageService.storeUploadedFile(audioFile, AssetType.AUDIO, null, null);
            voice.setSampleAudioUrl(asset.getStorageUrl());
        }

        if (Boolean.TRUE.equals(isDefault)) {
            voiceRepository.findAll().forEach(v -> {
                if (!v.getId().equals(id) && Boolean.TRUE.equals(v.getIsDefault())) {
                    v.setIsDefault(false);
                    voiceRepository.save(v);
                }
            });
            voice.setIsDefault(true);
        }

        return voiceRepository.save(voice);
    }

    @Transactional
    public void setDefaultVoice(Long id) {
        voiceRepository.findAll().forEach(v -> {
            v.setIsDefault(v.getId().equals(id));
            voiceRepository.save(v);
        });
    }

    @Transactional
    public void deleteVoice(Long id) {
        voiceRepository.deleteById(id);
    }
}
