package com.zutils.server.model.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String token;
    private DeveloperInfo developer;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeveloperInfo {
        private Long id;
        private String username;
        private String email;
        private String role;
    }
}
