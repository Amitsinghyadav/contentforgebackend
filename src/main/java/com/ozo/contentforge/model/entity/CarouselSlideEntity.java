package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import com.ozo.contentforge.model.enums.JobStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "carousel_slides")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CarouselSlideEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "slide_number", nullable = false)
    private Integer slideNumber; // 1 to 6

    @Column(name = "title")
    private String title;

    @Column(name = "body_content", columnDefinition = "TEXT")
    private String bodyContent;

    @Column(name = "visual_prompt", columnDefinition = "TEXT")
    private String visualPrompt;

    @Column(name = "asset_id")
    private Long assetId;

    @Column(name = "image_url")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private JobStatus status; // QUEUED, IN_PROGRESS, COMPLETED, FAILED

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = JobStatus.QUEUED;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
