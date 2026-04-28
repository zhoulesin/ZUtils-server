package com.zutils.server.repository;

import com.zutils.server.model.entity.PluginVersion;
import com.zutils.server.model.enums.VersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PluginVersionRepository extends JpaRepository<PluginVersion, Long> {
    List<PluginVersion> findByPluginIdOrderByPublishedAtDesc(String pluginId);
    Optional<PluginVersion> findTopByPluginIdAndStatusOrderByPublishedAtDesc(String pluginId, VersionStatus status);
    Optional<PluginVersion> findByPluginIdAndVersion(String pluginId, String version);
    boolean existsByPluginIdAndVersion(String pluginId, String version);
    long countByStatus(VersionStatus status);
    List<PluginVersion> findByStatusOrderByPublishedAtDesc(VersionStatus status);
}
