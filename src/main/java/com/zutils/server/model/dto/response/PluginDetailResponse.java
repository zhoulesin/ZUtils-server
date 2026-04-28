package com.zutils.server.model.dto.response;

import com.zutils.server.model.dto.DependencyDto;
import com.zutils.server.model.dto.ParameterDto;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginDetailResponse {
    private String id;
    private String functionName;
    private String description;
    private String icon;
    private String category;
    private String author;
    private String minAppVersion;
    private Long downloads;
    private Double rating;
    private String createdAt;
    private String updatedAt;

    private VersionInfo latestVersion;
    private List<VersionInfo> versionHistory;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VersionInfo {
        private Long versionId;
        private String version;
        private String dexUrl;
        private Long dexSize;
        private String checksum;
        private String className;
        private List<ParameterDto> parameters;
        private List<String> requiredPermissions;
        private List<DependencyDto> dependencies;
        private String changelog;
        private String status;
        private String publishedAt;
    }
}
