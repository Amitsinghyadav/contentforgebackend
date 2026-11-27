package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A client workspace. Everything the team makes for a client (settings, presenters, voices, posts, the Instagram
 * connection) belongs to one brand. Clients don't log in during the pilot; the team works inside the brand.
 */
@Entity
@Table(name = "cf_brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "industry")
    private String industry;

    @Column(name = "contact_name")
    private String contactName;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "registration_no")
    private String registrationNo;

    @Column(name = "gst_no")
    private String gstNo;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "active", nullable = false)
    private Boolean active;

    // ---- Consent to use the person's face and voice, and to label content as AI-made ----

    @Column(name = "consent_face")
    private Boolean consentFace;

    @Column(name = "consent_voice")
    private Boolean consentVoice;

    @Column(name = "consent_ai_label")
    private Boolean consentAiLabel;

    @Column(name = "consent_signed_by")
    private String consentSignedBy;

    @Column(name = "consent_signed_at")
    private LocalDateTime consentSignedAt;

    @Column(name = "consent_document_url", columnDefinition = "TEXT")
    private String consentDocumentUrl;

    @Column(name = "consent_recorded_by")
    private String consentRecordedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (active == null) active = true;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** Face and voice may be used only with a signed consent document covering both. */
    public boolean hasFaceConsent() {
        return Boolean.TRUE.equals(consentFace) && consentDocumentUrl != null && !consentDocumentUrl.isBlank();
    }

    public boolean hasVoiceConsent() {
        return Boolean.TRUE.equals(consentVoice) && consentDocumentUrl != null && !consentDocumentUrl.isBlank();
    }
}
