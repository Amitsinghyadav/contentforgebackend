package com.ozo.contentforge.controller;

import com.ozo.contentforge.model.entity.BrandEntity;
import com.ozo.contentforge.service.BrandService;
import com.ozo.contentforge.service.PlanService;
import com.ozo.contentforge.tenancy.BrandContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** The brand selected in the dashboard (X-Brand-Id): its details, consent record and this month's plan usage. */
@RestController
@RequestMapping("/api/v1/brand")
public class CurrentBrandController {

    private final BrandService brandService;
    private final PlanService planService;

    public CurrentBrandController(BrandService brandService, PlanService planService) {
        this.brandService = brandService;
        this.planService = planService;
    }

    @GetMapping
    public Map<String, Object> current() {
        BrandEntity brand = brandService.get(BrandContext.require());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("brand", brand);
        out.put("faceConsent", brand.hasFaceConsent());
        out.put("voiceConsent", brand.hasVoiceConsent());
        return out;
    }

    /** Usage for the plan month containing {@code date} (today by default). */
    @GetMapping("/usage")
    public Object usage(@RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return planService.getUsage(day).<Object>map(u -> u)
                .orElse(Map.of("hasPlan", false, "message", "No plan assigned yet. Assign one in the admin app."));
    }

    @PostMapping(value = "/consent", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> recordConsent(@RequestPart(value = "document", required = false) MultipartFile document,
                                             @RequestParam("signedBy") String signedBy,
                                             @RequestParam(value = "signedOn", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate signedOn,
                                             @RequestParam(value = "face", defaultValue = "false") boolean face,
                                             @RequestParam(value = "voice", defaultValue = "false") boolean voice,
                                             @RequestParam(value = "aiLabel", defaultValue = "false") boolean aiLabel) throws IOException {
        brandService.recordConsent(document, signedBy, signedOn, face, voice, aiLabel);
        return current();
    }

    @PostMapping("/consent/revoke")
    public Map<String, Object> revokeConsent() {
        brandService.revokeConsent();
        return current();
    }
}
