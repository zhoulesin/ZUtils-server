package com.zutils.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@Service
public class GithubStorageService {

    private static final Logger log = LoggerFactory.getLogger(GithubStorageService.class);
    private static final String API_BASE = "https://api.github.com";

    private final String token;
    private final String owner;
    private final String repo;
    private final String basePath;
    private final String cdnBaseUrl;
    private final HttpClient http;

    public GithubStorageService(
            @Value("${app.github.token:}") String token,
            @Value("${app.github.owner:zhoulesin}") String owner,
            @Value("${app.github.repo:ZUtils}") String repo,
            @Value("${app.github.path:zutils-plugins}") String path,
            @Value("${app.github.cdn-base-url:https://raw.githubusercontent.com}") String cdnBaseUrl
    ) {
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.basePath = path;
        this.cdnBaseUrl = cdnBaseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigured() {
        return token != null && !token.isEmpty();
    }

    public String getRawBaseUrl() {
        return cdnBaseUrl + "/" + owner + "/" + repo + "/main/" + basePath;
    }

    public void uploadDex(String functionName, byte[] dexBytes) {
        if (!isConfigured()) {
            throw new RuntimeException("GitHub token not configured");
        }
        try {
            String filename = "plugin_" + functionName + "_v1.0.0.dex";
            String path = basePath + "/dex/" + filename;
            String content = Base64.getEncoder().encodeToString(dexBytes);

            // Check if file exists to get SHA for update
            String sha = null;
            try {
                JsonNode existing = getFile(path);
                if (existing != null) sha = existing.get("sha").asText();
            } catch (Exception ignored) {}

            String msg = sha != null ? "Update " + filename : "Add " + filename;
            putFile(path, content, msg, sha);
            log.info("Uploaded DEX: {}", path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload DEX to GitHub", e);
        }
    }

    public void addToManifest(String functionName, String description,
                              String className, java.util.List<com.zutils.server.model.dto.ParameterDto> parameters) {
        if (!isConfigured()) {
            throw new RuntimeException("GitHub token not configured");
        }

        ObjectMapper mapper = new ObjectMapper();
        String manifestPath = basePath + "/manifest.json";
        String dexUrl = "dex/plugin_" + functionName + "_v1.0.0.dex";

        try {
            // Get current manifest
            String sha = null;
            String existingContent = null;
            try {
                JsonNode existing = getFile(manifestPath);
                if (existing != null) {
                    sha = existing.get("sha").asText();
                    String encoded = existing.get("content").asText().replace("\n", "");
                    existingContent = new String(Base64.getDecoder().decode(encoded));
                }
            } catch (Exception e) {
                log.info("manifest.json does not exist yet, creating new one");
            }

            // Build new plugin entry
            ObjectNode pluginEntry = mapper.createObjectNode();
            pluginEntry.put("functionName", functionName);
            pluginEntry.put("description", description != null ? description : "");
            pluginEntry.put("version", "1.0.0");
            pluginEntry.put("dexUrl", dexUrl);
            pluginEntry.put("className", className);

            ArrayNode paramsArray = mapper.createArrayNode();
            if (parameters != null) {
                for (com.zutils.server.model.dto.ParameterDto p : parameters) {
                    ObjectNode pObj = mapper.createObjectNode();
                    pObj.put("name", p.getName());
                    pObj.put("description", p.getDescription() != null ? p.getDescription() : "");
                    pObj.put("type", p.getType() != null ? p.getType() : "STRING");
                    pObj.put("required", p.isRequired());
                    paramsArray.add(pObj);
                }
            }
            pluginEntry.set("parameters", paramsArray);
            pluginEntry.set("dependencies", mapper.createArrayNode());

            // Merge with existing manifest
            ObjectNode manifest;
            if (existingContent != null) {
                manifest = (ObjectNode) mapper.readTree(existingContent);
            } else {
                manifest = mapper.createObjectNode();
                manifest.set("plugins", mapper.createArrayNode());
            }

            ArrayNode plugins = (ArrayNode) manifest.get("plugins");
            plugins.add(pluginEntry);

            String newContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
            String encoded = Base64.getEncoder().encodeToString(newContent.getBytes());

            putFile(manifestPath, encoded, "Add " + functionName + " plugin", sha);
            log.info("Updated manifest.json with {}", functionName);

        } catch (Exception e) {
            throw new RuntimeException("Failed to update manifest.json", e);
        }
    }

    private JsonNode getFile(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null;
        if (response.statusCode() != 200) {
            throw new RuntimeException("GitHub API error " + response.statusCode() + ": " + response.body());
        }
        return new ObjectMapper().readTree(response.body());
    }

    private void putFile(String path, String base64Content, String message, String sha) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("message", message);
        body.put("content", base64Content);
        if (sha != null) {
            body.put("sha", sha);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new RuntimeException("GitHub API error " + response.statusCode() + ": " + response.body());
        }
        log.info("GitHub PUT {} success: {}", path, response.statusCode());
    }

    public boolean fileExists(String relativePath) {
        if (!isConfigured()) return false;
        try {
            String path = basePath + "/" + relativePath;
            JsonNode result = getFile(path);
            return result != null;
        } catch (Exception e) {
            return false;
        }
    }

    public void deleteDex(String relativePath) {
        if (!isConfigured()) return;
        try {
            String path = basePath + "/" + relativePath;
            JsonNode existing = getFile(path);
            if (existing == null) return;

            String sha = existing.get("sha").asText();
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode body = mapper.createObjectNode();
            body.put("message", "Delete " + relativePath);
            body.put("sha", sha);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/repos/" + owner + "/" + repo + "/contents/" + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to delete: " + response.statusCode());
            }
            log.info("Deleted GitHub file: {}", path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete DEX from GitHub", e);
        }
    }

    public void removeFromManifest(String functionName) {
        if (!isConfigured()) return;
        try {
            String manifestPath = basePath + "/manifest.json";
            ObjectMapper mapper = new ObjectMapper();
            JsonNode existing = getFile(manifestPath);
            if (existing == null) return;

            String sha = existing.get("sha").asText();
            String encoded = existing.get("content").asText().replace("\n", "");
            String existingContent = new String(Base64.getDecoder().decode(encoded));
            JsonNode manifest = mapper.readTree(existingContent);
            com.fasterxml.jackson.databind.node.ArrayNode plugins = (com.fasterxml.jackson.databind.node.ArrayNode) manifest.get("plugins");

            java.util.List<com.fasterxml.jackson.databind.JsonNode> filtered = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode p : plugins) {
                if (!functionName.equals(p.get("functionName").asText())) {
                    filtered.add(p);
                }
            }

            com.fasterxml.jackson.databind.node.ObjectNode newManifest = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode newPlugins = mapper.createArrayNode();
            for (com.fasterxml.jackson.databind.JsonNode p : filtered) newPlugins.add(p);
            newManifest.set("plugins", newPlugins);

            String newContent = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(newManifest);
            String newEncoded = Base64.getEncoder().encodeToString(newContent.getBytes());
            putFile(manifestPath, newEncoded, "Remove " + functionName + " from manifest", sha);
            log.info("Removed {} from manifest.json", functionName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update manifest.json", e);
        }
    }
}
