package com.zutils.server.service;

import com.zutils.server.exception.BusinessException;
import com.zutils.server.model.entity.Developer;
import com.zutils.server.model.entity.Plugin;
import com.zutils.server.repository.DeveloperRepository;
import com.zutils.server.repository.PluginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DeveloperService {

    private final DeveloperRepository developerRepository;
    private final PluginRepository pluginRepository;

    public Map<String, Object> getPublicProfile(String memberUid) {
        Developer dev = developerRepository.findByMemberUid(memberUid)
                .orElseThrow(() -> new BusinessException(404, "Developer not found"));

        if (dev.isDeleted()) {
            throw new BusinessException(404, "Developer not found");
        }

        List<Plugin> plugins = pluginRepository.findByDeveloperId(dev.getId(), org.springframework.data.domain.Pageable.unpaged()).getContent();

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("memberUid", dev.getMemberUid());
        profile.put("nickname", dev.getNickname() != null ? dev.getNickname() : dev.getUsername());
        profile.put("bio", dev.getBio());
        profile.put("avatarUrl", dev.getAvatarUrl());
        profile.put("role", dev.getRole().name());
        profile.put("pluginCount", plugins.size());
        profile.put("totalDownloads", plugins.stream().mapToLong(p -> p.getDownloads() != null ? p.getDownloads() : 0).sum());
        profile.put("plugins", plugins.stream().map(p -> Map.of(
                "id", p.getId(),
                "functionName", p.getFunctionName(),
                "description", p.getDescription() != null ? p.getDescription() : "",
                "downloads", p.getDownloads() != null ? p.getDownloads() : 0,
                "rating", p.getRating() != null ? p.getRating() : 0.0,
                "version", p.getVersions() != null && !p.getVersions().isEmpty() ? p.getVersions().get(0).getVersion() : ""
        )).toList());

        return profile;
    }
}
