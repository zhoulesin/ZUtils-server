package com.zutils.server.repository;

import com.zutils.server.model.entity.Plugin;
import com.zutils.server.model.enums.PluginCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PluginRepository extends JpaRepository<Plugin, String> {
    Page<Plugin> findByCategory(PluginCategory category, Pageable pageable);
    Page<Plugin> findByDeveloperId(Long developerId, Pageable pageable);
}
