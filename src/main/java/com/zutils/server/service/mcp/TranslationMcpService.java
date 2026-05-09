package com.zutils.server.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
public class TranslationMcpService {

    private static final Logger log = LoggerFactory.getLogger(TranslationMcpService.class);
    private final HttpClient httpClient;

    public TranslationMcpService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String translate(String text, String targetLang) {
        log.info("translate: text length={}, targetLang={}", text.length(), targetLang);
        // 按行拆分，逐段翻译后拼接（Google Translate 长文本会截断）
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                result.append("\n");
                continue;
            }
            String translated = translateSingleLine(line, targetLang);
            result.append(translated);
            if (i < lines.length - 1) result.append("\n");
        }
        log.info("translate: final result length={}", result.length());
        return result.toString();
    }

    private String translateSingleLine(String text, String targetLang) {
        try {
            String from = detectLang(text);
            String url = "https://translate.googleapis.com/translate_a/single"
                    + "?client=gtx&sl=" + from + "&tl=" + targetLang
                    + "&dt=t&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = new ObjectMapper().readTree(response.body());
                if (root.isArray() && root.size() > 0) {
                    JsonNode segment = root.get(0);
                    if (segment.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode item : segment) {
                            if (item.isArray() && item.size() > 0) {
                                sb.append(item.get(0).asText());
                            }
                        }
                        if (sb.length() > 0) return sb.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("translateSingleLine failed: {}", e.getMessage());
        }
        return text;
    }

    private String detectLang(String text) {
        if (text.matches(".*[\\u4e00-\\u9fff]+.*")) {
            return "zh-CN";
        }
        return "auto";
    }
}
