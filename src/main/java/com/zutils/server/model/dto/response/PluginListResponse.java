package com.zutils.server.model.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PluginListResponse {
    private String id;
    private String functionName;
    private String description;
    private String icon;
    private String category;
    private String author;
    private String version;
    private Long downloads;
    private Double rating;
    private String updatedAt;
}
