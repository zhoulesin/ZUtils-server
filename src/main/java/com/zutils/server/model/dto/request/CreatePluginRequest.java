package com.zutils.server.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreatePluginRequest {
    @NotBlank
    private String id;

    @NotBlank
    private String functionName;

    private String description;
    private String icon;
    private String category;
    private String author;
    private String minAppVersion;
}
