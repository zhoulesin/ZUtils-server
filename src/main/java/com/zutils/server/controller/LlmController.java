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

@RestController
@RequestMapping("/api/v1/llm")
@Tag(name = "LLM Engine", description = "Server-side LLM intent parsing with MCP tool execution")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    private final LlmService llmService;

    public LlmController(LlmService llmService) {
        this.llmService = llmService;
    }

    private static final Set<String> MCP_TOOLS = Set.of("weather_current", "translate_text", "news_headlines", "geo_location", "qrcode_generate");

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
                        )),
                new LlmService.FunctionSchema(
                        "qrcode_generate", "生成二维码图片，返回 base64 编码的 PNG 图片",
                        List.of(
                                new LlmService.ParamSchema("content", "二维码内容", "STRING", true),
                                new LlmService.ParamSchema("size", "图片尺寸（选填，默认300）", "NUMBER", false)
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
            enriched.put("type", MCP_TOOLS.contains(functionName) ? "mcp" : "local");
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
