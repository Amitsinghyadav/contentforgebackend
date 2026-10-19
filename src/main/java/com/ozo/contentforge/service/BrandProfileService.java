package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.repository.BrandProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class BrandProfileService {

    private final BrandProfileRepository brandRepository;
    private final StorageService storageService;

    public BrandProfileService(BrandProfileRepository brandRepository, StorageService storageService) {
        this.brandRepository = brandRepository;
        this.storageService = storageService;
    }

    public List<BrandProfileEntity> getAllBrands() {
        return brandRepository.findAll();
    }

    public Optional<BrandProfileEntity> getDefaultBrand() {
        return brandRepository.findByIsDefaultTrue();
    }

    public Optional<BrandProfileEntity> getBrandById(Long id) {
        return brandRepository.findById(id);
    }

    @Transactional
    public BrandProfileEntity createOrUpdateBrand(Long id, String brandName, String industry, String tagline,
                                                  String colorsJson, String fontsJson, String cta,
                                                  String website, String brandTone, String targetAudience,
                                                  String contentGuidelines, String disclaimer,
                                                  Boolean isDefault, MultipartFile logoFile) throws IOException {
        BrandProfileEntity brand;
        if (id != null && brandRepository.existsById(id)) {
            brand = brandRepository.findById(id).get();
        } else {
            brand = new BrandProfileEntity();
        }

        if (brandName != null) brand.setBrandName(brandName);
        if (industry != null) brand.setIndustry(industry);
        if (tagline != null) brand.setTagline(tagline);
        if (colorsJson != null) brand.setColorsJson(colorsJson);
        if (fontsJson != null) brand.setFontsJson(fontsJson);
        if (cta != null) brand.setCta(cta);
        if (website != null) brand.setWebsite(website);
        if (brandTone != null) brand.setBrandTone(brandTone);
        if (targetAudience != null) brand.setTargetAudience(targetAudience);
        if (contentGuidelines != null) brand.setContentGuidelines(contentGuidelines);
        if (disclaimer != null) brand.setDisclaimer(disclaimer);

        if (logoFile != null && !logoFile.isEmpty()) {
            GeneratedAssetEntity asset = storageService.storeUploadedFile(logoFile, AssetType.LOGO, null, null);
            brand.setLogoUrl(asset.getStorageUrl());
            brand.setLogoAssetId(asset.getId().toString());
        }

        if (Boolean.TRUE.equals(isDefault)) {
            brandRepository.findAll().forEach(b -> {
                if (b.getId() != null && !b.getId().equals(brand.getId()) && Boolean.TRUE.equals(b.getIsDefault())) {
                    b.setIsDefault(false);
                    brandRepository.save(b);
                }
            });
            brand.setIsDefault(true);
        }

        return brandRepository.save(brand);
    }
}
