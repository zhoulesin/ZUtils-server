package com.zutils.server.model.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ParameterDto {
    private String name;
    private String description;
    private String type;
    private boolean required;
}
