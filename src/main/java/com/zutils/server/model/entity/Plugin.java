package com.zutils.server.model.entity;

import com.zutils.server.model.enums.PluginCategory;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "plugins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plugin {

    @Id
    @Column(length = 100)
    private String id;

    @Column(name = "function_name", nullable = false, length = 100)
    private String functionName;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String icon;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PluginCategory category;

    @Column(nullable = false, length = 100)
    private String author;

    @Column(name = "min_app_version", length = 20)
    private String minAppVersion;

    @Builder.Default
    private Long downloads = 0L;

    @Builder.Default
    private Double rating = 0.0;

    @Column(name = "developer_id", nullable = false)
    private Long developerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "plugin", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PluginVersion> versions = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
