package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "action", nullable = false)
    private String action; // e.g. "UPDATE_SETTINGS", "APPROVE_TEXT", "REGENERATE_SCENE", "APPROVE_MEDIA"

    @Column(name = "entity_type", nullable = false)
    private String entityType; // "SETTINGS", "POST", "SCENE", "EVENT"

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
