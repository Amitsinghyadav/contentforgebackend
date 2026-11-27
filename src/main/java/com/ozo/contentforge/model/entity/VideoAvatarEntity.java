package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "video_avatars")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoAvatarEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name; // e.g. "Amit Singh Yadav - Studio Twin"

    @Column(name = "description", columnDefinition = "TEXT")
    private String description; // Optional notes

    @Column(name = "video_url", columnDefinition = "TEXT")
    private String videoUrl; // S3 URL of the uploaded 2-min training video

    @Column(name = "thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl; // Preview thumbnail

    @Column(name = "avatar_id")
    private String avatarId; // Dynamic HeyGen avatar / look ID

    @Column(name = "asset_id")
    private String assetId; // HeyGen uploaded video asset ID

    @Column(name = "group_id")
    private String groupId; // HeyGen avatar group ID

    @Column(name = "look_id")
    private String lookId; // HeyGen training look ID for status polling

    @Column(name = "status")
    private String status; // PENDING, PROCESSING, COMPLETED, FAILED

    @Column(name = "status_message", columnDefinition = "TEXT")
    private String statusMessage;

    @Column(name = "consent_status")
    private String consentStatus; // pending, accepted, rejected

    @Column(name = "consent_url", columnDefinition = "TEXT")
    private String consentUrl; // Dedicated HeyGen consent recording session link

    @Column(name = "is_default")
    private Boolean isDefault;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = "PROCESSING";
        if (this.isDefault == null) this.isDefault = false;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
