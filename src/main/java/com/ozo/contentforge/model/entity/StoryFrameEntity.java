package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.model.enums.FramePublishStatus;
import com.ozo.contentforge.model.enums.JobStatus;
import com.ozo.contentforge.model.enums.StoryFrameType;
import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** One 9:16 frame of a story post. Frames are published in order at the story's scheduled time. */
@Entity
@Table(name = "story_frames")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StoryFrameEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "frame_number", nullable = false)
    private Integer frameNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "frame_type", nullable = false)
    private StoryFrameType frameType;

    @Column(name = "headline", columnDefinition = "TEXT")
    private String headline;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** For sticker frames: the sticker the team adds by hand, e.g. {"question":"...","options":["Yes","No"]}. */
    @Column(name = "sticker_json", columnDefinition = "TEXT")
    private String stickerJson;

    /** Optional photo behind the text (e.g. a real clinic photo the client sent). */
    @Column(name = "background_url", columnDefinition = "TEXT")
    private String backgroundUrl;

    /** The rendered 1080x1920 frame. */
    @Column(name = "media_url", columnDefinition = "TEXT")
    private String mediaUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "publish_status")
    private FramePublishStatus publishStatus;

    @Column(name = "instagram_media_id")
    private String instagramMediaId;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "publish_error", columnDefinition = "TEXT")
    private String publishError;

    @Column(name = "manual_posted_by")
    private String manualPostedBy;

    @Column(name = "manual_posted_at")
    private LocalDateTime manualPostedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = JobStatus.QUEUED;
        if (publishStatus == null) publishStatus = FramePublishStatus.PENDING;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
