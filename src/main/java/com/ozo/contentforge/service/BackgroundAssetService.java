package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.BackgroundAssetEntity;
import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.repository.BackgroundAssetRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class BackgroundAssetService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAssetService.class);

    private final BackgroundAssetRepository repository;
    private final StorageService storageService;

    public BackgroundAssetService(BackgroundAssetRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    @PostConstruct
    public void initDefaultGallery() {
        try {
            if (repository.count() == 0) {
                log.info("Seeding curated photorealistic backgrounds into gallery...");
                // Curated high-resolution 9:16 vertical workspace backdrops
                createSeedBackground("Modern Executive Glass Office", "IMAGE",
                        "https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=1080&q=80",
                        true);
                createSeedBackground("Sunlit Creative Studio Workspace", "IMAGE",
                        "https://images.unsplash.com/photo-1524758631624-e2822e304c36?auto=format&fit=crop&w=1080&q=80",
                        false);
                createSeedBackground("Minimalist Architectural Lounge", "IMAGE",
                        "https://images.unsplash.com/photo-1517245386807-bb43f82c33c4?auto=format&fit=crop&w=1080&q=80",
                        false);
                createSeedBackground("Clinical & Diagnostic Consultation Room", "IMAGE",
                        "https://images.unsplash.com/photo-1519494026892-80bbd2d6fd0d?auto=format&fit=crop&w=1080&q=80",
                        false);
            }
        } catch (Exception e) {
            log.warn("Could not seed default background gallery: {}", e.getMessage());
        }
    }

    private void createSeedBackground(String name, String mediaType, String url, boolean isDefault) {
        repository.save(BackgroundAssetEntity.builder()
                .name(name)
                .mediaType(mediaType)
                .storageUrl(url)
                .storagePath(url)
                .mimeType(mediaType.equals("VIDEO") ? "video/mp4" : "image/jpeg")
                .isDefault(isDefault)
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<BackgroundAssetEntity> getAllBackgrounds() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public Optional<BackgroundAssetEntity> getDefaultBackground() {
        return repository.findByIsDefaultTrue();
    }

    @Transactional
    public BackgroundAssetEntity uploadBackground(String name, MultipartFile file, Boolean isDefault) throws IOException {
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        String mediaType = contentType.startsWith("video") ? "VIDEO" : "IMAGE";
        AssetType assetType = mediaType.equals("VIDEO") ? AssetType.VIDEO : AssetType.IMAGE;

        GeneratedAssetEntity asset = storageService.storeUploadedFile(file, assetType, null, null);

        if (Boolean.TRUE.equals(isDefault)) {
            clearDefault();
        }

        BackgroundAssetEntity bg = BackgroundAssetEntity.builder()
                .name((name != null && !name.isBlank()) ? name : file.getOriginalFilename())
                .mediaType(mediaType)
                .storageUrl(asset.getStorageUrl())
                .storagePath(asset.getStoragePath())
                .mimeType(contentType.isBlank() ? (mediaType.equals("VIDEO") ? "video/mp4" : "image/jpeg") : contentType)
                .isDefault(Boolean.TRUE.equals(isDefault))
                .createdAt(LocalDateTime.now())
                .build();

        return repository.save(bg);
    }

    @Transactional
    public BackgroundAssetEntity setDefault(Long id) {
        clearDefault();
        BackgroundAssetEntity bg = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Background not found: " + id));
        bg.setIsDefault(true);
        return repository.save(bg);
    }

    @Transactional
    public void clearDefault() {
        repository.findAll().forEach(b -> {
            if (Boolean.TRUE.equals(b.getIsDefault())) {
                b.setIsDefault(false);
                repository.save(b);
            }
        });
    }

    @Transactional
    public void deleteBackground(Long id) {
        repository.deleteById(id);
    }
}
