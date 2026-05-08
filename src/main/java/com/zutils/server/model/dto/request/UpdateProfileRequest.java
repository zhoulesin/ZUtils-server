package com.zutils.server.model.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {
    @Size(max = 50)
    private String nickname;

    @Email
    private String email;

    @Size(min = 6, max = 100)
    private String newPassword;

    @Size(min = 1, max = 100)
    private String currentPassword;

    @Size(max = 500)
    private String avatarUrl;

    @Size(max = 1000)
    private String bio;
}
