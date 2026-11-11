package openrsc.sessionproxy.authentic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SleepImageTest {

    @Test
    void aBlankWordIsOneRunPerRow() throws IOException {
        byte[] rle = runLengths(new BufferedImage(245, 40, BufferedImage.TYPE_INT_RGB));
        byte[] expected = new byte[40];
        Arrays.fill(expected, (byte) 255);
        assertArrayEquals(expected, rle);
    }

    @Test
    void aColouredPixelIsSetAndEachRowAfterTheFirstCountsAgainstTheOneAbove() throws IOException {
        BufferedImage image = new BufferedImage(245, 40, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.GREEN.getRGB());
        byte[] rle = runLengths(image);
        byte[] expected = new byte[43];
        Arrays.fill(expected, (byte) 255);
        expected[0] = 0;
        expected[1] = 1;
        expected[2] = (byte) 254;
        expected[3] = 0;
        expected[4] = (byte) 254;
        assertArrayEquals(expected, rle);
    }

    @Test
    void anUnreadableImageIsBlank() {
        byte[] out = SleepImage.toAuthentic(new byte[] {117, 1, 2, 3});
        assertEquals(117, out[0] & 0xFF);
        assertEquals(41, out.length);
    }

    private static byte[] runLengths(BufferedImage image) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(117);
        ImageIO.write(image, "png", png);
        byte[] out = SleepImage.toAuthentic(png.toByteArray());
        assertEquals(117, out[0] & 0xFF);
        return Arrays.copyOfRange(out, 1, out.length);
    }
}
