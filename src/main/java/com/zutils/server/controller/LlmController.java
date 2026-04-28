package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.LlmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
@Tag(name = "LLM Engine", description = "Server-side LLM intent parsing and summarization")
public class LlmController {

    private final LlmService llmService;

    @PostMapping("/parse")
    @Operation(summary = "Parse user input into a workflow using LLM function calling")
    public ResponseEntity<ApiResponse<LlmParseResponse>> parse(@RequestBody ParseRequest request) {
        List<LlmService.FunctionSchema> functions = request.functions() != null ? request.functions() : List.of();
        LlmService.LlmResult result = llmService.parseIntent(request.input(), functions);

        if (!result.isSuccess()) {
            LlmParseResponse body = new LlmParseResponse(
                    List.of(), result.getError() != null ? result.getError() : "Unknown error");
            return ResponseEntity.ok(ApiResponse.success("Parse failed", body));
        }

        LlmParseResponse body = new LlmParseResponse(result.getSteps(), null);
        return ResponseEntity.ok(ApiResponse.success("Parse completed", body));
    }

    public record ParseRequest(
            String input,
            List<LlmService.FunctionSchema> functions
    ) {}

    public record LlmParseResponse(
            List<Map<String, Object>> steps,
            String error
    ) {}
}
