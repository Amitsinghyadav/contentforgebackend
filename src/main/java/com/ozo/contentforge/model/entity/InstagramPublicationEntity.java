package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "instagram_publications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstagramPublicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "container_id")
    private String containerId;

    @Column(name = "media_id")
    private String mediaId;

    @Column(name = "permalink")
    private String permalink;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "status")
    private String status; // CREATED, IN_PROGRESS, PUBLISHED, FAILED

    @Column(name = "error_json", columnDefinition = "TEXT")
    private String errorJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = "CREATED";
    }
}
