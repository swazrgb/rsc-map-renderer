package openrsc.bot.core.world;

import java.io.ByteArrayOutputStream;

final class BitWriter {

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private int current;
    private int filled;

    void writeBits(int value, int numBits) {
        for (int i = numBits - 1; i >= 0; i--) {
            current = (current << 1) | ((value >> i) & 1);
            if (++filled == 8) {
                buf.write(current);
                current = 0;
                filled = 0;
            }
        }
    }

    byte[] toBytes() {
        if (filled > 0) {
            buf.write(current << (8 - filled));
            current = 0;
            filled = 0;
        }
        return buf.toByteArray();
    }
}
