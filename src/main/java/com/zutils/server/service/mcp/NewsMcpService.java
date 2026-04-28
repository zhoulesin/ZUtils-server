package com.zutils.server.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class NewsMcpService {

    private static final Logger log = LoggerFactory.getLogger(NewsMcpService.class);
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public NewsMcpService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public String getHeadlines(String category, int limit) {
        limit = Math.min(Math.max(limit, 1), 10);
        try {
            String cat = switch (category.toLowerCase()) {
                case "科技", "tech" -> "technology";
                case "体育", "sports" -> "sports";
                case "财经", "business" -> "business";
                case "娱乐", "entertainment" -> "entertainment";
                default -> "top";
            };
            String url = "https://newsapi.org/v2/top-headlines?sources=google-news" +
                    "&pageSize=" + limit + "&apiKey=2d96074cd3814d6cb1ec8425c93f6bde";
            if (!cat.equals("top")) {
                url = "https://newsapi.org/v2/top-headlines?category=" + cat +
                        "&pageSize=" + limit + "&apiKey=2d96074cd3814d6cb1ec8425c93f6bde";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode articles = root.get("articles");
                if (articles != null && articles.isArray()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("📰 最新").append(category).append("新闻：\n");
                    int count = 0;
                    for (JsonNode article : articles) {
                        if (count >= limit) break;
                        String title = article.has("title") ? article.get("title").asText() : "";
                        String source = article.has("source") && article.get("source").has("name")
                                ? article.get("source").get("name").asText() : "";
                        if (!title.isEmpty()) {
                            sb.append("\n").append(count + 1).append(". ").append(title);
                            if (!source.isEmpty()) sb.append(" (").append(source).append(")");
                        }
                        count++;
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch news, using mock", e);
        }
        return category + "暂无最新新闻，请稍后重试";
    }
}
