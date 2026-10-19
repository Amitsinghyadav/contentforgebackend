package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.model.enums.ApprovalDecision;
import com.ozo.contentforge.model.enums.ApprovalType;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "content_approvals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_type", nullable = false)
    private ApprovalType approvalType; // TEXT_APPROVAL, FINAL_MEDIA_APPROVAL, CLINICAL_REVIEW

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false)
    private ApprovalDecision decision; // APPROVED, REJECTED

    @Column(name = "reviewer_id")
    private String reviewerId;

    @Column(name = "comments", columnDefinition = "TEXT")
    private String comments;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @PrePersist
    public void onCreate() {
        this.decidedAt = LocalDateTime.now();
    }
}
