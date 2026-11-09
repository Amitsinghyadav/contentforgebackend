package com.ozo.contentforge.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "cf_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "role", nullable = false)
    private String role; // "ADMIN" or "BUSINESS"

    @Column(name = "admin_name")
    private String adminName; // Contact / Admin Person Name

    @Column(name = "organization_name", nullable = false)
    private String organizationName; // Business / Organization Name

    @Column(name = "registration_no")
    private String registrationNo; // Business Reg / CIN

    @Column(name = "gst_no")
    private String gstNo; // GST Identification Number

    @Column(name = "mobile_no")
    private String mobileNo;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.active == null) this.active = true;
        if (this.role == null) this.role = "BUSINESS";
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

