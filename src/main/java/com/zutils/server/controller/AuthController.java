package com.zutils.server.controller;

import com.zutils.server.model.dto.request.LoginRequest;
import com.zutils.server.model.dto.request.RegisterRequest;
import com.zutils.server.model.dto.request.UpdateProfileRequest;
import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.model.dto.response.AuthResponse;
import com.zutils.server.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Developer authentication APIs")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new developer account")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Registered successfully", response));
    }

    @PostMapping("/login")
    @Operation(summary = "Login and get JWT token")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip = httpRequest.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = httpRequest.getRemoteAddr();
        AuthResponse response = authService.login(request, ip);
        return ResponseEntity.ok(ApiResponse.success("Login successful", response));
    }

    @PutMapping("/profile")
    @Operation(summary = "Update current developer profile (email, password)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
            @AuthenticationPrincipal(expression = "id") Long developerId,
            @Valid @RequestBody UpdateProfileRequest request) {
        Map<String, Object> result = authService.updateProfile(developerId, request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", result));
    }
}
