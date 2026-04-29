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
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = new ObjectMapper();
    }

    public String search(String query, int limit) {
        limit = Math.min(Math.max(limit, 1), 8);
        log.info("WebSearch: query={}, limit={}", query, limit);
        List<String> results = tryDuckDuckGo(query, limit);
        if (!results.isEmpty()) return formatResults(query, results);
        results = tryGoogleSearch(query, limit);
        if (!results.isEmpty()) return formatResults(query, results);
        return "无法搜索到结果，请稍后重试";
    }

    private String formatResults(String query, List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 ").append(query).append(" 搜索结果：\n");
        for (int i = 0; i < items.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(items.get(i));
        }
        return sb.toString();
    }

    private List<String> tryDuckDuckGo(String query, int limit) {
        try {
            String url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; ZUtils/1.0)")
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                List<String> results = new ArrayList<>();
                int idx = 0;
                int count = 0;
                while (true) {
                    // DuckDuckGo HTML results are in <a class="result__a" href="...">
                    int hrefStart = body.indexOf("class=\"result__a\"", idx);
                    if (hrefStart < 0) break;
                    int linkStart = body.indexOf("href=\"", hrefStart);
                    if (linkStart < 0) break;
                    linkStart += 6;
                    int linkEnd = body.indexOf("\"", linkStart);
                    if (linkEnd < 0) break;
                    String link = body.substring(linkStart, linkEnd);
                    // Skip ads and internal links
                    if (link.contains("//duckduckgo.com/l/")) {
                        int realUrlStart = link.indexOf("uddg=");
                        if (realUrlStart > 0) {
                            String encoded = link.substring(realUrlStart + 5);
                            int ampPos = encoded.indexOf('&');
                            if (ampPos > 0) encoded = encoded.substring(0, ampPos);
                            link = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                        }
                    }
                    // Find title
                    int titleStart = body.indexOf("result__a", hrefStart);
                    titleStart = body.indexOf(">", titleStart) + 1;
                    int titleEnd = body.indexOf("</a>", titleStart);
                    String title = body.substring(titleStart, titleEnd).replaceAll("<[^>]+>", "").trim();
                    // Find snippet
                    int snippetStart = body.indexOf("class=\"result__snippet\"", titleEnd);
                    if (snippetStart > 0) {
                        snippetStart = body.indexOf(">", snippetStart) + 1;
                        int snippetEnd = body.indexOf("</a>", snippetStart);
                        if (snippetEnd < 0) snippetEnd = body.indexOf("</span>", snippetStart);
                        if (snippetEnd > snippetStart) {
                            String snippet = body.substring(snippetStart, snippetEnd)
                                    .replaceAll("<[^>]+>", "").trim();
                            results.add(title + "\n   " + link + "\n   " + snippet);
                        }
                    } else {
                        results.add(title + "\n   " + link);
                    }
                    count++;
                    idx = titleEnd + 4;
                    if (count >= limit) break;
                }
                return results;
            }
        } catch (Exception e) {
            log.warn("DuckDuckGo search failed", e);
        }
        return List.of();
    }

    private List<String> tryGoogleSearch(String query, int limit) {
        try {
            // Use a public Google search frontend
            String url = "https://www.google.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&hl=en";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                String body = resp.body();
                List<String> results = new ArrayList<>();
                int idx = 0;
                int count = 0;
                while (true) {
                    int h3Start = body.indexOf("<h3", idx);
                    if (h3Start < 0) break;
                    int linkStart = body.indexOf("<a", h3Start);
                    if (linkStart < 0) break;
                    int hrefStart = body.indexOf("href=\"", linkStart);
                    if (hrefStart < 0) break;
                    hrefStart += 6;
                    int hrefEnd = body.indexOf("\"", hrefStart);
                    if (hrefEnd < 0) break;
                    String href = body.substring(hrefStart, hrefEnd);
                    if (!href.startsWith("http")) { idx = hrefEnd + 1; continue; }
                    int titleStart = body.indexOf(">", h3Start) + 1;
                    int titleEnd = body.indexOf("</h3>", titleStart);
                    if (titleEnd < 0) break;
                    String title = body.substring(titleStart, titleEnd).replaceAll("<[^>]+>", "").trim();
                    // Snippet
                    int snipStart = body.indexOf("<div class=\"VwiC3b", titleEnd);
                    if (snipStart > 0) {
                        snipStart = body.indexOf(">", snipStart) + 1;
                        int snipEnd = body.indexOf("</div>", snipStart);
                        if (snipEnd < 0) snipEnd = Math.min(body.indexOf("<div", snipStart), body.indexOf("</span>", snipStart));
                        if (snipEnd > snipStart) {
                            String snippet = body.substring(snipStart, snipEnd).replaceAll("<[^>]+>", "").trim();
                            results.add(title + "\n   " + href + "\n   " + snippet);
                        }
                    } else {
                        results.add(title + "\n   " + href);
                    }
                    count++;
                    idx = titleEnd + 5;
                    if (count >= limit) break;
                }
                return results;
            }
        } catch (Exception e) {
            log.warn("Google search failed", e);
        }
        return List.of();
    }
}
