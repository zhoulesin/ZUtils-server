package com.zutils.server.service.mcp;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

@Service
public class QrMcpService {

    private static final Logger log = LoggerFactory.getLogger(QrMcpService.class);

    public String generateQrCode(String content, int size, String fgHex, String bgHex) {
        size = Math.max(50, Math.min(size, 2000));
        int fg = parseHex(fgHex, 0xFF000000);
        int bg = parseHex(bgHex, 0xFFFFFFFF);

        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.MARGIN, 1,
                    EncodeHintType.CHARACTER_SET, "UTF-8"
            );
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(new Color(bg, true));
            g.fillRect(0, 0, size, size);
            g.setColor(new Color(fg, true));
            for (int y = 0; y < bitMatrix.getHeight(); y++) {
                for (int x = 0; x < bitMatrix.getWidth(); x++) {
                    if (bitMatrix.get(x, y)) {
                        g.fillRect(x, y, 1, 1);
                    }
                }
            }
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            image.flush();

            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return "data:image/png;base64," + base64;

        } catch (Exception e) {
            log.error("QR code generation failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private int parseHex(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() == 6) {
                return 0xFF000000 | Integer.parseInt(h, 16);
            } else if (h.length() == 8) {
                return (int) Long.parseLong(h, 16);
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}
