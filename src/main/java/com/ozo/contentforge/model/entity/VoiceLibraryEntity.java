package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "voice_library")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoiceLibraryEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name; // e.g. "Amit Singh Cloned Voice - Hinglish"

    @Column(name = "voice_provider")
    private String voiceProvider; // "ElevenLabs"

    @Column(name = "provider_voice_id", nullable = false)
    private String providerVoiceId; // e.g. "21m00Tcm4TlvDq8ikWAM"

    @Column(name = "sample_audio_url")
    private String sampleAudioUrl; // Uploaded mp3 audio sample URL

    @Column(name = "description")
    private String description;

    @Column(name = "gender")
    private String gender; // Male, Female, Neutral

    @Column(name = "language")
    private String language; // Hinglish, Hindi, English

    @Column(name = "accent")
    private String accent; // Indian, US, British

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "voice_cloning_tier")
    private String voiceCloningTier; // "INSTANT" or "PROFESSIONAL"

    @Column(name = "training_status")
    private String trainingStatus; // "READY", "TRAINING", "PENDING_VERIFICATION", "FAILED"

    @Column(name = "audio_duration_minutes")
    private Double audioDurationMinutes;

    @Column(name = "consent_audio_url")
    private String consentAudioUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.isDefault == null) this.isDefault = false;
        if (this.voiceCloningTier == null) this.voiceCloningTier = "INSTANT";
        if (this.trainingStatus == null) this.trainingStatus = "READY";
    }
}
