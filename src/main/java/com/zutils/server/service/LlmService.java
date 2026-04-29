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

import java.net.URI;
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
    }

    public LlmResult parseIntent(String userInput, List<FunctionSchema> builtinFunctions) {
        if (apiKey == null || apiKey.isEmpty()) {
            return LlmResult.error("LLM API key not configured");
        }

        try {
            List<FunctionDef> functions = collectFunctions();
            log.info("Marketplace plugins: {}", functions.size());
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
            return parseResponse(response.body(), userInput);

        } catch (Exception e) {
            log.error("LLM parse error", e);
            return LlmResult.error(e.getMessage());
        }
    }

    private List<FunctionDef> collectFunctions() {
        List<FunctionDef> result = new ArrayList<>();

        // Marketplace plugins (approved)
        try {
            List<PluginManifestResponse> manifest = pluginService.getManifest();
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
        } catch (Exception e) {
            log.warn("Failed to collect marketplace plugins", e);
        }

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
                8. 当你需要把上一步的结果传给下一步时，在上一步的参数中用 "{prev}" 占位。例如：用户问"美国今天科技新闻，帮我翻译成中文"，应同时返回 news_headlines(category=科技) 和 translate_text(text="{prev}", target_lang=zh)。
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

    private LlmResult parseResponse(String responseBody, String userInput) {
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

    public record FunctionDef(String name, String description, List<ParamDef> parameters) {}
    public record ParamDef(String name, String description, String type, boolean required) {}

    public record FunctionSchema(String name, String description, List<ParamSchema> parameters) {}
    public record ParamSchema(String name, String description, String type, boolean required) {}

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
