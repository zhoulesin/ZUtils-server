package com.zutils.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zutils.server.service.mcp.TranslationMcpService;
import com.zutils.server.service.mcp.WeatherMcpService;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper springObjectMapper) {
        return new JacksonMcpJsonMapper(springObjectMapper);
    }

    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider(McpJsonMapper jsonMapper) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp/message")
                .build();
    }

    @Bean
    public ServletRegistrationBean<?> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transportProvider) {
        return new ServletRegistrationBean<>(transportProvider, "/mcp/*");
    }

    @Bean
    public McpSyncServer mcpSyncServer(
            HttpServletStreamableServerTransportProvider transportProvider,
            WeatherMcpService weatherService,
            TranslationMcpService translationService) {

        var weatherSchema = new McpSchema.JsonSchema("object", Map.of(
                "location", Map.of("type", "string", "description", "城市名，如 北京、东京、London"),
                "days", Map.of("type", "number", "description", "预报天数（选填，默认1）")
        ), List.of("location"), null, null, null);

        var translateSchema = new McpSchema.JsonSchema("object", Map.of(
                "text", Map.of("type", "string", "description", "要翻译的文本"),
                "target_lang", Map.of("type", "string", "description", "目标语言代码，如 en(英语)、zh(中文)、ja(日语)")
        ), List.of("text", "target_lang"), null, null, null);

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("zutils-mcp-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("weather_current")
                                .description("查询指定城市的实时天气和未来预报")
                                .inputSchema(weatherSchema)
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> args = request.arguments();
                            String location = (String) args.get("location");
                            int days = args.containsKey("days") ? ((Number) args.get("days")).intValue() : 1;

                            String result;
                            if (days > 1) {
                                result = weatherService.getForecast(location, days);
                            } else {
                                result = weatherService.getCurrentWeather(location);
                            }

                            log.info("[MCP Tool] weather_current(location={}, days={}) → {}", location, days, result);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("translate_text")
                                .description("将文本翻译成目标语言")
                                .inputSchema(translateSchema)
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> args = request.arguments();
                            String text = (String) args.get("text");
                            String targetLang = (String) args.get("target_lang");

                            String result = translationService.translate(text, targetLang);
                            log.info("[MCP Tool] translate_text(text={}, target={}) → {}", text, targetLang, result);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .build();

        log.info("MCP Server started with tools: weather_current, translate_text");
        return server;
    }
}
