package com.zutils.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zutils.server.exception.BusinessException;
import com.zutils.server.exception.ResourceNotFoundException;
import com.zutils.server.model.dto.DependencyDto;
import com.zutils.server.model.dto.ParameterDto;
import com.zutils.server.model.dto.request.CreatePluginRequest;
import com.zutils.server.model.dto.request.CreateVersionRequest;
import com.zutils.server.model.dto.response.*;
import com.zutils.server.model.entity.Plugin;
import com.zutils.server.model.entity.PluginVersion;
import com.zutils.server.model.enums.PluginCategory;
import com.zutils.server.model.enums.VersionStatus;
import com.zutils.server.repository.PluginRepository;
import com.zutils.server.repository.PluginVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PluginService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final PluginRepository pluginRepository;
    private final PluginVersionRepository pluginVersionRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public List<PluginManifestResponse> getManifest() {
        List<Plugin> plugins = pluginRepository.findAll();
        List<PluginManifestResponse> result = new ArrayList<>();

        for (Plugin plugin : plugins) {
            Optional<PluginVersion> latestVersion = pluginVersionRepository
                    .findTopByPluginIdAndStatusOrderByPublishedAtDesc(plugin.getId(), VersionStatus.APPROVED);
            if (latestVersion.isEmpty()) continue;

            PluginVersion version = latestVersion.get();
            result.add(PluginManifestResponse.builder()
                    .functionName(plugin.getFunctionName())
                    .description(plugin.getDescription())
                    .version(version.getVersion())
                    .dexUrl(version.getDexUrl())
                    .className(version.getClassName())
                    .checksum(version.getChecksum())
                    .size(version.getDexSize())
                    .parameters(parseJsonList(version.getParameters(), ParameterDto.class))
                    .requiredPermissions(parseJsonList(version.getRequiredPermissions(), String.class))
                    .dependencies(parseJsonList(version.getDependencies(), DependencyDto.class))
                    .build());
        }

        return result;
    }

    public Page<PluginListResponse> getPlugins(String category, Pageable pageable) {
        Page<Plugin> pluginPage;
        if (category != null && !category.isBlank()) {
            try {
                PluginCategory cat = PluginCategory.valueOf(category.toUpperCase());
                pluginPage = pluginRepository.findByCategory(cat, pageable);
            } catch (IllegalArgumentException e) {
                pluginPage = Page.empty(pageable);
            }
        } else {
            pluginPage = pluginRepository.findAll(pageable);
        }

        List<PluginListResponse> responses = pluginPage.getContent().stream()
                .map(this::toPluginListResponse)
                .toList();

        return new PageImpl<>(responses, pageable, pluginPage.getTotalElements());
    }

    public PluginDetailResponse getPlugin(String id) {
        Plugin plugin = pluginRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Plugin", id));

        List<PluginVersion> versions = pluginVersionRepository.findByPluginIdOrderByPublishedAtDesc(id);
        PluginVersion latest = versions.isEmpty() ? null : versions.get(0);

        List<PluginDetailResponse.VersionInfo> versionInfos = versions.stream()
                .map(this::toVersionInfo)
                .toList();

        return PluginDetailResponse.builder()
                .id(plugin.getId())
                .functionName(plugin.getFunctionName())
                .description(plugin.getDescription())
                .icon(plugin.getIcon())
                .category(plugin.getCategory().name())
                .author(plugin.getAuthor())
                .minAppVersion(plugin.getMinAppVersion())
                .downloads(plugin.getDownloads())
                .rating(plugin.getRating())
                .createdAt(plugin.getCreatedAt().format(DTF))
                .updatedAt(plugin.getUpdatedAt().format(DTF))
                .latestVersion(latest != null ? toVersionInfo(latest) : null)
                .versionHistory(versionInfos)
                .build();
    }

    public List<VersionResponse> getVersions(String pluginId) {
        if (!pluginRepository.existsById(pluginId)) {
            throw new ResourceNotFoundException("Plugin", pluginId);
        }

        return pluginVersionRepository.findByPluginIdOrderByPublishedAtDesc(pluginId).stream()
                .map(this::toVersionResponse)
                .toList();
    }

    @Transactional
    public Plugin createPlugin(Long developerId, CreatePluginRequest request) {
        if (pluginRepository.existsById(request.getId())) {
            throw new BusinessException("Plugin ID already exists: " + request.getId());
        }

        PluginCategory category;
        try {
            category = PluginCategory.valueOf(request.getCategory().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid category: " + request.getCategory());
        }

        Plugin plugin = Plugin.builder()
                .id(request.getId())
                .functionName(request.getFunctionName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .category(category)
                .author(request.getAuthor())
                .minAppVersion(request.getMinAppVersion())
                .developerId(developerId)
                .downloads(0L)
                .rating(0.0)
                .build();

        return pluginRepository.save(plugin);
    }

    @Transactional
    public PluginVersion createVersion(Long developerId, String pluginId,
                                       CreateVersionRequest request, MultipartFile dexFile) {
        Plugin plugin = pluginRepository.findById(pluginId)
                .orElseThrow(() -> new ResourceNotFoundException("Plugin", pluginId));

        if (!plugin.getDeveloperId().equals(developerId)) {
            throw new BusinessException(403, "You do not own this plugin");
        }

        if (pluginVersionRepository.existsByPluginIdAndVersion(pluginId, request.getVersion())) {
            throw new BusinessException("Version " + request.getVersion() + " already exists for this plugin");
        }

        if (dexFile == null || dexFile.isEmpty()) {
            throw new BusinessException("DEX file is required");
        }

        String dexUrl = storageService.store(dexFile, pluginId, request.getVersion());
        String checksum = storageService.computeChecksum(dexFile);
        long dexSize = dexFile.getSize();

        String parametersJson = toJsonString(request.getParameters());
        String permissionsJson = toJsonString(request.getRequiredPermissions());
        String dependenciesJson = toJsonString(request.getDependencies());

        PluginVersion version = PluginVersion.builder()
                .plugin(plugin)
                .version(request.getVersion())
                .dexUrl(dexUrl)
                .dexSize(dexSize)
                .checksum(checksum)
                .className(request.getClassName())
                .parameters(parametersJson)
                .requiredPermissions(permissionsJson)
                .dependencies(dependenciesJson)
                .changelog(request.getChangelog())
                .status(VersionStatus.APPROVED)
                .build();

        return pluginVersionRepository.save(version);
    }

    public String getDexUrl(String pluginId, String version) {
        PluginVersion pv = pluginVersionRepository.findByPluginIdAndVersion(pluginId, version)
                .orElseThrow(() -> new ResourceNotFoundException("PluginVersion", pluginId + "/" + version));
        return pv.getDexUrl();
    }

    private PluginListResponse toPluginListResponse(Plugin plugin) {
        Optional<PluginVersion> latest = pluginVersionRepository
                .findTopByPluginIdAndStatusOrderByPublishedAtDesc(plugin.getId(), VersionStatus.APPROVED);

        return PluginListResponse.builder()
                .id(plugin.getId())
                .functionName(plugin.getFunctionName())
                .description(plugin.getDescription())
                .icon(plugin.getIcon())
                .category(plugin.getCategory().name())
                .author(plugin.getAuthor())
                .version(latest.map(PluginVersion::getVersion).orElse(null))
                .downloads(plugin.getDownloads())
                .rating(plugin.getRating())
                .updatedAt(plugin.getUpdatedAt().format(DTF))
                .build();
    }

    private PluginDetailResponse.VersionInfo toVersionInfo(PluginVersion v) {
        return PluginDetailResponse.VersionInfo.builder()
                .versionId(v.getId())
                .version(v.getVersion())
                .dexUrl(v.getDexUrl())
                .dexSize(v.getDexSize())
                .checksum(v.getChecksum())
                .className(v.getClassName())
                .parameters(parseJsonList(v.getParameters(), ParameterDto.class))
                .requiredPermissions(parseJsonList(v.getRequiredPermissions(), String.class))
                .dependencies(parseJsonList(v.getDependencies(), DependencyDto.class))
                .changelog(v.getChangelog())
                .status(v.getStatus().name())
                .publishedAt(v.getPublishedAt().format(DTF))
                .build();
    }

    private VersionResponse toVersionResponse(PluginVersion v) {
        return VersionResponse.builder()
                .id(v.getId())
                .pluginId(v.getPlugin().getId())
                .version(v.getVersion())
                .dexUrl(v.getDexUrl())
                .dexSize(v.getDexSize())
                .checksum(v.getChecksum())
                .className(v.getClassName())
                .parameters(parseJsonList(v.getParameters(), ParameterDto.class))
                .requiredPermissions(parseJsonList(v.getRequiredPermissions(), String.class))
                .dependencies(parseJsonList(v.getDependencies(), DependencyDto.class))
                .changelog(v.getChangelog())
                .status(v.getStatus().name())
                .publishedAt(v.getPublishedAt().format(DTF))
                .build();
    }

    private <T> List<T> parseJsonList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
