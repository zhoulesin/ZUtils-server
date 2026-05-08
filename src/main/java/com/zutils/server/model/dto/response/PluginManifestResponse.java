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
public class PluginManifestResponse {
    private String functionName;
    private String description;
    private String version;
    private String dexUrl;
    private String className;
    private String checksum;
    private String signature;
    private Long size;
    private String author;
    private String memberUid;
    private String authorNickname;
    private List<ParameterDto> parameters;
    private List<String> requiredPermissions;
    private List<DependencyDto> dependencies;
}
