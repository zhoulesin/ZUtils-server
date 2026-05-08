package com.zutils.server.service;

import com.zutils.server.exception.BusinessException;
import com.zutils.server.model.dto.request.LoginRequest;
import com.zutils.server.model.dto.request.RegisterRequest;
import com.zutils.server.model.dto.request.UpdateProfileRequest;
import com.zutils.server.model.dto.response.AuthResponse;
import com.zutils.server.model.entity.Developer;
import com.zutils.server.model.entity.LoginLog;
import com.zutils.server.repository.DeveloperRepository;
import com.zutils.server.repository.LoginLogRepository;
import com.zutils.server.security.DeveloperDetails;
import com.zutils.server.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_LOGIN_FAILS = 5;
    private static final long LOCK_DURATION_MINUTES = 15;

    private final DeveloperRepository developerRepository;
    private final LoginLogRepository loginLogRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    private static final String PASSWORD_PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$";

    public AuthResponse register(RegisterRequest request) {
        if (developerRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists");
        }
        if (developerRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists");
        }
        validatePasswordStrength(request.getPassword());

        Developer developer = Developer.builder()
                .username(request.getUsername())
                .nickname(request.getNickname())
                .memberUid(generateMemberUid(request.getMemberUid()))
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        developer = developerRepository.save(developer);

        DeveloperDetails details = new DeveloperDetails(developer);
        String token = jwtTokenProvider.generateToken(details);

        return AuthResponse.builder()
                .token(token)
                .developer(new AuthResponse.DeveloperInfo(
                        developer.getId(), developer.getUsername(), developer.getNickname(),
                        developer.getEmail(), developer.getRole() != null ? developer.getRole().name() : "DEVELOPER",
                        developer.getMemberUid(), developer.getAvatarUrl()))
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String ip) {
        Developer developer = developerRepository.findByUsername(request.getUsername())
                .orElse(null);

        if (developer == null) {
            saveLoginLog(null, request.getUsername(), ip, false, "用户不存在");
            throw new BusinessException(401, "用户名或密码错误");
        }

        if (developer.isDeleted()) {
            throw new BusinessException(403, "账号已被注销");
        }

        if (!developer.isEnabled()) {
            saveLoginLog(developer.getId(), request.getUsername(), ip, false, "账号已禁用");
            throw new BusinessException(403, "账号已被禁用");
        }

        // Check lock
        if (developer.getLockedUntil() != null && developer.getLockedUntil().isAfter(LocalDateTime.now())) {
            long remaining = java.time.Duration.between(LocalDateTime.now(), developer.getLockedUntil()).toMinutes();
            saveLoginLog(developer.getId(), request.getUsername(), ip, false, "账号已锁定，剩余 " + remaining + " 分钟");
            throw new BusinessException(423, "账号已锁定，请 " + remaining + " 分钟后重试");
        }

        if (!passwordEncoder.matches(request.getPassword(), developer.getPassword())) {
            developer.setLoginFailCount(developer.getLoginFailCount() + 1);
            if (developer.getLoginFailCount() >= MAX_LOGIN_FAILS) {
                developer.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                developerRepository.save(developer);
                saveLoginLog(developer.getId(), request.getUsername(), ip, false,
                        "密码错误 " + MAX_LOGIN_FAILS + " 次，锁定 " + LOCK_DURATION_MINUTES + " 分钟");
                throw new BusinessException(423, "密码错误次数过多，账号已锁定 " + LOCK_DURATION_MINUTES + " 分钟");
            }
            developerRepository.save(developer);
            saveLoginLog(developer.getId(), request.getUsername(), ip, false,
                    "密码错误 (" + developer.getLoginFailCount() + "/" + MAX_LOGIN_FAILS + ")");
            throw new BusinessException(401, "用户名或密码错误");
        }

        // Login success
        developer.setLoginFailCount(0);
        developer.setLockedUntil(null);
        developerRepository.save(developer);

        saveLoginLog(developer.getId(), request.getUsername(), ip, true, "登录成功");

        DeveloperDetails details = new DeveloperDetails(developer);
        String token = jwtTokenProvider.generateToken(details);

        return AuthResponse.builder()
                .token(token)
                .developer(new AuthResponse.DeveloperInfo(
                        developer.getId(), developer.getUsername(), developer.getNickname(),
                        developer.getEmail(), developer.getRole() != null ? developer.getRole().name() : "DEVELOPER",
                        developer.getMemberUid(), developer.getAvatarUrl()))
                .build();
    }

    public Map<String, Object> updateProfile(Long developerId, UpdateProfileRequest request) {
        Developer developer = developerRepository.findById(developerId)
                .orElseThrow(() -> new BusinessException(404, "Developer not found"));

        if (request.getCurrentPassword() != null) {
            if (!passwordEncoder.matches(request.getCurrentPassword(), developer.getPassword())) {
                throw new BusinessException(400, "当前密码错误");
            }
            if (request.getNewPassword() != null) {
                validatePasswordStrength(request.getNewPassword());
                developer.setPassword(passwordEncoder.encode(request.getNewPassword()));
            }
        }

        if (request.getNickname() != null) {
            developer.setNickname(request.getNickname());
        }
        if (request.getAvatarUrl() != null) {
            developer.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getBio() != null) {
            developer.setBio(request.getBio());
        }
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            if (!request.getEmail().equals(developer.getEmail())
                    && developerRepository.existsByEmail(request.getEmail())) {
                throw new BusinessException("Email already in use");
            }
            developer.setEmail(request.getEmail());
        }

        developerRepository.save(developer);
        return Map.of("id", developer.getId(), "username", developer.getUsername(),
                "nickname", developer.getNickname() != null ? developer.getNickname() : "",
                "email", developer.getEmail(), "role", developer.getRole().name());
    }

    private String generateMemberUid(String requested) {
        if (requested != null && !requested.isBlank()) {
            if (developerRepository.existsByMemberUid(requested)) {
                throw new BusinessException("memberUid already exists");
            }
            return requested;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void validatePasswordStrength(String password) {
        if (password == null || !password.matches(PASSWORD_PATTERN)) {
            throw new BusinessException(400, "密码需要至少 8 位，包含大写字母、小写字母和数字");
        }
    }

    private void saveLoginLog(Long developerId, String username, String ip, boolean success, String detail) {
        try {
            loginLogRepository.save(LoginLog.builder()
                    .developerId(developerId)
                    .username(username)
                    .ip(ip != null ? ip : "unknown")
                    .success(success)
                    .detail(detail)
                    .build());
        } catch (Exception ignored) {}
    }
}
