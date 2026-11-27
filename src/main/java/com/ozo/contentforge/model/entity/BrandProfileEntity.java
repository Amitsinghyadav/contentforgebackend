package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "brand_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandProfileEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brand_name", nullable = false)
    private String brandName;

    @Column(name = "industry")
    private String industry; // e.g., Healthcare, Fitness, E-commerce, SaaS, Real Estate

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "logo_asset_id")
    private String logoAssetId;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "colors_json", columnDefinition = "TEXT")
    private String colorsJson; // e.g. ["#0052FF", "#00D26A", "#1E293B"]

    @Column(name = "fonts_json", columnDefinition = "TEXT")
    private String fontsJson; // e.g. {"primary": "Inter", "heading": "Outfit"}

    @Column(name = "cta")
    private String cta; // e.g. "Visit ozohealth.in or download the app"

    @Column(name = "website")
    private String website; // e.g. "ozohealth.in"

    @Column(name = "brand_tone")
    private String brandTone; // e.g. "Trustworthy, clear, Indian, empathetic"

    @Column(name = "target_audience", columnDefinition = "TEXT")
    private String targetAudience;

    @Column(name = "content_guidelines", columnDefinition = "TEXT")
    private String contentGuidelines;

    @Column(name = "disclaimer", columnDefinition = "TEXT")
    private String disclaimer;

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.isDefault == null) this.isDefault = false;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
