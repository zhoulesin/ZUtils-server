package com.zutils.server.controller;

import com.zutils.server.model.dto.response.ApiResponse;
import com.zutils.server.service.LlmService;
import com.zutils.server.service.mcp.GeoMcpService;
import com.zutils.server.service.mcp.NewsMcpService;
import com.zutils.server.service.mcp.QrMcpService;
import com.zutils.server.service.mcp.TranslationMcpService;
import com.zutils.server.service.mcp.WeatherMcpService;
import com.zutils.server.service.mcp.WebSearchMcpService;
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
    private final NewsMcpService newsService;
    private final GeoMcpService geoService;
    private final QrMcpService qrService;
    private final WebSearchMcpService webSearchService;
    private final LlmService llmService;

    public McpController(
            WeatherMcpService weatherService,
            TranslationMcpService translationService,
            NewsMcpService newsService,
            GeoMcpService geoService,
            QrMcpService qrService,
            WebSearchMcpService webSearchService,
            LlmService llmService) {
        this.weatherService = weatherService;
        this.translationService = translationService;
        this.newsService = newsService;
        this.geoService = geoService;
        this.qrService = qrService;
        this.webSearchService = webSearchService;
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
                ),
                Map.of(
                        "name", "news_headlines",
                        "description", "获取最新新闻头条，支持分类：科技、体育、财经、娱乐",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "category", Map.of("type", "string", "description", "新闻类别（选填，默认综合）"),
                                        "limit", Map.of("type", "number", "description", "返回条数（选填，默认5）")
                                ),
                                "required", List.of()
                        )
                ),
                Map.of(
                        "name", "geo_location",
                        "description", "查询 IP 地址的地理位置信息，不传 IP 则查询当前设备位置",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "ip", Map.of("type", "string", "description", "IP 地址（选填）")
                                ),
                                "required", List.of()
                        )
                ),
                Map.of(
                        "name", "qrcode_generate",
                        "description", "生成二维码图片，返回 base64 编码的 PNG 图片",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "content", Map.of("type", "string", "description", "二维码内容"),
                                        "size", Map.of("type", "number", "description", "图片尺寸（选填，默认300）")
                                ),
                                "required", List.of("content")
                        )
                ),
                Map.of(
                        "name", "web_search",
                        "description", "搜索互联网，返回网页标题、链接和摘要",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "搜索关键词"),
                                        "limit", Map.of("type", "number", "description", "返回条数（选填，默认5）")
                                ),
                                "required", List.of("query")
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
                case "news_headlines" -> {
                    String category = request.arguments().containsKey("category")
                            ? (String) request.arguments().get("category") : "综合";
                    int limit = request.arguments().containsKey("limit")
                            ? ((Number) request.arguments().get("limit")).intValue() : 5;
                    yield newsService.getHeadlines(category, limit);
                }
                case "geo_location" -> {
                    if (request.arguments().containsKey("ip")
                            && !((String) request.arguments().get("ip")).isBlank()) {
                        yield geoService.queryIp((String) request.arguments().get("ip"));
                    } else {
                        yield geoService.getMyLocation();
                    }
                }
                case "qrcode_generate" -> {
                    String content = (String) request.arguments().get("content");
                    int size = request.arguments().containsKey("size")
                            ? ((Number) request.arguments().get("size")).intValue() : 300;
                    yield qrService.generateQrCode(content, size, "#000000", "#FFFFFF");
                }
                case "web_search" -> {
                    String query = (String) request.arguments().get("query");
                    int limit = request.arguments().containsKey("limit")
                            ? ((Number) request.arguments().get("limit")).intValue() : 5;
                    yield webSearchService.search(query, limit);
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
                        )),
                new LlmService.FunctionSchema(
                        "news_headlines", "获取最新新闻头条，支持分类：科技、体育、财经、娱乐",
                        List.of(
                                new LlmService.ParamSchema("category", "新闻类别（选填）", "STRING", false),
                                new LlmService.ParamSchema("limit", "返回条数（选填，默认5）", "NUMBER", false)
                        )),
                new LlmService.FunctionSchema(
                        "geo_location", "查询 IP 地址地理位置，不传 IP 查当前设备位置",
                        List.of(
                                new LlmService.ParamSchema("ip", "IP 地址（选填）", "STRING", false)
                        )),
                new LlmService.FunctionSchema(
                        "qrcode_generate", "生成二维码图片，返回 base64 编码的 PNG",
                        List.of(
                                new LlmService.ParamSchema("content", "二维码内容", "STRING", true),
                                new LlmService.ParamSchema("size", "图片尺寸（选填，默认300）", "NUMBER", false)
                        )),
                new LlmService.FunctionSchema(
                        "web_search", "搜索互联网，返回网页标题、链接和摘要",
                        List.of(
                                new LlmService.ParamSchema("query", "搜索关键词", "STRING", true),
                                new LlmService.ParamSchema("limit", "返回条数（选填，默认5）", "NUMBER", false)
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
                    case "news_headlines" -> {
                        String category = args.containsKey("category") ? (String) args.get("category") : "综合";
                        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
                        yield newsService.getHeadlines(category, limit);
                    }
                    case "geo_location" -> {
                        if (args.containsKey("ip") && !((String) args.get("ip")).isBlank()) {
                            yield geoService.queryIp((String) args.get("ip"));
                        } else {
                            yield geoService.getMyLocation();
                        }
                    }
                    case "qrcode_generate" -> {
                        String content = (String) args.get("content");
                        int size = args.containsKey("size") ? ((Number) args.get("size")).intValue() : 300;
                        yield qrService.generateQrCode(content, size, "#000000", "#FFFFFF");
                    }
                    case "web_search" -> {
                        String query = (String) args.get("query");
                        int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
                        yield webSearchService.search(query, limit);
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
