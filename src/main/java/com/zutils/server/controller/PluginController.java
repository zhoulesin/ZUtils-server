package com.zutils.server.controller;

import com.zutils.server.model.dto.DependencyDto;
import com.zutils.server.model.dto.ParameterDto;
import com.zutils.server.model.dto.request.CreatePluginRequest;
import com.zutils.server.model.dto.request.CreateVersionRequest;
import com.zutils.server.model.dto.response.*;
import com.zutils.server.model.entity.Plugin;
import com.zutils.server.model.entity.PluginVersion;
import com.zutils.server.security.DeveloperDetails;
import com.zutils.server.service.DexGenerationService;
import com.zutils.server.service.GithubStorageService;
import com.zutils.server.service.KotlinSandboxService;
import com.zutils.server.service.PluginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Plugin Marketplace", description = "Plugin marketplace APIs")
public class PluginController {

    private final PluginService pluginService;
    private final KotlinSandboxService sandboxService;
    private final DexGenerationService dexGenerationService;
    private final GithubStorageService githubStorageService;

    @GetMapping
    @Operation(summary = "Get plugin marketplace list (paginated)")
    public ResponseEntity<ApiResponse<Page<PluginListResponse>>> getPlugins(
            @RequestParam(required = false) String category,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        Page<PluginListResponse> plugins = pluginService.getPlugins(category, pageable);
        return ResponseEntity.ok(ApiResponse.success(plugins));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get plugin detail")
    public ResponseEntity<ApiResponse<PluginDetailResponse>> getPlugin(@PathVariable String id) {
        PluginDetailResponse plugin = pluginService.getPlugin(id);
        return ResponseEntity.ok(ApiResponse.success(plugin));
    }

    @PostMapping
    @Operation(summary = "Create a new plugin (requires developer auth)")
    public ResponseEntity<ApiResponse<Plugin>> createPlugin(
            @Valid @RequestBody CreatePluginRequest request,
            @AuthenticationPrincipal DeveloperDetails principal) {
        Plugin plugin = pluginService.createPlugin(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Plugin created", plugin));
    }

    @GetMapping("/{pluginId}/versions")
    @Operation(summary = "Get version history of a plugin")
    public ResponseEntity<ApiResponse<java.util.List<VersionResponse>>> getVersions(@PathVariable String pluginId) {
        var versions = pluginService.getVersions(pluginId);
        return ResponseEntity.ok(ApiResponse.success(versions));
    }

    @PostMapping(value = "/{pluginId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Publish a new plugin version (requires developer auth)")
    public ResponseEntity<ApiResponse<VersionResponse>> createVersion(
            @PathVariable String pluginId,
            @RequestPart("metadata") @Valid CreateVersionRequest request,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal DeveloperDetails principal) {
        PluginVersion version = pluginService.createVersion(principal.getId(), pluginId, request, file);
        VersionResponse response = VersionResponse.builder()
                .id(version.getId())
                .pluginId(pluginId)
                .version(version.getVersion())
                .dexUrl(version.getDexUrl())
                .dexSize(version.getDexSize())
                .checksum(version.getChecksum())
                .className(version.getClassName())
                .parameters(request.getParameters())
                .requiredPermissions(request.getRequiredPermissions())
                .dependencies(request.getDependencies())
                .changelog(version.getChangelog())
                .status(version.getStatus().name())
                .publishedAt(version.getPublishedAt().toString())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Version published", response));
    }

    @GetMapping("/{pluginId}/versions/{version}/dex")
    @Operation(summary = "Get DEX file download URL for a specific version")
    public ResponseEntity<ApiResponse<java.util.Map<String, String>>> getDexUrl(
            @PathVariable String pluginId,
            @PathVariable String version) {
        String dexUrl = pluginService.getDexUrl(pluginId, version);
        return ResponseEntity.ok(ApiResponse.success(
                java.util.Map.of("dexUrl", dexUrl)));
    }

    @PostMapping("/from-playground")
    @Operation(summary = "Publish plugin from Playground code (requires developer auth)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishFromPlayground(
            @RequestBody PlaygroundPublishRequest request,
            @AuthenticationPrincipal DeveloperDetails principal) {

        // 1. Validate code
        KotlinSandboxService.TestResult validation = sandboxService.compileAndRun(
                request.code(), request.testArgs() != null ? request.testArgs() : Map.of());
        if (!validation.isSuccess()) {
            String valErr = validation.getError() != null ? validation.getError() : "Unknown error";
            return ResponseEntity.ok(ApiResponse.success("Validation failed", Map.of(
                    "success", false, "error", valErr)));
        }

        // 2. Generate DEX
        DexGenerationService.DexResult dex = dexGenerationService.generate(
                request.code(), request.functionName(), request.className());
        if (!dex.isSuccess()) {
            String dexErr = dex.getError() != null ? dex.getError() : "Unknown error";
            return ResponseEntity.ok(ApiResponse.success("DEX generation failed", Map.of(
                    "success", false, "error", dexErr)));
        }

        // 3. Upload to GitHub if token is configured
        if (githubStorageService.isConfigured()) {
            try {
                githubStorageService.uploadDex(request.functionName(), dex.getDexBytes());
                githubStorageService.addToManifest(
                        request.functionName(), request.description(),
                        "com.zutils.generated." + request.className(),
                        request.parameters());
            } catch (Exception e) {
                log.error("GitHub upload failed", e);
                return ResponseEntity.ok(ApiResponse.success("GitHub upload failed", Map.of(
                        "success", false, "error", e.getMessage())));
            }
        }

        // 4. Create plugin
        String pluginId = "plugin_" + request.functionName();
        CreatePluginRequest pluginReq = new CreatePluginRequest();
        pluginReq.setId(pluginId);
        pluginReq.setFunctionName(request.functionName());
        pluginReq.setDescription(request.description());
        pluginReq.setCategory(request.category());
        pluginReq.setAuthor(principal.getUsername());
        pluginReq.setMinAppVersion("1.0.0");
        Plugin plugin = pluginService.createPlugin(principal.getId(), pluginReq);

        // 5. Create version with DEX (using inline MultipartFile adapter)
        String versionStr = "1.0.0";
        String className = "com.zutils.generated." + request.className();
        CreateVersionRequest versionReq = new CreateVersionRequest();
        versionReq.setVersion(versionStr);
        versionReq.setClassName(className);
        versionReq.setParameters(request.parameters());
        versionReq.setRequiredPermissions(List.of());
        versionReq.setDependencies(List.of());
        versionReq.setChangelog("Generated from Playground");

        MultipartFile dexFile = new ByteArrayMultipartFile(
                "file", pluginId + "_v" + versionStr + ".dex",
                "application/octet-stream", dex.getDexBytes());

        PluginVersion version = pluginService.createVersion(
                principal.getId(), pluginId, versionReq, dexFile);
        String dexUrl = githubStorageService.isConfigured()
                ? githubStorageService.getRawBaseUrl() + "/dex/" + pluginId + "_v" + versionStr + ".dex"
                : version.getDexUrl();

        // 6. Return result
        Map<String, Object> body = Map.of(
                "success", true,
                "pluginId", pluginId,
                "dexUrl", dexUrl,
                "dexSize", dex.getSize(),
                "className", "com.zutils.generated." + request.className(),
                "functionName", request.functionName()
        );
        return ResponseEntity.ok(ApiResponse.success("Plugin published", body));
    }

    public record PlaygroundPublishRequest(
            String code,
            String functionName,
            String description,
            String category,
            String className,
            List<ParameterDto> parameters,
            Map<String, Object> testArgs
    ) {}

    private static class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] content;

        ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.content = content;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }
}
