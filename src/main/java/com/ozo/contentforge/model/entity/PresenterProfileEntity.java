package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "presenter_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PresenterProfileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name; // e.g. "Amit Singh Yadav"

    @Column(name = "role_title")
    private String roleTitle; // e.g. "Lead Health Presenter / Founder"

    @Column(name = "avatar_provider")
    private String avatarProvider; // "HEYGEN"

    @Column(name = "avatar_id")
    private String avatarId; // HeyGen avatar asset ID

    @Column(name = "avatar_image_url")
    private String avatarImageUrl; // Uploaded presenter photo / avatar preview

    @Column(name = "voice_provider")
    private String voiceProvider; // "ELEVENLABS"

    @Column(name = "voice_id")
    private String voiceId; // ElevenLabs cloned voice ID

    @Column(name = "consent_reference")
    private String consentReference; // Legal consent reference / document ID

    @Column(name = "active")
    private Boolean active;

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.active == null) this.active = true;
        if (this.isDefault == null) this.isDefault = false;
    }
}
