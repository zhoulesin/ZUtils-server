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
    @Builder
    public static class DeveloperInfo {
        private Long id;
        private String username;
        private String nickname;
        private String email;
        private String role;
        private String memberUid;
        private String avatarUrl;

        public DeveloperInfo(Long id, String username, String nickname, String email, String role,
                             String memberUid, String avatarUrl) {
            this.id = id;
            this.username = username;
            this.nickname = nickname;
            this.email = email;
            this.role = role;
            this.memberUid = memberUid;
            this.avatarUrl = avatarUrl;
        }
    }
}
