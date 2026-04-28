package com.zutils.server.model.entity;

import com.zutils.server.model.enums.VersionStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "plugin_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plugin_id", nullable = false)
    private Plugin plugin;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(name = "dex_url", nullable = false, length = 500)
    private String dexUrl;

    @Column(name = "dex_size")
    private Long dexSize;

    @Column(length = 100)
    private String checksum;

    @Column(name = "class_name", nullable = false, length = 255)
    private String className;

    @Column(columnDefinition = "TEXT")
    private String parameters;

    @Column(name = "required_permissions", columnDefinition = "TEXT")
    private String requiredPermissions;

    @Column(columnDefinition = "TEXT")
    private String dependencies;

    @Column(columnDefinition = "TEXT")
    private String changelog;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private VersionStatus status = VersionStatus.PENDING;

    @Column(name = "published_at", nullable = false, updatable = false)
    private LocalDateTime publishedAt;

    @PrePersist
    protected void onCreate() {
        publishedAt = LocalDateTime.now();
    }
}
