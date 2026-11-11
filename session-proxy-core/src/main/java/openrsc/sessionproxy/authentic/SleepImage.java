package openrsc.sessionproxy.authentic;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Turns the sleep word a custom client is sent, a PNG, into the run-length coded 255 by 40 bitmap an authentic
 * client draws, as the server codes its pre-rendered sleep words for one.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/CaptchaGenerator.java#L218-L330">CaptchaGenerator</a>
 */
final class SleepImage {

    private static final int OPCODE = 117;
    private static final int WIDTH = 255;
    private static final int HEIGHT = 40;
    // The authentic client draws the last column wrong: an image is scaled to end before it, or the column is kept
    // the colour of its top pixel.
    private static final int BUGGY_COLUMN = 254;

    private SleepImage() {}

    /** The 235 packet for the custom sleep-screen packet {@code body}; a blank image when its PNG cannot be read. */
    static byte[] toAuthentic(byte[] body) {
        boolean[][] set = new boolean[WIDTH][HEIGHT];
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(body, 1, body.length - 1));
            if (image != null) {
                image = resizeSanitize(image);
                for (int y = 0; y < image.getHeight() && y < HEIGHT; y++) {
                    for (int x = 0; x < image.getWidth() && x < WIDTH; x++) {
                        set[x][y] = isSet(image.getRGB(x, y));
                    }
                }
            }
        } catch (IOException e) {
            // A blank image: the player can still answer, or wake by other means.
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(OPCODE);
        byte[] rle = runLengths(set);
        out.write(rle, 0, rle.length);
        return out.toByteArray();
    }

    // The server tests for near-white, as its pre-rendered words are white on black; the words it draws for a custom
    // client are in bright colours on black, so a pixel counts as set when any channel is bright.
    private static boolean isSet(int argb) {
        return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF)) >= 128;
    }

    // The first row runs horizontally, alternating colours from unset; each later row counts the pixels that match
    // the one above.
    private static byte[] runLengths(boolean[][] set) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean last = false;
        int length = 0;
        for (int x = 0; x < WIDTH; x++) {
            if (set[x][0] == last) {
                length++;
            } else {
                out.write(length);
                length = 1;
                last = !last;
            }
        }
        out.write(length);
        for (int y = 1; y < HEIGHT; y++) {
            length = 0;
            for (int x = 0; x < WIDTH; x++) {
                if (set[x][y] == set[x][y - 1]) {
                    length++;
                } else {
                    out.write(length);
                    length = 0;
                }
            }
            out.write(length);
        }
        return out.toByteArray();
    }

    private static BufferedImage resizeSanitize(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 260 && height <= 45) {
            if (width <= BUGGY_COLUMN) {
                return image;
            }
            int top = image.getRGB(BUGGY_COLUMN, 0);
            int[] column = new int[height];
            Arrays.fill(column, top);
            image.setRGB(BUGGY_COLUMN, 0, 1, height, column, 0, 1);
            return image;
        }
        BufferedImage scaled = new BufferedImage(BUGGY_COLUMN, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.drawImage(image.getScaledInstance(BUGGY_COLUMN, HEIGHT, Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();
        return scaled;
    }
}
