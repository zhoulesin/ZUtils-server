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
@RequestMapping("/api/v1/llm")
@Tag(name = "LLM Engine", description = "Server-side LLM intent parsing with MCP tool execution")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    private final LlmService llmService;
    private final WeatherMcpService weatherService;
    private final TranslationMcpService translationService;
    private final NewsMcpService newsService;
    private final GeoMcpService geoService;

    public LlmController(LlmService llmService,
                         WeatherMcpService weatherService,
                         TranslationMcpService translationService,
                         NewsMcpService newsService,
                         GeoMcpService geoService) {
        this.llmService = llmService;
        this.weatherService = weatherService;
        this.translationService = translationService;
        this.newsService = newsService;
        this.geoService = geoService;
    }

    private static final Set<String> MCP_TOOLS = Set.of("weather_current", "translate_text", "news_headlines", "geo_location");

    private List<LlmService.FunctionSchema> getMcpToolSchemas() {
        return List.of(
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
                                new LlmService.ParamSchema("category", "新闻类别，如 科技/体育/财经/娱乐（选填）", "STRING", false),
                                new LlmService.ParamSchema("limit", "返回条数（选填，默认5）", "NUMBER", false)
                        )),
                new LlmService.FunctionSchema(
                        "geo_location", "查询 IP 地址的地理位置信息，不传 IP 则查询当前设备位置",
                        List.of(
                                new LlmService.ParamSchema("ip", "IP 地址（选填，不填查当前设备）", "STRING", false)
                        ))
        );
    }

    private List<LlmService.FunctionSchema> getAndroidFunctionSchemas() {
        return List.of(
                new LlmService.FunctionSchema(
                        "send_notification", "发送系统通知到 Android 通知栏",
                        List.of(
                                new LlmService.ParamSchema("title", "通知标题", "STRING", true),
                                new LlmService.ParamSchema("content", "通知内容", "STRING", true)
                        )),
                new LlmService.FunctionSchema(
                        "create_automation", "创建自动化定时规则。用户说'每天X点做Y'时需要调用此函数",
                        List.of(
                                new LlmService.ParamSchema("name", "规则名称", "STRING", true),
                                new LlmService.ParamSchema("cron", "Cron 表达式，如'0 8 * * *'表示每天8点", "STRING", true),
                                new LlmService.ParamSchema("steps", "执行的步骤列表，JSON 字符串，每步包含 function/args/type", "STRING", true)
                        ))
        );
    }

    private String executeMcpTool(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "weather_current" -> {
                    String location = (String) args.get("location");
                    int days = args.containsKey("days") ? ((Number) args.get("days")).intValue() : 1;
                    yield days > 1
                            ? weatherService.getForecast(location, days)
                            : weatherService.getCurrentWeather(location);
                }
                case "translate_text" -> {
                    String text = (String) args.get("text");
                    String target = (String) args.get("target_lang");
                    yield translationService.translate(text, target);
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
                default -> "Unknown MCP tool: " + name;
            };
        } catch (Exception e) {
            log.error("MCP tool execution error: {}", name, e);
            return "Error: " + e.getMessage();
        }
    }

    @PostMapping("/parse")
    @Operation(summary = "Parse user input into a workflow using LLM function calling (supports MCP tools)")
    public ResponseEntity<ApiResponse<LlmParseResponse>> parse(@RequestBody ParseRequest request) {
        List<LlmService.FunctionSchema> deviceFunctions = request.functions() != null
                ? request.functions() : List.of();

        List<LlmService.FunctionSchema> allFunctions = new ArrayList<>();
        allFunctions.addAll(deviceFunctions);
        allFunctions.addAll(getMcpToolSchemas());
        allFunctions.addAll(getAndroidFunctionSchemas());

        LlmService.LlmResult result = llmService.parseIntent(request.input(), allFunctions);

        if (!result.isSuccess()) {
            LlmParseResponse body = new LlmParseResponse(
                    List.of(), result.getError() != null ? result.getError() : "Unknown error");
            return ResponseEntity.ok(ApiResponse.success("Parse failed", body));
        }

        List<Map<String, Object>> enrichedSteps = new ArrayList<>();
        for (Map<String, Object> step : result.getSteps()) {
            String functionName = (String) step.get("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) step.get("args");

            Map<String, Object> enriched = new LinkedHashMap<>();
            enriched.put("function", functionName);
            enriched.put("args", args);

            if (MCP_TOOLS.contains(functionName)) {
                enriched.put("type", "mcp");
                enriched.put("result", executeMcpTool(functionName, args));
                log.info("MCP tool executed: {}({}) → {}", functionName, args, enriched.get("result"));
            } else {
                enriched.put("type", "local");
            }
            enrichedSteps.add(enriched);
        }

        LlmParseResponse body = new LlmParseResponse(enrichedSteps, null);
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
