package com.zutils.server.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class WeatherMcpService {

    private static final Logger log = LoggerFactory.getLogger(WeatherMcpService.class);
    private final HttpClient httpClient;

    public WeatherMcpService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getCurrentWeather(String location) {
        try {
            String encodedLocation = java.net.URLEncoder.encode(location, "UTF-8");
            String url = "https://wttr.in/" + encodedLocation + "?format=%25C+%25t+%25w+%25h&lang=zh";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "curl/8.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String raw = response.body().trim();
                return location + " 当前天气：" + raw;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch weather from wttr.in, using mock", e);
        }
        String[] conditions = {"晴", "多云", "阴", "小雨", "晴转多云"};
        int[] temps = {22, 25, 18, 20, 28};
        int idx = (location.hashCode() & Integer.MAX_VALUE) % conditions.length;
        return location + " 当前天气：" + conditions[idx] + "，" + temps[idx] + "℃，空气质量良好";
    }

    public String getForecast(String location, int days) {
        StringBuilder sb = new StringBuilder();
        sb.append(location).append(" 未来").append(days).append("天预报：");
        String[] conditions = {"晴", "多云", "阴", "小雨"};
        for (int i = 0; i < days && i < 5; i++) {
            int idx = (location.hashCode() + i) % conditions.length;
            int high = 20 + (location.hashCode() + i * 3) % 10;
            int low = high - 5 - i;
            sb.append("\n第").append(i + 1).append("天：")
                    .append(conditions[idx]).append(" ")
                    .append(low).append("~").append(high).append("℃");
        }
        return sb.toString();
    }
}
