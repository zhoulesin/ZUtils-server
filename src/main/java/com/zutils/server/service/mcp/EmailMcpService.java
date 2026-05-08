package com.zutils.server.service.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;

@Service
public class EmailMcpService {

    private static final Logger log = LoggerFactory.getLogger(EmailMcpService.class);
    private final JavaMailSender mailSender;

    public EmailMcpService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public String send(String to, String subject, String body) {
        if (mailSender == null) {
            return "邮件服务未配置，请在 application.yml 中设置 app.mail.* 参数";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, true);
            mailSender.send(msg);
            log.info("[Email] Sent to {} subject={}", to, subject);
            return "邮件已发送给 " + to;
        } catch (Exception e) {
            log.error("[Email] Send failed", e);
            return "邮件发送失败: " + e.getMessage();
        }
    }
}
