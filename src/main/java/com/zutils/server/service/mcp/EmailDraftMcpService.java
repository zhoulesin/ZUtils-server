package com.zutils.server.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EmailDraftMcpService {

    private static final Logger log = LoggerFactory.getLogger(EmailDraftMcpService.class);
    private final File draftsDir;
    private final ObjectMapper objectMapper;

    public EmailDraftMcpService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.draftsDir = new File("drafts");
        if (!draftsDir.exists()) {
            draftsDir.mkdirs();
        }
    }

    public String save(String to, String subject, String body) {
        try {
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("to", to);
            draft.put("subject", subject);
            draft.put("body", body);
            draft.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            String filename = "draft_" + System.currentTimeMillis() + ".json";
            Path path = draftsDir.toPath().resolve(filename);
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(draft));

            log.info("[EmailDraft] Saved draft {} → {}", filename, to);
            return "邮件草稿已保存: 收件人=" + to + ", 主题=" + subject + ", 草稿文件=" + filename;
        } catch (IOException e) {
            log.error("[EmailDraft] Save failed", e);
            return "保存草稿失败: " + e.getMessage();
        }
    }

    public String list() {
        File[] files = draftsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return "暂无草稿";
        }
        StringBuilder sb = new StringBuilder("邮件草稿列表:\n");
        for (File f : files) {
            try {
                Map<?, ?> draft = objectMapper.readValue(f, Map.class);
                sb.append("- ").append(draft.get("subject"))
                        .append(" → ").append(draft.get("to"))
                        .append(" (").append(draft.get("createdAt")).append(")\n");
            } catch (IOException e) {
                sb.append("- ").append(f.getName()).append(" (读取失败)\n");
            }
        }
        return sb.toString();
    }
}
