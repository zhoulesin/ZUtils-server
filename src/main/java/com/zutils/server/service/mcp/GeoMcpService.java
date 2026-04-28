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

@Service
public class GeoMcpService {

    private static final Logger log = LoggerFactory.getLogger(GeoMcpService.class);
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public GeoMcpService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public String getMyLocation() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/?lang=zh-CN&fields=status,country,city,regionName,isp,query,lat,lon,timezone"))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                if ("success".equals(root.get("status").asText())) {
                    String ip = root.get("query").asText();
                    String country = root.get("country").asText();
                    String city = root.get("city").asText();
                    String region = root.get("regionName").asText();
                    String isp = root.get("isp").asText();
                    double lat = root.get("lat").asDouble();
                    double lon = root.get("lon").asDouble();
                    String tz = root.get("timezone").asText();
                    return String.format("📍 当前位置：%s%s%s\nIP：%s\n运营商：%s\n经纬度：%.4f, %.4f\n时区：%s",
                            country, region, city, ip, isp, lat, lon, tz);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get location, using mock", e);
        }
        return "无法获取当前位置信息";
    }

    public String queryIp(String ip) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json/" + ip + "?lang=zh-CN&fields=status,country,city,regionName,isp,query,lat,lon,timezone"))
                    .header("User-Agent", "ZUtils/1.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                if ("success".equals(root.get("status").asText())) {
                    String country = root.get("country").asText();
                    String city = root.get("city").asText();
                    String region = root.get("regionName").asText();
                    String isp = root.get("isp").asText();
                    return String.format("IP %s 归属地：%s%s%s，运营商：%s", ip, country, region, city, isp);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query IP", e);
        }
        return "无法查询 IP " + ip;
    }
}
