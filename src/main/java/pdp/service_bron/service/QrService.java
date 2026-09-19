package pdp.service_bron.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/** Builds the QR code PNG of a shop link: 1024 px wide, the shop name printed under the code. */
@Service
public class QrService {

    public static final int SIZE = 1024;
    static final int CAPTION_HEIGHT = 140;
    private static final int CAPTION_MARGIN = 48;
    private static final int MAX_FONT = 64;
    private static final int MIN_FONT = 20;

    static {
        // The server has no display; Java2D must work without one.
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * @param content the text encoded in the QR code (the client link)
     * @param caption printed under the code, usually the shop name
     */
    public byte[] png(String content, String caption) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE, SIZE, Map.of(
                    EncodeHintType.MARGIN, 2,
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, "UTF-8"));
            BufferedImage qr = MatrixToImageWriter.toBufferedImage(matrix);

            BufferedImage canvas = new BufferedImage(SIZE, SIZE + CAPTION_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = canvas.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                g.drawImage(qr, 0, 0, null);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                drawCentered(g, caption == null ? "" : caption);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(canvas, "png", out)) {
                throw new IllegalStateException("No PNG writer available");
            }
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Cannot build the QR code", e);
        }
    }

    /** Draws the caption centered under the code, shrinking the font until the text fits the width. */
    private void drawCentered(Graphics2D g, String text) {
        int available = SIZE - 2 * CAPTION_MARGIN;
        int size = MAX_FONT;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, size);
        FontMetrics metrics = g.getFontMetrics(font);
        while (size > MIN_FONT && metrics.stringWidth(text) > available) {
            size -= 2;
            font = new Font(Font.SANS_SERIF, Font.BOLD, size);
            metrics = g.getFontMetrics(font);
        }
        String shown = text;
        while (shown.length() > 1 && metrics.stringWidth(shown) > available) {
            shown = shown.substring(0, shown.length() - 2) + "…";
        }
        g.setFont(font);
        int x = (SIZE - metrics.stringWidth(shown)) / 2;
        int y = SIZE + (CAPTION_HEIGHT + metrics.getAscent() - metrics.getDescent()) / 2 - 8;
        g.drawString(shown, Math.max(x, 0), y);
    }
}
