package com.zutils.server.model.dto.request;

import com.zutils.server.model.dto.DependencyDto;
import com.zutils.server.model.dto.ParameterDto;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateVersionRequest {
    @NotBlank
    private String version;

    @NotBlank
    private String className;

    private List<ParameterDto> parameters;
    private List<String> requiredPermissions;
    private List<DependencyDto> dependencies;
    private String changelog;
}
