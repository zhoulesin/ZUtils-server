package com.zutils.server.service.mcp;

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
                String body = response.body();
                int start = body.indexOf("\"") + 1;
                int end = body.indexOf("\"", start);
                if (start > 0 && end > start) {
                    String translated = body.substring(start, end);
                    return translated;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to call translation API, using mock", e);
        }
        return "[模拟翻译] " + text + " (→" + targetLang + ")";
    }

    private String detectLang(String text) {
        if (text.matches(".*[\\u4e00-\\u9fff]+.*")) {
            return "zh-CN";
        }
        return "auto";
    }
}
