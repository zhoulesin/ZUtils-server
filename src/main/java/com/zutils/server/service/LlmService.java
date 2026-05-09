package com.zutils.server.service;

import com.zutils.server.model.dto.ParameterDto;
import com.zutils.server.model.dto.response.PluginManifestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final PluginService pluginService;
    private final HttpClient httpClient;

    public LlmService(
            @Value("${app.llm.api-key:}") String apiKey,
            @Value("${app.llm.base-url:https://ark.cn-beijing.volces.com/api/coding/v3}") String baseUrl,
            @Value("${app.llm.model:Doubao-Seed-2.0-lite}") String model,
            PluginService pluginService) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.pluginService = pluginService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("app.llm.api-key is empty — LLM / Agent will not work until configured.");
        } else {
            log.info("LLM configured: baseUrl={}, model={}", baseUrl, model);
        }
    }

    /** 把连接类异常转成可读说明（不记录密钥）。 */
    private static String describeHttpConnectFailure(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof UnknownHostException) {
                return "无法解析大模型域名（UnknownHostException）。请检查 DNS 与 app.llm.base-url。";
            }
            if (cur instanceof ConnectException) {
                return "无法连接大模型服务（ConnectException）。请检查本机出网、公司代理/防火墙，以及当前环境能否访问火山方舟 URL。";
            }
            cur = cur.getCause();
        }
        return null;
    }

    public LlmResult parseIntent(String userInput, List<FunctionSchema> builtinFunctions) {
        if (apiKey == null || apiKey.isEmpty()) {
            return LlmResult.error("LLM API key not configured");
        }

        try {
            // Collect all functions (plugins + MCP + builtin)
            List<PluginManifestResponse> manifest = collectPluginManifest();
            Map<String, PluginManifestResponse> dexByFunction = new LinkedHashMap<>();
            for (PluginManifestResponse p : manifest) {
                dexByFunction.put(p.getFunctionName(), p);
            }

            List<FunctionDef> allFunctions = buildFunctionDefs(manifest);
            allFunctions.addAll(buildMcpFunctionDefs());
            if (builtinFunctions != null) {
                for (FunctionSchema fs : builtinFunctions) {
                    if (allFunctions.stream().noneMatch(f -> f.name().equals(fs.name()))) {
                        List<ParamDef> params = fs.parameters() != null
                                ? fs.parameters().stream()
                                    .map(p -> new ParamDef(p.name(), p.description(), p.type(), p.required(), null))
                                    .toList()
                                : List.of();
                        allFunctions.add(new FunctionDef(fs.name(), fs.description(), params));
                    }
                }
            }
            log.info("Total functions: {}", allFunctions.size());

            // === Round 1: Intent classification (轻量选函数) ===
            String functionSummary = buildFunctionSummary(allFunctions);
            List<Map<String, String>> roundOneSteps = callLlmRoundOne(userInput, functionSummary);

            if (roundOneSteps.isEmpty()) {
                log.warn("Round 1 returned empty, falling back to single-round");
                String systemPrompt = buildSystemPrompt(allFunctions);
                String toolsJson = buildToolsJson(allFunctions);
                String requestBody = """
                        {"model":"%s","messages":[{"role":"system","content":%s},{"role":"user","content":%s}],"tools":%s,"tool_choice":"required","temperature":0.1}
                        """.formatted(model, jsonEscape(systemPrompt), jsonEscape(userInput), toolsJson);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .timeout(Duration.ofSeconds(60))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return LlmResult.error("LLM API error: " + response.statusCode());
                }
                LlmResult result = parseResponse(response.body(), userInput, dexByFunction);
                if (result.isSuccess()) result = appendMissingSteps(result, userInput, allFunctions);
                return result;
            }

            // === Round 2: Parameter filling (只传选中函数的完整 schema) ===
            Set<String> selectedNames = roundOneSteps.stream()
                    .map(s -> s.get("function"))
                    .collect(Collectors.toSet());
            List<FunctionDef> selectedFunctions = allFunctions.stream()
                    .filter(f -> selectedNames.contains(f.name()))
                    .toList();
            log.info("Round 1 selected: {}, Round 2 using {} functions", selectedNames, selectedFunctions.size());

            LlmResult result = callLlmRoundTwo(userInput, selectedFunctions, dexByFunction);
            if (result.isSuccess()) {
                result = appendMissingSteps(result, userInput, allFunctions);
            }
            return result;

        } catch (Exception e) {
            log.error("LLM parse error, baseUrl={}", baseUrl, e);
            String hint = describeHttpConnectFailure(e);
            return LlmResult.error(hint != null ? hint : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private List<PluginManifestResponse> collectPluginManifest() {
        try {
            return pluginService.getManifest();
        } catch (Exception e) {
            log.warn("Failed to collect marketplace plugins", e);
            return List.of();
        }
    }

    private List<FunctionDef> buildFunctionDefs(List<PluginManifestResponse> manifest) {
        List<FunctionDef> result = new ArrayList<>();
        for (PluginManifestResponse p : manifest) {
            List<ParamDef> params = new ArrayList<>();
            if (p.getParameters() != null) {
                for (ParameterDto dto : p.getParameters()) {
                    params.add(new ParamDef(
                            dto.getName(),
                            dto.getDescription() != null ? dto.getDescription() : "",
                            dto.getType() != null ? dto.getType() : "STRING",
                            dto.isRequired(),
                            null
                    ));
                }
            }
            result.add(new FunctionDef(
                    p.getFunctionName(),
                    p.getDescription() != null ? p.getDescription() : "",
                    params
            ));
        }
        return result;
    }

    /** 构建 Server 端 MCP 工具的函数定义（与 McpController.listTools 一致）。 */
    private List<FunctionDef> buildMcpFunctionDefs() {
        List<FunctionDef> result = new ArrayList<>();
        result.add(new FunctionDef("weather_current", "查询指定城市的实时天气和未来预报",
                List.of(new ParamDef("location", "城市名，如 北京、东京", "STRING", true, null),
                        new ParamDef("days", "预报天数（选填，默认1）", "NUMBER", false, null))));
        result.add(new FunctionDef("translate_text", "将文本翻译成目标语言。当需要翻译功能时调用此函数",
                List.of(new ParamDef("text", "要翻译的文本", "STRING", true, "previous_step_result"),
                        new ParamDef("target_lang", "目标语言代码，如 en、zh", "STRING", true, null))));
        result.add(new FunctionDef("news_headlines", "获取最新新闻头条，按分类返回。返回内容为英文",
                List.of(new ParamDef("category", "新闻类别（选填）", "STRING", false, null),
                        new ParamDef("limit", "返回条数（选填，默认5）", "NUMBER", false, null))));
        result.add(new FunctionDef("geo_location", "查询 IP 地址地理位置，不传 IP 查当前设备位置",
                List.of(new ParamDef("ip", "IP 地址（选填）", "STRING", false, null))));
        result.add(new FunctionDef("qrcode_generate", "生成二维码图片，返回 base64 编码的 PNG",
                List.of(new ParamDef("content", "二维码内容", "STRING", true, null),
                        new ParamDef("size", "图片尺寸（选填，默认300）", "NUMBER", false, null))));
        result.add(new FunctionDef("web_search", "搜索互联网，返回网页标题、链接和摘要",
                List.of(new ParamDef("query", "搜索关键词", "STRING", true, null),
                        new ParamDef("limit", "返回条数（选填，默认5）", "NUMBER", false, null))));
        result.add(new FunctionDef("email_send", "发送邮件到指定收件人，支持HTML格式正文",
                List.of(new ParamDef("to", "收件人邮箱", "STRING", true, null),
                        new ParamDef("subject", "邮件主题", "STRING", true, null),
                        new ParamDef("body", "邮件正文（支持HTML）", "STRING", true, null))));
        result.add(new FunctionDef("document_summarize", "对文档内容进行摘要总结或润色优化",
                List.of(new ParamDef("content", "文档文本内容", "STRING", true, "previous_step_result"),
                        new ParamDef("action", "summarize(摘要) 或 polish(润色)，默认 summarize", "STRING", false, null))));
        return result;
    }

    private String buildSystemPrompt(List<FunctionDef> functions) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ZUtils AI 引擎的助手。你可以使用以下函数来帮助用户：\n\n");
        for (FunctionDef fn : functions) {
            sb.append("- ").append(fn.name()).append(": ").append(fn.description()).append("\n");
            for (ParamDef p : fn.parameters()) {
                sb.append("  ").append(p.name()).append(": ").append(p.type());
                if (p.required()) sb.append(" (必填)");
                sb.append(" - ").append(p.description()).append("\n");
            }
        }
        sb.append("""

                【核心规则】分析用户意图，将请求拆解为多个函数调用步骤，在一次响应中通过 tool_calls 全部返回。

                规则：
                1. 用户输入包含多个动作（"并"、"然后"、"再"、"和"等连接词），必须拆分为多个 tool_calls 同时返回。
                2. 每个步骤的参数从用户输入中直接提取，不要等待上一步结果。
                3. 翻译类请求：如果用户要求将结果翻译/转为某语言，必须同时调用对应的翻译函数，target_lang 从用户输入提取。
                4. 不需要参数的函数传入空对象 {}
                5. 必须始终通过 tool_calls 响应，禁止返回纯文字。
                6. 函数名称必须严格使用上面列出的名称，不能编造。
                7. 日期时间必须用 ISO 8601 格式，禁止中文相对日期。
                """);
        return sb.toString();
    }

    /** Round 2 专用：纯 JSON 输出模式，绕过 tool_calls 单次限制。 */
    private String buildJsonOutputPrompt(List<FunctionDef> functions) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ZUtils AI 引擎的助手。根据用户意图，为每个函数调用填写完整参数。\n\n");
        sb.append("可用函数及参数：\n\n");
        for (FunctionDef fn : functions) {
            sb.append("函数: ").append(fn.name()).append("\n");
            sb.append("描述: ").append(fn.description()).append("\n");
            sb.append("参数:\n");
            for (ParamDef p : fn.parameters()) {
                sb.append("  - ").append(p.name()).append(" (").append(p.type()).append(")");
                if (p.required()) sb.append(" 必填");
                sb.append(": ").append(p.description()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("""
                规则：
                1. 返回严格 JSON 格式，不要输出其他任何内容
                2. 每个函数调用的参数从用户输入中提取
                3. 步骤 B 的参数不依赖步骤 A 的结果，直接从用户输入提取
                4. 不需要参数的函数传入空对象 {}
                5. 函数名称必须严格使用上面列出的名称，不能编造
                6. 日期时间用 ISO 8601 格式，禁止中文相对日期

                示例输入: "搜索科技新闻并翻译成中文"
                示例输出: {"steps":[{"function":"news_headlines","args":{"category":"科技"}},{"function":"translate_text","args":{"text":"","target_lang":"zh"}}]}

                请直接返回 JSON，不要输出其他内容。
                """);
        return sb.toString();
    }

    private String buildToolsJson(List<FunctionDef> functions) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode tools = mapper.createArrayNode();
        for (FunctionDef fn : functions) {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("type", "function");
            ObjectNode func = tool.putObject("function");
            func.put("name", fn.name());
            func.put("description", fn.description() != null ? fn.description() : "");

            ObjectNode params = func.putObject("parameters");
            params.put("type", "object");
            ObjectNode props = params.putObject("properties");
            ArrayNode required = params.putArray("required");

            for (ParamDef p : fn.parameters()) {
                String pName = p.name() != null ? p.name() : "arg";
                ObjectNode prop = props.putObject(pName);
                prop.put("type", typeToJson(p.type()));
                prop.put("description", p.description() != null ? p.description() : "");
                if (p.required()) {
                    required.add(pName);
                }
            }
            tools.add(tool);
        }
        try {
            return mapper.writeValueAsString(tools);
        } catch (Exception e) {
            log.error("Failed to build tools JSON", e);
            return "[]";
        }
    }

    private String typeToJson(String type) {
        if (type == null || type.isEmpty()) return "string";
        return switch (type.toUpperCase()) {
            case "STRING" -> "string";
            case "NUMBER", "INTEGER" -> "number";
            case "BOOLEAN" -> "boolean";
            case "ARRAY" -> "array";
            case "OBJECT" -> "object";
            default -> "string";
        };
    }

    private LlmResult parseResponse(String responseBody, String userInput,
                                     Map<String, PluginManifestResponse> dexByFunction) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return LlmResult.error("LLM returned no choices");
            }

            JsonNode message = choices.get(0).get("message");
            if (message == null) {
                return LlmResult.error("LLM returned no message");
            }

            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
                String content = message.has("content") ? message.get("content").asText("") : "";
                return LlmResult.error("LLM returned no tool_calls: " + content);
            }

            List<Map<String, Object>> steps = new ArrayList<>();
            for (JsonNode tc : toolCalls) {
                JsonNode func = tc.get("function");
                if (func == null) continue;

                String name = func.has("name") ? func.get("name").asText() : "";
                String argsStr = func.has("arguments") ? func.get("arguments").asText() : "{}";

                Map<String, Object> args = new LinkedHashMap<>();
                if (!argsStr.isEmpty() && !argsStr.equals("{}")) {
                    JsonNode argsNode = mapper.readTree(argsStr);
                    if (argsNode != null && argsNode.isObject()) {
                        argsNode.fieldNames().forEachRemaining(key -> {
                            JsonNode val = argsNode.get(key);
                            if (val.isTextual()) args.put(key, val.asText());
                            else if (val.isBoolean()) args.put(key, val.asBoolean());
                            else if (val.isInt()) args.put(key, val.asInt());
                            else if (val.isLong()) args.put(key, val.asLong());
                            else if (val.isDouble()) args.put(key, val.asDouble());
                            else if (val.isFloat()) args.put(key, val.asDouble());
                            else args.put(key, val.asText());
                        });
                    }
                }

                Map<String, Object> step = new LinkedHashMap<>();
                step.put("function", name);
                step.put("args", args);
                step.putAll(classifyStep(name, dexByFunction));
                steps.add(step);
            }

            return new LlmResult(true, steps, userInput, null);

        } catch (Exception e) {
            log.error("Failed to parse LLM response", e);
            return LlmResult.error("Failed to parse LLM response: " + e.getMessage());
        }
    }

    /** 解析 Round 2 纯 JSON 输出（content 字段中的 JSON，非 tool_calls）。 */
    private LlmResult parseJsonResponse(String responseBody, String userInput,
                                         Map<String, PluginManifestResponse> dexByFunction) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);
            String content = root.path("choices").get(0).path("message").path("content").asText("");
            log.info("Round 2 content: {}", content.length() > 1500 ? content.substring(0, 1500) + "..." : content);

            JsonNode parsed = mapper.readTree(extractJsonFromText(content));
            JsonNode stepsNode = parsed.has("steps") ? parsed.get("steps") : parsed;
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                return LlmResult.error("Round 2 returned no steps: " + content);
            }

            List<Map<String, Object>> steps = new ArrayList<>();
            for (JsonNode step : stepsNode) {
                String name = step.has("function") ? step.get("function").asText() : "";
                JsonNode argsNode = step.has("args") ? step.get("args") : null;

                Map<String, Object> args = new LinkedHashMap<>();
                if (argsNode != null && argsNode.isObject()) {
                    argsNode.fieldNames().forEachRemaining(key -> {
                        JsonNode val = argsNode.get(key);
                        if (val.isTextual()) args.put(key, val.asText());
                        else if (val.isBoolean()) args.put(key, val.asBoolean());
                        else if (val.isInt()) args.put(key, val.asInt());
                        else if (val.isLong()) args.put(key, val.asLong());
                        else if (val.isDouble()) args.put(key, val.asDouble());
                        else if (val.isFloat()) args.put(key, val.asDouble());
                        else args.put(key, val.asText());
                    });
                }

                Map<String, Object> stepResult = new LinkedHashMap<>();
                stepResult.put("function", name);
                stepResult.put("args", args);
                stepResult.putAll(classifyStep(name, dexByFunction));
                steps.add(stepResult);
            }

            return new LlmResult(true, steps, userInput, null);
        } catch (Exception e) {
            log.error("Failed to parse Round 2 JSON response", e);
            return LlmResult.error("Failed to parse response: " + e.getMessage());
        }
    }

    /** 后处理：根据 ParamDef.pipelineSource 自动为步骤补全 pipeline。 */
    private LlmResult appendMissingSteps(LlmResult result, String userInput,
                                          List<FunctionDef> allFunctions) {
        // 构建函数名 → 参数定义映射
        Map<String, List<ParamDef>> funcParamDefs = new LinkedHashMap<>();
        for (FunctionDef fn : allFunctions) {
            funcParamDefs.put(fn.name(), fn.parameters());
        }

        List<Map<String, Object>> steps = result.getSteps().stream()
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
        boolean modified = false;

        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = steps.get(i);
            String funcName = (String) step.get("function");
            List<ParamDef> paramDefs = funcParamDefs.get(funcName);
            if (paramDefs == null) continue;
            if (step.containsKey("pipeline")) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) step.get("args");
            if (args == null) continue;

            Map<String, String> pipeline = new LinkedHashMap<>();
            for (ParamDef pd : paramDefs) {
                if (!"previous_step_result".equals(pd.pipelineSource())) continue;
                if (i == 0) continue;
                Object val = args.get(pd.name());
                if (val == null || "".equals(val)) {
                    pipeline.put(pd.name(), "{" + (i - 1) + ".result}");
                }
            }
            if (!pipeline.isEmpty()) {
                step.put("pipeline", pipeline);
                modified = true;
                log.info("Post-processing: added pipeline {} to step {} ({})", pipeline, i, funcName);
            }
        }

        if (!modified) {
            return result;
        }
        return LlmResult.ok(steps, result.getSummary());
    }

    // ==================== Two-round LLM: Round 1 ====================

    private String buildFunctionSummary(List<FunctionDef> functions) {
        StringBuilder sb = new StringBuilder();
        for (FunctionDef fn : functions) {
            sb.append("- ").append(fn.name()).append(": ").append(fn.description()).append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, String>> callLlmRoundOne(String userInput, String functionSummary) {
        String systemPrompt = """
                分析用户意图，确定需要调用哪些函数。

                可用函数：
                %s
                规则：
                1. 将用户请求拆解为有序的函数调用步骤
                2. 包含多个动作的请求必须拆分为多个步骤（"并"、"然后"、"再"等连接词）
                3. 翻译类请求必须同时包含翻译函数
                4. 每个步骤附带 args_hint，从用户输入中提取关键参数值
                5. 返回严格 JSON 格式，不要输出其他内容

                示例输入: "搜索科技新闻并翻译成中文"
                示例输出: {"steps":[{"function":"news_headlines","args_hint":"category=科技"},{"function":"translate_text","args_hint":"target_lang=zh"}]}
                """.formatted(functionSummary);

        String requestBody = """
                {"model":"%s","messages":[{"role":"system","content":%s},{"role":"user","content":%s}],"temperature":0.1}
                """.formatted(model, jsonEscape(systemPrompt), jsonEscape(userInput));

        log.info("LLM Round 1 request:\n{}", requestBody.length() > 2000 ? requestBody.substring(0, 2000) + "..." : requestBody);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LLM Round 1 API error {}: {}", response.statusCode(), response.body());
                return List.of();
            }

            log.info("LLM Round 1 response:\n{}", response.body().length() > 1500 ? response.body().substring(0, 1500) + "..." : response.body());

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").get(0).path("message").path("content").asText("");

            // 从 content 中提取 JSON
            JsonNode parsed = mapper.readTree(extractJsonFromText(content));
            JsonNode stepsNode = parsed.has("steps") ? parsed.get("steps") : parsed;
            if (!stepsNode.isArray()) return List.of();

            List<Map<String, String>> steps = new ArrayList<>();
            for (JsonNode step : stepsNode) {
                Map<String, String> s = new LinkedHashMap<>();
                s.put("function", step.has("function") ? step.get("function").asText() : "");
                s.put("args_hint", step.has("args_hint") ? step.get("args_hint").asText() : "");
                if (!s.get("function").isEmpty()) steps.add(s);
            }
            return steps;
        } catch (Exception e) {
            log.error("LLM Round 1 error", e);
            return List.of();
        }
    }

    private String extractJsonFromText(String text) {
        if (text == null || text.isBlank()) return "{}";
        int start = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        // 取最前面的 { 或 [
        if (start >= 0 && (arrayStart < 0 || start < arrayStart)) {
            return text.substring(start);
        }
        if (arrayStart >= 0) {
            return text.substring(arrayStart);
        }
        return text.trim();
    }

    // ==================== Two-round LLM: Round 2 ====================

    private LlmResult callLlmRoundTwo(String userInput, List<FunctionDef> selectedFunctions,
                                       Map<String, PluginManifestResponse> dexByFunction) {
        String systemPrompt = buildJsonOutputPrompt(selectedFunctions);

        // 纯 JSON 输出模式，不使用 tools/tool_choice（Doubao 单轮只支持 1 个 tool_call）
        String requestBody = """
                {
                  "model": "%s",
                  "messages": [
                    {"role": "system", "content": %s},
                    {"role": "user", "content": %s}
                  ],
                  "temperature": 0.1
                }
                """.formatted(model, jsonEscape(systemPrompt), jsonEscape(userInput));

        log.info("LLM Round 2 request:\n{}", requestBody.length() > 3000 ? requestBody.substring(0, 3000) + "..." : requestBody);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LLM Round 2 API error {}: {}", response.statusCode(), response.body());
                return LlmResult.error("LLM API error: " + response.statusCode());
            }

            log.info("LLM Round 2 response:\n{}", response.body().length() > 3000 ? response.body().substring(0, 3000) + "..." : response.body());
            return parseJsonResponse(response.body(), userInput, dexByFunction);
        } catch (Exception e) {
            log.error("LLM Round 2 error", e);
            return LlmResult.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * 简单问答：输入一句话，返回文本回复（无工具调用）。
     */
    public String simpleChat(String prompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "[LLM 未配置]";
        }
        try {
            String requestBody = """
                    {"model":"%s","messages":[{"role":"user","content":%s}],"temperature":0.3}
                    """.formatted(model, jsonEscape(prompt));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "[LLM API error: " + response.statusCode() + "]";
            JsonNode root = new ObjectMapper().readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText("[空回复]");
        } catch (Exception e) {
            log.error("simpleChat error, baseUrl={}", baseUrl, e);
            String hint = describeHttpConnectFailure(e);
            return "[LLM 调用失败: " + (hint != null ? hint : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())) + "]";
        }
    }

    /**
     * Agent 模式调用。不强制 tool_choice，LLM 可选择 text 回复或 tool_calls。
     */
    public ChatResult chat(List<Map<String, Object>> messages, List<FunctionSchema> functions) {
        if (apiKey == null || apiKey.isEmpty()) {
            return ChatResult.error("LLM API key not configured");
        }
        try {
            List<FunctionDef> funcDefs = functions != null ? functions.stream()
                    .map(fs -> new FunctionDef(fs.name(), fs.description(),
                            fs.parameters() != null ? fs.parameters().stream()
                                    .map(p -> new ParamDef(p.name(), p.description(), p.type(), p.required(), null))
                                    .toList() : List.of()))
                    .toList() : List.of();
            String toolsJson = buildToolsJson(funcDefs);
            String systemPrompt = buildAgentPrompt(funcDefs);
            StringBuilder msgsJson = new StringBuilder("[");
            msgsJson.append("{\"role\":\"system\",\"content\":%s}".formatted(jsonEscape(systemPrompt)));
            for (int i = 0; i < messages.size(); i++) {
                Map<String, Object> msg = messages.get(i);
                msgsJson.append(",{\"role\":%s,\"content\":%s}"
                        .formatted(jsonEscape((String) msg.get("role")),
                                jsonEscape((String) msg.get("content"))));
            }
            msgsJson.append("]");

            String requestBody = """
                    {"model":"%s","messages":%s,"tools":%s,"temperature":0.1}
                    """.formatted(model, msgsJson, toolsJson);

            log.info("Agent request: {}", requestBody.length() > 2000 ? requestBody.substring(0, 2000) + "..." : requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Agent API error {}: {}", response.statusCode(), response.body());
                return ChatResult.error("API error: " + response.statusCode());
            }
            log.info("Agent response: {}", response.body().length() > 1000 ? response.body().substring(0, 1000) + "..." : response.body());
            return parseChatResponse(response.body());
        } catch (Exception e) {
            log.error("Agent chat error, baseUrl={}, model={}", baseUrl, model, e);
            String hint = describeHttpConnectFailure(e);
            String msg = hint != null ? hint : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return ChatResult.error(msg);
        }
    }

    private String buildAgentPrompt(List<FunctionDef> functions) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 ZUtils 手机助手。你可以使用以下工具来帮助用户完成操作：\n\n");
        for (FunctionDef fn : functions) {
            sb.append("- ").append(fn.name()).append(": ").append(fn.description()).append("\n");
            for (ParamDef p : fn.parameters()) {
                sb.append("  ").append(p.name()).append(" (").append(p.type()).append(")");
                if (p.required()) sb.append(" 必填");
                sb.append(" - ").append(p.description()).append("\n");
            }
        }
        sb.append("""
                
                规则：
                1. 你需要调用工具来完成任务。每次只调用一个工具，看到工具结果后再决定下一步。
                2. 不要一次性返回多个 tool_calls，一次只做一个操作。
                3. 如果工具调用成功，根据结果决定是否需要继续操作。
                4. 使用中文回复用户。
                5. 任务完成后，回复一段总结文字给用户，不要再调工具。
                6. 当用户问你不知道的信息（如最新消息、名人、事件等），使用 web_search 搜索。web_search 返回结果后根据内容直接回复用户。
                """);
        return sb.toString();
    }

    private ChatResult parseChatResponse(String responseBody) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return ChatResult.error("No choices");
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null) return ChatResult.error("No message");

            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode tc = toolCalls.get(0);
                JsonNode func = tc.get("function");
                String name = func.has("name") ? func.get("name").asText() : "";
                String argsStr = func.has("arguments") ? func.get("arguments").asText() : "{}";
                Map<String, Object> args = new LinkedHashMap<>();
                if (!argsStr.isEmpty() && !argsStr.equals("{}")) {
                    JsonNode argsNode = mapper.readTree(argsStr);
                    if (argsNode != null && argsNode.isObject()) {
                        argsNode.fieldNames().forEachRemaining(key -> {
                            JsonNode val = argsNode.get(key);
                            if (val.isTextual()) args.put(key, val.asText());
                            else if (val.isNumber()) args.put(key, val.asDouble());
                            else if (val.isBoolean()) args.put(key, val.asBoolean());
                            else args.put(key, val.asText());
                        });
                    }
                }
                return new ChatResult(true, name, args, null);
            }

            String content = message.has("content") ? message.get("content").asText("") : "";
            return new ChatResult(true, null, null, content);

        } catch (Exception e) {
            log.error("Failed to parse chat response", e);
            return ChatResult.error("Parse error: " + e.getMessage());
        }
    }

    public record FunctionDef(String name, String description, List<ParamDef> parameters) {}
    public record ParamDef(String name, String description, String type, boolean required, String pipelineSource) {}

    public record FunctionSchema(String name, String description, List<ParamSchema> parameters) {}
    public record ParamSchema(String name, String description, String type, boolean required, String pipelineSource) {}

    /**
     * 公开方法：根据函数名返回执行类型（供 LlmController.chat 使用）。
     */
    public String classifyType(String functionName) {
        if (MCP_TOOL_NAMES.contains(functionName)) return "mcp";
        // 有 pluginService 可查时再补充 dex 判断；此处仅返回非 mcp
        return "local";
    }

    /** Server 端已知的 MCP 工具名集合（与 McpController.listTools 一致）。 */
    private static final Set<String> MCP_TOOL_NAMES = Set.of(
        "weather_current", "translate_text", "news_headlines",
        "geo_location", "qrcode_generate", "web_search",
        "email_send", "document_summarize"
    );

    /**
     * 分类函数执行类型，并为 DEX 插件补充元数据。
     * @param functionName 函数名
     * @param dexByFunction 按函数名索引的市场插件
     * @return {type, dexUrl, className, checksum, signature} 的 Map
     */
    private Map<String, Object> classifyStep(String functionName,
                                              Map<String, PluginManifestResponse> dexByFunction) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (MCP_TOOL_NAMES.contains(functionName)) {
            meta.put("type", "mcp");
        } else if (dexByFunction.containsKey(functionName)) {
            PluginManifestResponse p = dexByFunction.get(functionName);
            meta.put("type", "dex");
            meta.put("dexUrl", p.getDexUrl());
            meta.put("className", p.getClassName());
            meta.put("checksum", p.getChecksum());
            meta.put("signature", p.getSignature());
        } else {
            meta.put("type", "local");
        }
        return meta;
    }

    public static class ChatResult {
        private final boolean success;
        private final String toolName;
        private final Map<String, Object> toolArgs;
        private final String text;

        private ChatResult(boolean success, String toolName, Map<String, Object> toolArgs, String text) {
            this.success = success;
            this.toolName = toolName;
            this.toolArgs = toolArgs;
            this.text = text;
        }
        public static ChatResult toolCall(String name, Map<String, Object> args) {
            return new ChatResult(true, name, args, null);
        }
        public static ChatResult text(String text) {
            return new ChatResult(true, null, null, text);
        }
        public static ChatResult error(String error) {
            return new ChatResult(false, null, null, error);
        }

        public boolean isSuccess() { return success; }
        public boolean isToolCall() { return toolName != null; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getToolArgs() { return toolArgs; }
        public String getText() { return text; }
    }

    public static class LlmResult {
        private final boolean success;
        private final List<Map<String, Object>> steps;
        private final String summary;
        private final String error;

        private LlmResult(boolean success, List<Map<String, Object>> steps, String summary, String error) {
            this.success = success;
            this.steps = steps;
            this.summary = summary;
            this.error = error;
        }

        public static LlmResult ok(List<Map<String, Object>> steps, String summary) {
            return new LlmResult(true, steps, summary, null);
        }
        public static LlmResult error(String error) {
            return new LlmResult(false, List.of(), null, error);
        }

        public boolean isSuccess() { return success; }
        public List<Map<String, Object>> getSteps() { return steps; }
        public String getSummary() { return summary; }
        public String getError() { return error; }
    }
}
