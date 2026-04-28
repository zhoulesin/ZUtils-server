package com.zutils.server.service;

import com.zutils.server.exception.BusinessException;
import com.zutils.server.model.dto.request.LoginRequest;
import com.zutils.server.model.dto.request.RegisterRequest;
import com.zutils.server.model.dto.response.AuthResponse;
import com.zutils.server.model.entity.Developer;
import com.zutils.server.repository.DeveloperRepository;
import com.zutils.server.security.DeveloperDetails;
import com.zutils.server.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final DeveloperRepository developerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthResponse register(RegisterRequest request) {
        if (developerRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException("Username already exists");
        }
        if (developerRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("Email already exists");
        }

        Developer developer = Developer.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        developer = developerRepository.save(developer);

        DeveloperDetails details = new DeveloperDetails(developer);
        String token = jwtTokenProvider.generateToken(details);

        return AuthResponse.builder()
                .token(token)
                .developer(new AuthResponse.DeveloperInfo(
                        developer.getId(), developer.getUsername(), developer.getEmail(),
                        developer.getRole() != null ? developer.getRole().name() : "DEVELOPER"))
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        Developer developer = developerRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(401, "Invalid username or password"));

        if (!passwordEncoder.matches(request.getPassword(), developer.getPassword())) {
            throw new BusinessException(401, "Invalid username or password");
        }

        DeveloperDetails details = new DeveloperDetails(developer);
        String token = jwtTokenProvider.generateToken(details);

        return AuthResponse.builder()
                .token(token)
                .developer(new AuthResponse.DeveloperInfo(
                        developer.getId(), developer.getUsername(), developer.getEmail(),
                        developer.getRole() != null ? developer.getRole().name() : "DEVELOPER"))
                .build();
    }
}
