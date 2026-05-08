package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.LlmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/llm")
@Tag(name = "LLM Integration", description = "LLM intent parsing and chat API for Android client")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    private final LlmService llmService;

    public LlmController(LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/parse")
    @Operation(summary = "Parse user input into function call steps (used by Android ServerLlmClient)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> parseIntent(@RequestBody Map<String, Object> body) {
        String userInput = (String) body.getOrDefault("input", "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawFunctions = (List<Map<String, Object>>) body.getOrDefault("functions", List.of());

        List<LlmService.FunctionSchema> functions = rawFunctions.stream()
                .map(this::toFunctionSchema)
                .collect(Collectors.toList());

        log.info("LLM parse: input='{}', functions={}", userInput, functions.size());

        LlmService.LlmResult result = llmService.parseIntent(userInput, functions);

        Map<String, Object> data = new LinkedHashMap<>();
        if (result.isSuccess()) {
            data.put("steps", result.getSteps());
            data.put("error", null);
        } else {
            data.put("steps", List.of());
            data.put("error", result.getError());
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/chat")
    @Operation(summary = "Chat with LLM, returns tool call or text response (used by Android ServerLlmClient)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawMessages = (List<Map<String, Object>>) body.getOrDefault("messages", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawFunctions = (List<Map<String, Object>>) body.getOrDefault("functions", List.of());

        List<LlmService.FunctionSchema> functions = rawFunctions.stream()
                .map(this::toFunctionSchema)
                .collect(Collectors.toList());

        log.info("LLM chat: messages={}, functions={}", rawMessages.size(), functions.size());

        LlmService.ChatResult result = llmService.chat(rawMessages, functions);

        Map<String, Object> data = new LinkedHashMap<>();
        if (result.isSuccess() && result.isToolCall()) {
            data.put("toolName", result.getToolName());
            data.put("toolArgs", result.getToolArgs());
            data.put("type", "local");
            data.put("text", null);
            data.put("dexUrl", null);
            data.put("className", null);
            data.put("checksum", null);
            data.put("signature", null);
        } else if (result.isSuccess()) {
            data.put("toolName", null);
            data.put("toolArgs", null);
            data.put("text", result.getText() != null ? result.getText() : "");
        } else {
            data.put("toolName", null);
            data.put("toolArgs", null);
            data.put("text", result.getText() != null ? result.getText() : "LLM 不可用");
        }

        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private LlmService.FunctionSchema toFunctionSchema(Map<String, Object> raw) {
        String name = (String) raw.getOrDefault("name", "");
        String description = (String) raw.getOrDefault("description", "");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawParams = (List<Map<String, Object>>) raw.getOrDefault("parameters", List.of());
        List<LlmService.ParamSchema> params = rawParams.stream()
                .map(p -> new LlmService.ParamSchema(
                        (String) p.getOrDefault("name", ""),
                        (String) p.getOrDefault("description", ""),
                        (String) p.getOrDefault("type", "STRING"),
                        p.containsKey("required") && Boolean.TRUE.equals(p.get("required"))
                ))
                .collect(Collectors.toList());
        return new LlmService.FunctionSchema(name, description, params);
    }
}
