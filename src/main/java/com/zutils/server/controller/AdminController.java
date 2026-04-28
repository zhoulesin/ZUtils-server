package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Plugin marketplace admin APIs")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/stats")
    @Operation(summary = "Get admin dashboard stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getStats()));
    }

    @GetMapping("/plugins")
    @Operation(summary = "Get all plugins")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAllPlugins() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getAllPlugins()));
    }

    @GetMapping("/plugins/pending")
    @Operation(summary = "Get pending plugin versions")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getPendingVersions() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getPendingVersions()));
    }

    @PostMapping("/plugins/{versionId}/approve")
    @Operation(summary = "Approve a plugin version")
    public ResponseEntity<ApiResponse<String>> approveVersion(@PathVariable Long versionId) {
        adminService.approveVersion(versionId);
        return ResponseEntity.ok(ApiResponse.success("Version approved"));
    }

    @PostMapping("/plugins/{versionId}/reject")
    @Operation(summary = "Reject a plugin version")
    public ResponseEntity<ApiResponse<String>> rejectVersion(
            @PathVariable Long versionId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", "") : "";
        adminService.rejectVersion(versionId, reason);
        return ResponseEntity.ok(ApiResponse.success("Version rejected"));
    }

    @GetMapping("/users")
    @Operation(summary = "Get all developers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getUsers() {
        return ResponseEntity.ok(ApiResponse.success(adminService.getUsers()));
    }

    @PostMapping("/users")
    @Operation(summary = "Create a new user (admin only)")
    public ResponseEntity<ApiResponse<String>> createUser(@RequestBody CreateUserRequest req) {
        adminService.createUser(req.username(), req.email(), req.password(), req.role());
        return ResponseEntity.ok(ApiResponse.success("User created"));
    }

    @PostMapping("/users/{userId}/disable")
    @Operation(summary = "Disable a user")
    public ResponseEntity<ApiResponse<String>> disableUser(@PathVariable Long userId) {
        adminService.setUserEnabled(userId, false);
        return ResponseEntity.ok(ApiResponse.success("User disabled"));
    }

    @PostMapping("/users/{userId}/enable")
    @Operation(summary = "Enable a user")
    public ResponseEntity<ApiResponse<String>> enableUser(@PathVariable Long userId) {
        adminService.setUserEnabled(userId, true);
        return ResponseEntity.ok(ApiResponse.success("User enabled"));
    }

    @DeleteMapping("/plugins/{pluginId}")
    @Operation(summary = "Delete a plugin (DB + GitHub)")
    public ResponseEntity<ApiResponse<String>> deletePlugin(@PathVariable String pluginId) {
        adminService.deletePlugin(pluginId);
        return ResponseEntity.ok(ApiResponse.success("Plugin deleted"));
    }

    public record CreateUserRequest(
            String username,
            String email,
            String password,
            String role
    ) {}
}
