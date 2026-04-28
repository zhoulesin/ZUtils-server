package com.zutils.server.service;

import com.zutils.server.model.entity.Developer;
import com.zutils.server.model.entity.Plugin;
import com.zutils.server.model.entity.PluginVersion;
import com.zutils.server.model.enums.Role;
import com.zutils.server.model.enums.VersionStatus;
import com.zutils.server.repository.DeveloperRepository;
import com.zutils.server.repository.PluginRepository;
import com.zutils.server.repository.PluginVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final PluginRepository pluginRepository;
    private final PluginVersionRepository versionRepository;
    private final DeveloperRepository developerRepository;
    private final PasswordEncoder passwordEncoder;
    private final GithubStorageService githubStorageService;

    public Map<String, Object> getStats() {
        long totalPlugins = pluginRepository.count();
        long pendingVersions = versionRepository.countByStatus(VersionStatus.PENDING);
        long approvedVersions = versionRepository.countByStatus(VersionStatus.APPROVED);
        long totalUsers = developerRepository.count();

        return Map.of(
                "totalPlugins", totalPlugins,
                "pendingVersions", pendingVersions,
                "approvedVersions", approvedVersions,
                "totalUsers", totalUsers
        );
    }

    public List<Map<String, Object>> getPendingVersions() {
        List<PluginVersion> versions = versionRepository.findByStatusOrderByPublishedAtDesc(VersionStatus.PENDING);
        return versions.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("versionId", v.getId());
            m.put("pluginId", v.getPlugin().getId());
            m.put("functionName", v.getPlugin().getFunctionName());
            m.put("version", v.getVersion());
            m.put("className", v.getClassName());
            m.put("dexSize", v.getDexSize());
            m.put("status", v.getStatus().name());
            m.put("publishedAt", v.getPublishedAt() != null ? v.getPublishedAt().toString() : null);
            return m;
        }).toList();
    }

    public List<Map<String, Object>> getAllPlugins() {
        List<Plugin> plugins = pluginRepository.findAll();
        return plugins.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("functionName", p.getFunctionName());
            m.put("description", p.getDescription());
            m.put("category", p.getCategory() != null ? p.getCategory().name() : null);
            m.put("author", p.getAuthor());
            m.put("downloads", p.getDownloads());
            m.put("rating", p.getRating());
            m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
            m.put("dexUrl", getDexUrl(p));
            m.put("dexExists", checkDexExists(p.getFunctionName()));
            return m;
        }).toList();
    }

    private String getDexUrl(Plugin p) {
        var versions = versionRepository.findByPluginIdOrderByPublishedAtDesc(p.getId());
        if (!versions.isEmpty()) return versions.get(0).getDexUrl();
        return null;
    }

    private boolean checkDexExists(String functionName) {
        try {
            String filename = "dex/plugin_" + functionName + "_v1.0.0.dex";
            return githubStorageService.isConfigured() && githubStorageService.fileExists(filename);
        } catch (Exception e) {
            return false;
        }
    }

    public List<Map<String, Object>> getUsers() {
        List<Developer> devs = developerRepository.findAll();
        return devs.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("username", d.getUsername());
            m.put("email", d.getEmail());
            m.put("role", d.getRole() != null ? d.getRole().name() : "DEVELOPER");
            m.put("enabled", d.isEnabled());
            m.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
            return m;
        }).toList();
    }

    @Transactional
    public void approveVersion(Long versionId) {
        PluginVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchElementException("Version not found: " + versionId));
        v.setStatus(VersionStatus.APPROVED);
        versionRepository.save(v);
    }

    @Transactional
    public void rejectVersion(Long versionId, String reason) {
        PluginVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchElementException("Version not found: " + versionId));
        v.setStatus(VersionStatus.REJECTED);
        versionRepository.save(v);
    }

    @Transactional
    public void createUser(String username, String email, String password, String roleStr) {
        Role role = roleStr != null && roleStr.equalsIgnoreCase("ADMIN") ? Role.ADMIN : Role.DEVELOPER;
        Developer dev = Developer.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(password))
                .role(role)
                .enabled(true)
                .build();
        developerRepository.save(dev);
    }

    @Transactional
    public void setUserEnabled(Long userId, boolean enabled) {
        Developer dev = developerRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        dev.setEnabled(enabled);
        developerRepository.save(dev);
    }

    @Transactional
    public void deletePlugin(String pluginId) {
        Plugin plugin = pluginRepository.findById(pluginId)
                .orElseThrow(() -> new NoSuchElementException("Plugin not found: " + pluginId));
        String functionName = plugin.getFunctionName();

        // Delete from GitHub if configured
        if (githubStorageService.isConfigured()) {
            try {
                String filename = "dex/plugin_" + functionName + "_v1.0.0.dex";
                githubStorageService.deleteDex(filename);
                githubStorageService.removeFromManifest(functionName);
            } catch (Exception e) {
                log.warn("GitHub cleanup failed for {}: {}", pluginId, e.getMessage());
            }
        }

        // Delete from DB (cascades to versions)
        pluginRepository.delete(plugin);
    }
}
