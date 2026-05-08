package com.zutils.server.service.mcp;

import com.zutils.server.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentMcpService {

    private static final Logger log = LoggerFactory.getLogger(DocumentMcpService.class);
    private final LlmService llmService;

    public DocumentMcpService(LlmService llmService) {
        this.llmService = llmService;
    }

    public String summarize(String content) {
        try {
            String prompt = "请用中文简洁摘要以下文档内容，控制在200字以内：\n\n" + content;
            String result = llmService.simpleChat(prompt);
            log.info("[Document] Summarized to {} chars", result != null ? result.length() : 0);
            return result != null ? result : "[摘要生成失败]";
        } catch (Exception e) {
            log.error("[Document] Summarize failed", e);
            return "[摘要服务暂不可用] " + e.getMessage();
        }
    }

    public String polish(String content) {
        try {
            String prompt = "请对以下文本进行润色和优化，保持原意不变：\n\n" + content;
            String result = llmService.simpleChat(prompt);
            return result != null ? result : "[润色失败]";
        } catch (Exception e) {
            log.error("[Document] Polish failed", e);
            return "[润色服务暂不可用] " + e.getMessage();
        }
    }
}
