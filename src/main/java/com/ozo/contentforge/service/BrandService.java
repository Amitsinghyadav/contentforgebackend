package com.ozo.contentforge.service;

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.model.entity.BrandSubscriptionEntity;
import com.ozo.contentforge.model.entity.GeneratedAssetEntity;
import com.ozo.contentforge.model.enums.AssetType;
import com.ozo.contentforge.repository.BrandProfileRepository;
import com.ozo.contentforge.repository.BrandRepository;
import com.ozo.contentforge.security.CurrentUser;
import com.ozo.contentforge.tenancy.BrandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Client workspaces: creating and editing brands, their consent record, and the list the team switches between. */
@Service
public class BrandService {

    private static final Logger log = LoggerFactory.getLogger(BrandService.class);

    private final BrandRepository brandRepository;
    private final BrandProfileRepository profileRepository;
    private final MasterSettingsService settingsService;
    private final PlanService planService;
    private final StorageService storageService;

    public BrandService(BrandRepository brandRepository, BrandProfileRepository profileRepository,
                        MasterSettingsService settingsService, PlanService planService, StorageService storageService) {
        this.brandRepository = brandRepository;
        this.profileRepository = profileRepository;
        this.settingsService = settingsService;
        this.planService = planService;
        this.storageService = storageService;
    }

    /** Business details entered when onboarding a client. */
    public record BrandDetails(String name, String industry, String contactName, String contactEmail, String contactPhone,
                               String registrationNo, String gstNo, String notes) {
    }

    public List<BrandEntity> listAll() {
        return brandRepository.findAllByOrderByNameAsc();
    }

    public BrandEntity get(Long id) {
        return brandRepository.findById(id).orElseThrow(() -> new NoSuchElementException("Brand not found"));
    }

    /**
     * Creates the brand plus its own settings and brand profile, optionally on a plan. Not one transaction on purpose:
     * the brand's own rows must be written in a session opened inside that brand.
     */
    public BrandEntity create(BrandDetails details, Long planId, LocalDate planStart) {
        if (details.name() == null || details.name().isBlank()) throw new IllegalArgumentException("Brand name is required");
        BrandEntity brand = BrandEntity.builder().active(true).build();
        apply(brand, details);
        brand = brandRepository.save(brand);

        String name = brand.getName();
        String industry = brand.getIndustry();
        BrandContext.runAs(brand.getId(), () -> {
            settingsService.initializeForBrand(name, industry);
            profileRepository.save(BrandProfileEntity.builder()
                    .brandName(name)
                    .industry(industry)
                    .isDefault(true)
                    .build());
        });
        if (planId != null) planService.assignPlan(brand.getId(), planId, planStart, null);
        log.info("Brand '{}' created by {}", name, CurrentUser.actor());
        return brand;
    }

    @Transactional
    public BrandEntity update(Long id, BrandDetails details) {
        BrandEntity brand = get(id);
        apply(brand, details);
        return brandRepository.save(brand);
    }

    @Transactional
    public BrandEntity setActive(Long id, boolean active) {
        BrandEntity brand = get(id);
        brand.setActive(active);
        return brandRepository.save(brand);
    }

    private static void apply(BrandEntity brand, BrandDetails d) {
        if (d.name() != null && !d.name().isBlank()) brand.setName(d.name().trim());
        if (d.industry() != null) brand.setIndustry(blankToNull(d.industry()));
        if (d.contactName() != null) brand.setContactName(blankToNull(d.contactName()));
        if (d.contactEmail() != null) brand.setContactEmail(blankToNull(d.contactEmail()));
        if (d.contactPhone() != null) brand.setContactPhone(blankToNull(d.contactPhone()));
        if (d.registrationNo() != null) brand.setRegistrationNo(blankToNull(d.registrationNo()));
        if (d.gstNo() != null) brand.setGstNo(blankToNull(d.gstNo()));
        if (d.notes() != null) brand.setNotes(blankToNull(d.notes()));
    }

    // =========================================================================
    // Consent (inside the current brand)
    // =========================================================================

    /**
     * Records the client's signed consent. The signed document is required: without it, avatar and voice generation
     * stay blocked.
     */
    @Transactional
    public BrandEntity recordConsent(MultipartFile document, String signedBy, LocalDate signedOn,
                                     boolean face, boolean voice, boolean aiLabel) throws IOException {
        BrandEntity brand = get(BrandContext.require());
        if (signedBy == null || signedBy.isBlank()) throw new IllegalArgumentException("Who signed the consent is required");
        if (document != null && !document.isEmpty()) {
            GeneratedAssetEntity asset = storageService.storeUploadedFile(document, AssetType.DOCUMENT, null, null);
            brand.setConsentDocumentUrl(asset.getStorageUrl());
        }
        if (brand.getConsentDocumentUrl() == null) throw new IllegalArgumentException("Upload the signed consent document");
        brand.setConsentFace(face);
        brand.setConsentVoice(voice);
        brand.setConsentAiLabel(aiLabel);
        brand.setConsentSignedBy(signedBy.trim());
        brand.setConsentSignedAt((signedOn != null ? signedOn : LocalDate.now()).atStartOfDay());
        brand.setConsentRecordedBy(CurrentUser.actor());
        return brandRepository.save(brand);
    }

    /** Stops avatar and voice work for this brand until consent is recorded again. */
    @Transactional
    public BrandEntity revokeConsent() {
        BrandEntity brand = get(BrandContext.require());
        brand.setConsentFace(false);
        brand.setConsentVoice(false);
        brand.setConsentRecordedBy(CurrentUser.actor());
        return brandRepository.save(brand);
    }

    // =========================================================================
    // Views
    // =========================================================================

    /** The brands a team member can switch between, with their plan and Instagram account. */
    public List<Map<String, Object>> workspaceList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BrandEntity b : brandRepository.findByActiveTrueOrderByNameAsc()) {
            Map<String, Object> settings = BrandContext.callAs(b.getId(), settingsService::getAllSettings);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("name", b.getName());
            row.put("industry", b.getIndustry());
            row.put("logoUrl", settings.getOrDefault("brandLogoUrl", ""));
            row.put("instagramUsername", settings.getOrDefault("instagramUsername", ""));
            row.put("metaConnected", Boolean.TRUE.equals(settings.get("metaConnected")));
            row.put("metaTokenStatus", settings.getOrDefault("metaTokenStatus", ""));
            row.put("planName", planService.getSubscription(b.getId()).map(BrandSubscriptionEntity::getPlanName).orElse(null));
            row.put("consentComplete", b.hasFaceConsent() && b.hasVoiceConsent());
            out.add(row);
        }
        return out;
    }

    /** One brand for the admin app: details, plan and consent state. */
    public Map<String, Object> adminView(BrandEntity b) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("brand", b);
        row.put("subscription", planService.getSubscription(b.getId()).orElse(null));
        row.put("consentComplete", b.hasFaceConsent() && b.hasVoiceConsent());
        return row;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

}
