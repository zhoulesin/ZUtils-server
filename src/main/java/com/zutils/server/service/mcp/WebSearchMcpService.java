package com.zutils.server.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class WebSearchMcpService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchMcpService.class);
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public WebSearchMcpService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.mapper = new ObjectMapper();
    }

    public String search(String query, int limit) {
        limit = Math.min(Math.max(limit, 1), 8);
        log.info("WebSearch: query={}", query);

        List<String> results = tryWikipedia(query, limit);
        if (!results.isEmpty()) return format("维基百科", query, results);

        results = tryDdgInstant(query, limit);
        if (!results.isEmpty()) return format("DuckDuckGo", query, results);

        return "无法搜索到结果，请稍后重试";
    }

    private String format(String source, String query, List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 ").append(query).append(" 搜索结果（").append(source).append("）：\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(items.get(i));
        }
        return sb.toString();
    }

    private List<String> tryWikipedia(String query, int limit) {
        try {
            boolean isChinese = query.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
            String wikiLang = isChinese ? "zh.wikipedia.org" : "en.wikipedia.org";
            String wikiLink = isChinese ? "https://zh.wikipedia.org/wiki/" : "https://en.wikipedia.org/wiki/";
            String url = "https://" + wikiLang + "/w/api.php?action=query&list=search&srsearch="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&srlimit=" + limit + "&format=json&origin=*";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(6))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode search = root.at("/query/search");
                if (search != null && search.isArray() && search.size() > 0) {
                    List<String> results = new ArrayList<>();
                    for (JsonNode hit : search) {
                        String title = hit.has("title") ? hit.get("title").asText() : "";
                        String snippet = hit.has("snippet") ? hit.get("snippet").asText()
                                .replaceAll("<[^>]+>", "").trim() : "";
                        String link = wikiLink + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8);
                        results.add(title + "\n   " + link + "\n   " + snippet);
                        if (results.size() >= limit) break;
                    }
                    return results;
                }
            }
        } catch (Exception e) {
            log.warn("Wikipedia search failed", e);
        }
        return List.of();
    }

    private List<String> tryDdgInstant(String query, int limit) {
        try {
            String url = "https://api.duckduckgo.com/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&format=json&no_html=1&skip_disambig=1";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                List<String> results = new ArrayList<>();

                // Abstract (instant answer)
                if (root.has("AbstractText") && !root.get("AbstractText").asText().isEmpty()) {
                    String text = root.get("AbstractText").asText();
                    String source = root.has("AbstractSource") ? root.get("AbstractSource").asText() : "";
                    String link = root.has("AbstractURL") ? root.get("AbstractURL").asText() : "";
                    results.add("📖 " + text.substring(0, Math.min(text.length(), 300))
                            + (text.length() > 300 ? "…" : ""));
                    if (!link.isEmpty()) results.add("   来源: " + source + " - " + link);
                }

                // Related topics
                if (root.has("RelatedTopics") && root.get("RelatedTopics").isArray()) {
                    for (JsonNode topic : root.get("RelatedTopics")) {
                        if (topic.has("Text")) {
                            String text = topic.get("Text").asText();
                            results.add(text.length() > 200 ? text.substring(0, 200) + "…" : text);
                            if (results.size() >= limit + 1) break;
                        }
                    }
                }
                // Remove the Abstract info from count
                int realLimit = results.size() > 1 ? limit + 1 : limit;
                return results.subList(0, Math.min(results.size(), realLimit));
            }
        } catch (Exception e) {
            log.warn("DDG instant search failed", e);
        }
        return List.of();
    }
}
