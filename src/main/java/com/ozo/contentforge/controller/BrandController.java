package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.BrandProfileEntity;
import com.ozo.contentforge.service.BrandProfileService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/brands")
@CrossOrigin
public class BrandController {

    private final BrandProfileService brandService;

    public BrandController(BrandProfileService brandService) {
        this.brandService = brandService;
    }

    @GetMapping
    public ResponseEntity<List<BrandProfileEntity>> getAllBrands() {
        return ResponseEntity.ok(brandService.getAllBrands());
    }

    @GetMapping("/default")
    public ResponseEntity<BrandProfileEntity> getDefaultBrand() {
        return ResponseEntity.ok(brandService.getDefaultBrand().orElse(null));
    }

    @PostMapping(consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<BrandProfileEntity> createBrand(
            @RequestParam("brandName") String brandName,
            @RequestParam(value = "industry", required = false) String industry,
            @RequestParam(value = "tagline", required = false) String tagline,
            @RequestParam(value = "colorsJson", required = false) String colorsJson,
            @RequestParam(value = "fontsJson", required = false) String fontsJson,
            @RequestParam(value = "cta", required = false) String cta,
            @RequestParam(value = "website", required = false) String website,
            @RequestParam(value = "brandTone", required = false) String brandTone,
            @RequestParam(value = "targetAudience", required = false) String targetAudience,
            @RequestParam(value = "contentGuidelines", required = false) String contentGuidelines,
            @RequestParam(value = "disclaimer", required = false) String disclaimer,
            @RequestParam(value = "isDefault", required = false) Boolean isDefault,
            @RequestPart(value = "logoFile", required = false) MultipartFile logoFile) throws IOException {

        return ResponseEntity.ok(brandService.createOrUpdateBrand(null, brandName, industry, tagline,
                colorsJson, fontsJson, cta, website, brandTone, targetAudience, contentGuidelines, disclaimer, isDefault, logoFile));
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<BrandProfileEntity> updateBrand(
            @PathVariable("id") Long id,
            @RequestParam(value = "brandName", required = false) String brandName,
            @RequestParam(value = "industry", required = false) String industry,
            @RequestParam(value = "tagline", required = false) String tagline,
            @RequestParam(value = "colorsJson", required = false) String colorsJson,
            @RequestParam(value = "fontsJson", required = false) String fontsJson,
            @RequestParam(value = "cta", required = false) String cta,
            @RequestParam(value = "website", required = false) String website,
            @RequestParam(value = "brandTone", required = false) String brandTone,
            @RequestParam(value = "targetAudience", required = false) String targetAudience,
            @RequestParam(value = "contentGuidelines", required = false) String contentGuidelines,
            @RequestParam(value = "disclaimer", required = false) String disclaimer,
            @RequestParam(value = "isDefault", required = false) Boolean isDefault,
            @RequestPart(value = "logoFile", required = false) MultipartFile logoFile) throws IOException {

        return ResponseEntity.ok(brandService.createOrUpdateBrand(id, brandName, industry, tagline,
                colorsJson, fontsJson, cta, website, brandTone, targetAudience, contentGuidelines, disclaimer, isDefault, logoFile));
    }
}
