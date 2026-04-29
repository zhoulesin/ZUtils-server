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
        // Try NewsAPI first, fallback to RSS
        String result = tryNewsApi(category, limit);
        if (result != null) return result;
        result = tryRssFeed(category, limit);
        if (result != null) return result;
        return getMockHeadlines(category, limit);
    }

    private String tryNewsApi(String category, int limit) {
        try {
            String url;
            String cat = switch (category.toLowerCase()) {
                case "科技", "tech" -> "technology";
                case "体育", "sports" -> "sports";
                case "财经", "business" -> "business";
                case "娱乐", "entertainment" -> "entertainment";
                default -> null;
            };
            if (cat == null) {
                url = "https://newsapi.org/v2/top-headlines?country=us&pageSize=" + limit
                        + "&apiKey=2d96074cd3814d6cb1ec8425c93f6bde";
            } else {
                url = "https://newsapi.org/v2/top-headlines?category=" + cat
                        + "&pageSize=" + limit + "&apiKey=2d96074cd3814d6cb1ec8425c93f6bde";
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                if ("ok".equals(root.get("status").asText())) {
                    JsonNode articles = root.get("articles");
                    if (articles != null && articles.isArray() && articles.size() > 0) {
                        StringBuilder sb = new StringBuilder("📰 最新").append(category).append("新闻：\n");
                        int count = 0;
                        for (JsonNode article : articles) {
                            if (count >= limit) break;
                            String title = article.has("title") ? article.get("title").asText() : "";
                            if (!title.isEmpty()) {
                                sb.append("\n").append(count + 1).append(". ").append(title);
                            }
                            count++;
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("NewsAPI failed", e);
        }
        return null;
    }

    private String tryRssFeed(String category, int limit) {
        try {
            String rssUrl = switch (category.toLowerCase()) {
                case "科技", "tech" -> "https://rss.nytimes.com/services/xml/rss/nyt/Technology.xml";
                case "体育", "sports" -> "https://rss.nytimes.com/services/xml/rss/nyt/Sports.xml";
                case "财经", "business" -> "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml";
                default -> "https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml";
            };
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rssUrl))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                StringBuilder sb = new StringBuilder("📰 最新").append(category).append("新闻：\n");
                int count = 0;
                int idx = 0;
                while (true) {
                    int start = body.indexOf("<title>", idx);
                    if (start < 0) break;
                    start += 7;
                    int end = body.indexOf("</title>", start);
                    if (end < 0) break;
                    String title = body.substring(start, end).trim();
                    idx = end + 8;
                    if (title.contains("CDATA")) {
                        int cs = title.indexOf("![CDATA[");
                        if (cs >= 0) {
                            title = title.substring(cs + 8, title.lastIndexOf("]]"));
                        }
                    }
                    if (title.isEmpty() || title.startsWith("NYT")) continue;
                    if (title.equals("Technology") || title.equals("Sports") || title.equals("Business")) continue;
                    if (count >= limit) break;
                    sb.append("\n").append(count + 1).append(". ").append(title);
                    count++;
                }
                if (count > 0) return sb.toString();
            }
        } catch (Exception e) {
            log.warn("RSS failed", e);
        }
        return null;
    }

    private String getMockHeadlines(String category, int limit) {
        String[][] mockData = {
            {"科技企业加速布局AI大模型，多家公司发布新产品", "AI芯片需求暴涨，供应链持续紧张"},
            {"中国队在亚运会上再获佳绩，金牌数领先", "NBA新赛季开幕，多支球队完成重磅交易"},
            {"央行宣布降准0.5个百分点，释放长期资金", "A股三大指数集体上涨，成交额突破万亿"},
        };
        int catIdx = switch (category.toLowerCase()) {
            case "科技", "tech" -> 0;
            case "体育", "sports" -> 1;
            case "财经", "business" -> 2;
            default -> new Random().nextInt(3);
        };
        String[] headlines = mockData[Math.min(catIdx, 2)];
        StringBuilder sb = new StringBuilder("📰 ").append(category).append("新闻（模拟）：\n");
        for (int i = 0; i < Math.min(limit, headlines.length); i++) {
            sb.append("\n").append(i + 1).append(". ").append(headlines[i]);
        }
        return sb.toString();
    }
}
