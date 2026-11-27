package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import com.ozo.contentforge.model.enums.JobStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "video_renders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VideoRenderEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "storage_url", nullable = false)
    private String storageUrl;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(name = "resolution")
    private String resolution; // "1080x1920"

    @Column(name = "render_version")
    private Integer renderVersion; // e.g. 1, 2...

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private JobStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.resolution == null) this.resolution = "1080x1920";
        if (this.renderVersion == null) this.renderVersion = 1;
        if (this.status == null) this.status = JobStatus.COMPLETED;
    }
}
