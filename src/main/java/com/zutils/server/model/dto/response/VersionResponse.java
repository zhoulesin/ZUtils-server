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
public class VersionResponse {
    private Long id;
    private String pluginId;
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
