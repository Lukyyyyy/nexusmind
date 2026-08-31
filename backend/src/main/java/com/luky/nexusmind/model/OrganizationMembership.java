package com.luky.nexusmind.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "organization_memberships", uniqueConstraints =
        @UniqueConstraint(name = "uk_membership_user_org", columnNames = {"user_id", "org_tag_id"}))
public class OrganizationMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_tag_id", referencedColumnName = "tag_id", nullable = false)
    private OrganizationTag organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Source source;

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    public enum Source { SYSTEM, APPROVED, ADMIN }
}
