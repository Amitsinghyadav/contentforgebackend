package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.model.enums.JobStatus;
import com.ozo.contentforge.model.enums.SceneType;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_scenes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentSceneEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "scene_number", nullable = false)
    private Integer sceneNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "scene_type", nullable = false)
    private SceneType sceneType; // AVATAR, BROLL, GRAPHIC

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt; // Visual prompt for Runway or HeyGen

    @Column(name = "spoken_text", columnDefinition = "TEXT")
    private String spokenText; // Spoken line / narration reference

    @Column(name = "spoken_line_reference")
    private String spokenLineReference;

    @Column(name = "camera_direction")
    private String cameraDirection; // e.g. "Gentle slow push-in"

    @Column(name = "aspect_ratio")
    private String aspectRatio; // "9:16" or "720:1280"

    @Column(name = "duration_seconds")
    private Integer durationSeconds; // e.g. 5

    @Column(name = "start_time_seconds")
    private Integer startTimeSeconds; // e.g. 0, 8, 13...

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status; // QUEUED, IN_PROGRESS, COMPLETED, FAILED, RETRYING, SKIPPED

    @Column(name = "media_url")
    private String mediaUrl; // Rendered video clip / asset URL

    @Column(name = "provider_task_id")
    private String providerTaskId;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "cost_usd")
    private Double costUsd;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = JobStatus.QUEUED;
        if (this.retryCount == null) this.retryCount = 0;
        if (this.aspectRatio == null) this.aspectRatio = "9:16";
        if (this.costUsd == null) this.costUsd = 0.0;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
