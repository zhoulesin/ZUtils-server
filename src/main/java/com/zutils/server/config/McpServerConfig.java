package com.zutils.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zutils.server.service.mcp.GeoMcpService;
import com.zutils.server.service.mcp.NewsMcpService;
import com.zutils.server.service.mcp.QrMcpService;
import com.zutils.server.service.mcp.TranslationMcpService;
import com.zutils.server.service.mcp.WeatherMcpService;
import com.zutils.server.service.mcp.WebSearchMcpService;
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
            TranslationMcpService translationService,
            NewsMcpService newsService,
            GeoMcpService geoService,
            QrMcpService qrService,
            WebSearchMcpService webSearchService) {

        var weatherSchema = new McpSchema.JsonSchema("object", Map.of(
                "location", Map.of("type", "string", "description", "城市名，如 北京、东京、London"),
                "days", Map.of("type", "number", "description", "预报天数（选填，默认1）")
        ), List.of("location"), null, null, null);

        var translateSchema = new McpSchema.JsonSchema("object", Map.of(
                "text", Map.of("type", "string", "description", "要翻译的文本"),
                "target_lang", Map.of("type", "string", "description", "目标语言代码，如 en(英语)、zh(中文)、ja(日语)")
        ), List.of("text", "target_lang"), null, null, null);

        var newsSchema = new McpSchema.JsonSchema("object", Map.of(
                "category", Map.of("type", "string", "description", "新闻类别：科技/体育/财经/娱乐（选填，默认综合）"),
                "limit", Map.of("type", "number", "description", "返回条数（选填，默认5，最多10）")
        ), List.of(), null, null, null);

        var geoSchema = new McpSchema.JsonSchema("object", Map.of(
                "ip", Map.of("type", "string", "description", "IP 地址（选填，不填查当前设备位置）")
        ), List.of(), null, null, null);

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
                            String result = days > 1 ? weatherService.getForecast(location, days) : weatherService.getCurrentWeather(location);
                            log.info("[MCP] weather_current({}, {}) → {}", location, days, result);
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
                            log.info("[MCP] translate_text({}, {}) → {}", text, targetLang, result);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("news_headlines")
                                .description("获取最新新闻头条，支持分类：科技、体育、财经、娱乐")
                                .inputSchema(newsSchema)
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> args = request.arguments();
                            String category = args.containsKey("category") ? (String) args.get("category") : "综合";
                            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
                            String result = newsService.getHeadlines(category, limit);
                            log.info("[MCP] news_headlines({}, {})", category, limit);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("geo_location")
                                .description("查询 IP 地址的地理位置信息，不传 IP 则查询当前设备位置")
                                .inputSchema(geoSchema)
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> args = request.arguments();
                            String result;
                            if (args.containsKey("ip") && !((String) args.get("ip")).isBlank()) {
                                result = geoService.queryIp((String) args.get("ip"));
                            } else {
                                result = geoService.getMyLocation();
                            }
                            log.info("[MCP] geo_location({})", args.get("ip"));
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("qrcode_generate")
                                .description("生成二维码图片，返回 base64 编码的 PNG 图片")
                                .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                                        "content", Map.of("type", "string", "description", "二维码内容"),
                                        "size", Map.of("type", "number", "description", "图片尺寸（选填，默认300）"),
                                        "foreground", Map.of("type", "string", "description", "前景色 hex（选填，默认 #000000）"),
                                        "background", Map.of("type", "string", "description", "背景色 hex（选填，默认 #FFFFFF）")
                                ), List.of("content"), null, null, null))
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> args = request.arguments();
                            String content = (String) args.get("content");
                            int size = args.containsKey("size") ? ((Number) args.get("size")).intValue() : 300;
                            String fg = args.containsKey("foreground") ? (String) args.get("foreground") : "#000000";
                            String bg = args.containsKey("background") ? (String) args.get("background") : "#FFFFFF";
                            String result = qrService.generateQrCode(content, size, fg, bg);
                            log.info("[MCP] qrcode_generate(content={}, size={})", content, size);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .toolCall(
                        McpSchema.Tool.builder()
                                .name("web_search")
                                .description("搜索互联网，返回网页标题、链接和摘要")
                                .inputSchema(new McpSchema.JsonSchema("object", Map.of(
                                        "query", Map.of("type", "string", "description", "搜索关键词"),
                                        "limit", Map.of("type", "number", "description", "返回条数（选填，默认5）")
                                ), List.of("query"), null, null, null))
                                .build(),
                        (exchange, request) -> {
                            Map<String, Object> args = request.arguments();
                            String query = (String) args.get("query");
                            int limit = args.containsKey("limit") ? ((Number) args.get("limit")).intValue() : 5;
                            String result = webSearchService.search(query, limit);
                            log.info("[MCP] web_search(query={})", query);
                            return McpSchema.CallToolResult.builder()
                                    .content(List.of(new McpSchema.TextContent(result)))
                                    .build();
                        }
                )
                .build();
                        }
                )
                .build();

        log.info("MCP Server started with tools: weather_current, translate_text, news_headlines, geo_location");
        return server;
    }
}
