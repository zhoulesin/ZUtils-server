package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.DexGenerationService;
import com.zutils.server.service.KotlinSandboxService;
import com.zutils.server.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/test")
@RequiredArgsConstructor
@Tag(name = "Kotlin Playground", description = "Online Kotlin code execution sandbox")
public class TestController {

    private final KotlinSandboxService sandboxService;
    private final DexGenerationService dexGenerationService;
    private final StorageService storageService;

    @PostMapping("/run")
    @Operation(summary = "Compile and run Kotlin code with test arguments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> runCode(
            @RequestBody RunCodeRequest request) {

        KotlinSandboxService.TestResult result = sandboxService.compileAndRun(
                request.code(), request.args() != null ? request.args() : Map.of());

        Map<String, Object> body = Map.of(
                "success", result.isSuccess(),
                "output", result.getOutput() != null ? result.getOutput() : "",
                "error", result.getError() != null ? result.getError() : "",
                "durationMs", result.getDurationMs()
        );

        if (result.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("Execution completed", body));
        } else {
            return ResponseEntity.ok(ApiResponse.success("Execution failed", body));
        }
    }

    @PostMapping("/publish-dex")
    @Operation(summary = "Generate DEX plugin from tested Kotlin code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishDex(
            @RequestBody PublishDexRequest request) {

        // 1. Validate code compiles correctly
        KotlinSandboxService.TestResult validation = sandboxService.compileAndRun(
                request.code(), request.testArgs() != null ? request.testArgs() : Map.of());
        if (!validation.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("Validation failed", Map.of(
                    "success", false, "error", validation.getError())));
        }

        // 2. Generate DEX
        DexGenerationService.DexResult dex = dexGenerationService.generate(
                request.code(), request.functionName(), request.className());
        if (!dex.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success("DEX generation failed", Map.of(
                    "success", false, "error", dex.getError())));
        }

        // 3. Save DEX file to storage (creates a temp file for storage)
        String dexFilename = "plugin_" + request.functionName() + "_v1.0.0.dex";
        String dexUrl = storageService.store(dex.getDexBytes(), dexFilename);

        Map<String, Object> body = Map.of(
                "success", true,
                "dexUrl", dexUrl,
                "dexSize", dex.getSize(),
                "className", "com.zutils.generated." + request.className(),
                "functionName", request.functionName()
        );

        return ResponseEntity.ok(ApiResponse.success("DEX generated", body));
    }

    @GetMapping("/presets")
    @Operation(summary = "Get code preset templates")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPresets() {
        Map<String, String> presets = Map.of(
                "hello",
                """
                val name = args["name"]?.toString() ?: "World"
                return "Hello, $name!"
                """,
                "calculate",
                """
                val a = args["a"]?.toString()?.toIntOrNull() ?: 0
                val b = args["b"]?.toString()?.toIntOrNull() ?: 0
                val op = args["op"]?.toString() ?: "+"
                return when (op) {
                    "+" -> a + b
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b != 0) a / b else "division by zero"
                    else -> "unknown op"
                }
                """,
                "uuid",
                """
                val count = args["count"]?.toString()?.toIntOrNull() ?: 1
                return (1..count).map { java.util.UUID.randomUUID().toString() }
                """,
                "reverse",
                """
                val text = args["text"]?.toString() ?: return ""
                return text.reversed()
                """
        );
        return ResponseEntity.ok(ApiResponse.success(presets));
    }

    public record RunCodeRequest(
            String code,
            Map<String, Object> args
    ) {}

    public record PublishDexRequest(
            String code,
            String functionName,
            String className,
            Map<String, Object> testArgs
    ) {}
}
