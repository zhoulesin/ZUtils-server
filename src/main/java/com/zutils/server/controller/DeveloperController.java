package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.DeveloperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/developers")
@RequiredArgsConstructor
@Tag(name = "Developer", description = "Developer public profile")
public class DeveloperController {

    private final DeveloperService developerService;

    @GetMapping("/{memberUid}")
    @Operation(summary = "Get developer public profile by memberUid")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getProfile(@PathVariable String memberUid) {
        return ResponseEntity.ok(ApiResponse.success(developerService.getPublicProfile(memberUid)));
    }
}
