package pdp.service_bron.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class QrServiceTest {

    private final QrService qr = new QrService();

    private BufferedImage decodeImage(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void producesA1024PixelWidePng() throws Exception {
        byte[] png = qr.png("https://t.me/navbat_bot?start=s_barber-house-k3x9", "Barber House");

        BufferedImage image = decodeImage(png);
        assertThat(image.getWidth()).isEqualTo(1024);
        assertThat(image.getHeight()).isEqualTo(1024 + QrService.CAPTION_HEIGHT);
        // PNG signature
        assertThat(png).startsWith(new byte[]{(byte) 0x89, 'P', 'N', 'G'});
    }

    @Test
    void theCodeDecodesBackToTheLink() throws Exception {
        String link = "https://t.me/navbat_bot?start=s_barber-house-k3x9";
        BufferedImage image = decodeImage(qr.png(link, "Barber House"));

        // Decode only the square with the code, not the caption below it.
        BufferedImage codeOnly = image.getSubimage(0, 0, 1024, 1024);
        Result result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(codeOnly))));

        assertThat(result.getText()).isEqualTo(link);
    }

    @Test
    void captionAreaIsNotBlankAndVeryLongNamesStillWork() throws Exception {
        String longName = "Juda uzun nomli sartaroshxona ".repeat(6);
        BufferedImage image = decodeImage(qr.png("https://t.me/x?start=s_a", longName));

        boolean anyDark = false;
        for (int y = 1024; y < image.getHeight() && !anyDark; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF) < 100) {
                    anyDark = true;
                    break;
                }
            }
        }
        assertThat(anyDark).as("caption text is drawn under the code").isTrue();
    }

    @Test
    void cyrillicCaptionsAreSupported() throws Exception {
        byte[] png = qr.png("https://t.me/x?start=s_a", "Барбер Хаус");

        assertThat(decodeImage(png).getWidth()).isEqualTo(1024);
    }
}
