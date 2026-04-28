package com.zutils.server.model.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DependencyDto {
    private String name;
    private String version;
    private String dexUrl;
    private String checksum;
}
