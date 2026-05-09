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
            // Collect marketplace plugins with full metadata
            List<PluginManifestResponse> manifest = collectPluginManifest();
            Map<String, PluginManifestResponse> dexByFunction = new LinkedHashMap<>();
            for (PluginManifestResponse p : manifest) {
                dexByFunction.put(p.getFunctionName(), p);
            }

            List<FunctionDef> functions = buildFunctionDefs(manifest);
            log.info("Marketplace plugins: {}", functions.size());

            // Add MCP tools (server-managed, not sent by Android)
            functions.addAll(buildMcpFunctionDefs());
            log.info("After adding MCP tools: {}", functions.size());

            // Merge with built-in functions from Android device
            if (builtinFunctions != null) {
                log.info("Built-in functions from device: {}", builtinFunctions.size());
                for (FunctionSchema fs : builtinFunctions) {
                    if (functions.stream().noneMatch(f -> f.name().equals(fs.name()))) {
                        List<ParamDef> params = fs.parameters() != null
                                ? fs.parameters().stream()
                                    .map(p -> new ParamDef(p.name(), p.description(), p.type(), p.required()))
                                    .toList()
                                : List.of();
                        functions.add(new FunctionDef(fs.name(), fs.description(), params));
                    }
                }
            }
            String systemPrompt = buildSystemPrompt(functions);
            String toolsJson = buildToolsJson(functions);

            String requestBody = """
                    {
                      "model": "%s",
                      "messages": [
                        {"role": "system", "content": %s},
                        {"role": "user", "content": %s}
                      ],
                      "tools": %s,
                      "tool_choice": "required",
                      "temperature": 0.1
                    }
                    """.formatted(
                    model,
                    jsonEscape(systemPrompt),
                    jsonEscape(userInput),
                    toolsJson
            );

            log.info("LLM request body:\n{}", requestBody.length() > 3000 ? requestBody.substring(0, 3000) + "..." : requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LLM API error {}: {}", response.statusCode(), response.body());
                return LlmResult.error("LLM API error: " + response.statusCode());
            }

            log.info("LLM raw response:\n{}", response.body().length() > 3000 ? response.body().substring(0, 3000) + "..." : response.body());
            return parseResponse(response.body(), userInput, dexByFunction);

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
                            dto.isRequired()
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
                List.of(new ParamDef("location", "城市名，如 北京、东京", "STRING", true),
                        new ParamDef("days", "预报天数（选填，默认1）", "NUMBER", false))));
        result.add(new FunctionDef("translate_text", "将文本翻译成目标语言",
                List.of(new ParamDef("text", "要翻译的文本", "STRING", true),
                        new ParamDef("target_lang", "目标语言代码，如 en、zh", "STRING", true))));
        result.add(new FunctionDef("news_headlines", "获取最新新闻头条，支持分类：科技、体育、财经、娱乐",
                List.of(new ParamDef("category", "新闻类别（选填）", "STRING", false),
                        new ParamDef("limit", "返回条数（选填，默认5）", "NUMBER", false))));
        result.add(new FunctionDef("geo_location", "查询 IP 地址地理位置，不传 IP 查当前设备位置",
                List.of(new ParamDef("ip", "IP 地址（选填）", "STRING", false))));
        result.add(new FunctionDef("qrcode_generate", "生成二维码图片，返回 base64 编码的 PNG",
                List.of(new ParamDef("content", "二维码内容", "STRING", true),
                        new ParamDef("size", "图片尺寸（选填，默认300）", "NUMBER", false))));
        result.add(new FunctionDef("web_search", "搜索互联网，返回网页标题、链接和摘要",
                List.of(new ParamDef("query", "搜索关键词", "STRING", true),
                        new ParamDef("limit", "返回条数（选填，默认5）", "NUMBER", false))));
        result.add(new FunctionDef("email_send", "发送邮件到指定收件人，支持HTML格式正文",
                List.of(new ParamDef("to", "收件人邮箱", "STRING", true),
                        new ParamDef("subject", "邮件主题", "STRING", true),
                        new ParamDef("body", "邮件正文（支持HTML）", "STRING", true))));
        result.add(new FunctionDef("document_summarize", "对文档内容进行摘要总结或润色优化",
                List.of(new ParamDef("content", "文档文本内容", "STRING", true),
                        new ParamDef("action", "summarize(摘要) 或 polish(润色)，默认 summarize", "STRING", false))));
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

                规则：
                1. 将用户需求拆解为有序的函数调用链，一次性返回所有步骤
                2. 每个步骤独立填写入参，从用户输入中提取
                3. 用户要求多个动作时（如"先做A再B"），必须在一次响应中返回多个 tool_calls。例如用户说"先查武汉天气再通知我"，应同时返回 weather_current(location=武汉) 和 send_notification(title=..., content=...)。
                4. 对于 send_notification 的 content 参数，根据用户意图自动生成合适的文本，不要因为用户没说具体内容就不调用。
                5. 不需要参数的函数传入空对象 {}
                6. 你必须始终通过函数调用（tool_calls）响应，禁止返回任何纯文字。
                7. 函数名称必须严格使用上面列出的名称，不能自己编造。
                 8. news_headlines 返回英文内容。如果用户要求翻译，同时调用 translate_text。translate_text 的 text 参数设为 "News: " + category 值。
                 9. 日期时间字符串（如 createCalendarEvent 的 startTime/endTime）：必须使用 ISO 8601 格式，如 2026-04-28T15:00+08:00 或 2026-04-28T07:00Z。禁止使用中文相对日期（"明天""今天""后天"），必须计算为具体日期。若无时区后缀如 2026-04-28T15:00，表示用户设备本地墙钟时间。
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
                                    .map(p -> new ParamDef(p.name(), p.description(), p.type(), p.required()))
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
                注意：news_headlines 返回英文内容，如果需要中文需要再调 translate_text。
                6. 如果调用了 news_headlines 获取到多条新闻，一次传给 translate_text 翻译全部，不要逐条翻译。
                7. 当用户问你不知道的信息（如最新消息、名人、事件等），使用 web_search 搜索。web_search 返回结果后根据内容直接回复用户。
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
    public record ParamDef(String name, String description, String type, boolean required) {}

    public record FunctionSchema(String name, String description, List<ParamSchema> parameters) {}
    public record ParamSchema(String name, String description, String type, boolean required) {}

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
