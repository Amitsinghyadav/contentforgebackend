package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "daily_generation_batches", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"target_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyGenerationBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "settings_snapshot", columnDefinition = "TEXT")
    private String settingsSnapshot;

    @Column(name = "planned_counts_json", columnDefinition = "TEXT")
    private String plannedCountsJson; // e.g. {"reels": 2, "posts": 0, "carousels": 1, "extras": 1}

    @Column(name = "status")
    private String status; // PLANNED, IN_PROGRESS, COMPLETED, PAUSED_BUDGET

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) this.status = "PLANNED";
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
