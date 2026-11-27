package com.ozo.contentforge.model.entity;

import com.ozo.contentforge.tenancy.BrandOwnedEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "generation_usage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationUsageEntity extends BrandOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id")
    private Long postId;

    @Column(name = "provider", nullable = false)
    private String provider; // OPENAI, ELEVENLABS, HEYGEN, RUNWAY, AWS

    @Column(name = "model")
    private String model; // e.g. "gpt-4o", "eleven_multilingual_v2", "gen-4.5", "Avatar IV"

    @Column(name = "units")
    private Double units; // e.g. 5.0 (seconds) or 1500 (tokens) or 1 (image)

    @Column(name = "unit_type")
    private String unitType; // SECONDS, TOKENS, CHARACTERS, IMAGES

    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @Column(name = "actual_cost")
    private Double actualCost;

    @Column(name = "currency")
    private String currency; // "USD"

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.currency == null) this.currency = "USD";
    }
}
