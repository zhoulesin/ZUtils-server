package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.model.dto.response.PluginManifestResponse;
import com.zutils.server.service.PluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
@Tag(name = "Plugin Manifest", description = "Client manifest API for Android app")
public class ManifestController {

    private final PluginService pluginService;

    @GetMapping("/manifest")
    @Operation(summary = "Get all approved plugins (lightweight DexSpec manifest for client)")
    public ResponseEntity<ApiResponse<List<PluginManifestResponse>>> getManifest() {
        List<PluginManifestResponse> manifest = pluginService.getManifest();
        return ResponseEntity.ok(ApiResponse.success(manifest));
    }
}
