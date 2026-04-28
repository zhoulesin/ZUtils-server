package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.LlmService;
import com.zutils.server.service.mcp.TranslationMcpService;
import com.zutils.server.service.mcp.WeatherMcpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/mcp")
@Tag(name = "MCP Tools", description = "MCP (Model Context Protocol) Tool 演示")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final WeatherMcpService weatherService;
    private final TranslationMcpService translationService;
    private final LlmService llmService;

    public McpController(
            WeatherMcpService weatherService,
            TranslationMcpService translationService,
            LlmService llmService) {
        this.weatherService = weatherService;
        this.translationService = translationService;
        this.llmService = llmService;
    }

    @GetMapping("/tools")
    @Operation(summary = "列出所有可用的 MCP Tool")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTools() {
        List<Map<String, Object>> tools = List.of(
                Map.of(
                        "name", "weather_current",
                        "description", "查询指定城市的实时天气和未来预报",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "location", Map.of("type", "string", "description", "城市名"),
                                        "days", Map.of("type", "number", "description", "预报天数（选填，默认1）")
                                ),
                                "required", List.of("location")
                        )
                ),
                Map.of(
                        "name", "translate_text",
                        "description", "将文本翻译成目标语言",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "text", Map.of("type", "string", "description", "要翻译的文本"),
                                        "target_lang", Map.of("type", "string", "description", "目标语言代码")
                                ),
                                "required", List.of("text", "target_lang")
                        )
                )
        );
        return ResponseEntity.ok(ApiResponse.success(tools));
    }

    @PostMapping("/call")
    @Operation(summary = "直接调用一个 MCP Tool")
    public ResponseEntity<ApiResponse<Map<String, Object>>> callTool(@RequestBody CallToolRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tool", request.tool());
        result.put("arguments", request.arguments());

        try {
            String output = switch (request.tool()) {
                case "weather_current" -> {
                    String location = (String) request.arguments().get("location");
                    int days = request.arguments().containsKey("days")
                            ? ((Number) request.arguments().get("days")).intValue() : 1;
                    yield days > 1
                            ? weatherService.getForecast(location, days)
                            : weatherService.getCurrentWeather(location);
                }
                case "translate_text" -> {
                    String text = (String) request.arguments().get("text");
                    String targetLang = (String) request.arguments().get("target_lang");
                    yield translationService.translate(text, targetLang);
                }
                default -> "未知工具: " + request.tool();
            };

            result.put("output", output);
            result.put("success", true);
            return ResponseEntity.ok(ApiResponse.success("Tool called successfully", result));

        } catch (Exception e) {
            result.put("output", null);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.ok(ApiResponse.success("Tool call failed", result));
        }
    }

    @PostMapping("/chat")
    @Operation(summary = "演示：用户输入 → LLM 发现 MCP Tool → 调用 Tool → 返回结果")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(@RequestBody ChatRequest request) {
        String userInput = request.input();
        log.info("[MCP Chat] User: {}", userInput);

        List<LlmService.FunctionSchema> mcpFunctions = List.of(
                new LlmService.FunctionSchema(
                        "weather_current", "查询指定城市的实时天气和未来预报",
                        List.of(
                                new LlmService.ParamSchema("location", "城市名，如 北京、东京", "STRING", true),
                                new LlmService.ParamSchema("days", "预报天数（选填，默认1）", "NUMBER", false)
                        )),
                new LlmService.FunctionSchema(
                        "translate_text", "将文本翻译成目标语言",
                        List.of(
                                new LlmService.ParamSchema("text", "要翻译的文本", "STRING", true),
                                new LlmService.ParamSchema("target_lang", "目标语言代码，如 en(英语)、zh(中文)", "STRING", true)
                        ))
        );

        LlmService.LlmResult llmResult = llmService.parseIntent(userInput, mcpFunctions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user_input", userInput);

        if (!llmResult.isSuccess()) {
            result.put("success", false);
            result.put("llm_error", llmResult.getError());
            return ResponseEntity.ok(ApiResponse.success("LLM parsing failed", result));
        }

        List<Map<String, Object>> steps = llmResult.getSteps();
        result.put("llm_plan", steps);

        List<Map<String, Object>> executionResults = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String functionName = (String) step.get("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) step.get("args");

            Map<String, Object> stepResult = new LinkedHashMap<>();
            stepResult.put("function", functionName);
            stepResult.put("args", args);

            try {
                String output = switch (functionName) {
                    case "weather_current" -> {
                        String location = (String) args.get("location");
                        int days = args.containsKey("days") ? ((Number) args.get("days")).intValue() : 1;
                        yield days > 1
                                ? weatherService.getForecast(location, days)
                                : weatherService.getCurrentWeather(location);
                    }
                    case "translate_text" -> {
                        String text = (String) args.get("text");
                        String targetLang = (String) args.get("target_lang");
                        yield translationService.translate(text, targetLang);
                    }
                    default -> "未知函数: " + functionName;
                };
                stepResult.put("output", output);
                stepResult.put("success", true);
            } catch (Exception e) {
                stepResult.put("output", null);
                stepResult.put("success", false);
                stepResult.put("error", e.getMessage());
            }
            executionResults.add(stepResult);
        }

        result.put("execution_results", executionResults);
        result.put("success", true);
        return ResponseEntity.ok(ApiResponse.success("Chat completed", result));
    }

    public record CallToolRequest(
            String tool,
            Map<String, Object> arguments
    ) {}

    public record ChatRequest(
            String input
    ) {}
}
