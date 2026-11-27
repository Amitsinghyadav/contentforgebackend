package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import com.ozo.contentforge.model.enums.JobProvider;
import com.ozo.contentforge.model.enums.JobStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "media_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MediaJobEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "scene_id")
    private Long sceneId; // nullable if post-level job

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private JobProvider provider; // OPENAI, ELEVENLABS, HEYGEN, RUNWAY, FFMPEG, META

    @Column(name = "operation", nullable = false)
    private String operation; // e.g. "GENERATE_SCRIPT", "GENERATE_VOICE", "GENERATE_AVATAR", "GENERATE_BROLL", "COMPOSITE_VIDEO", "PUBLISH_INSTAGRAM"

    @Column(name = "provider_task_id")
    private String providerTaskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Column(name = "retries")
    private Integer retries;

    @Column(name = "max_retries")
    private Integer maxRetries;

    @Column(name = "cost_usd")
    private Double costUsd;

    @Column(name = "error_json", columnDefinition = "TEXT")
    private String errorJson;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    public void onCreate() {
        this.startedAt = LocalDateTime.now();
        if (this.status == null) this.status = JobStatus.QUEUED;
        if (this.retries == null) this.retries = 0;
        if (this.maxRetries == null) this.maxRetries = 2;
        if (this.costUsd == null) this.costUsd = 0.0;
    }
}
