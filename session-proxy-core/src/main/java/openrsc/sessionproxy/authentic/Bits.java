package openrsc.sessionproxy.authentic;

import java.io.ByteArrayOutputStream;

/**
 * The bit-packed payloads of the position packets, most significant bit first, as the server writes them.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/PacketBuilder.java#L208-L240">PacketBuilder.writeBits</a>
 */
final class Bits {

    private Bits() {}

    /** Reads bits from a payload. */
    static final class Reader {
        private final byte[] data;
        private final int offset;
        private final int end;
        private int position;

        /** The bits of {@code data} from byte {@code offset} on. */
        Reader(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
            this.end = (data.length - offset) * 8;
        }

        int remaining() {
            return end - position;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int bit = position++;
                value = (value << 1) | ((data[offset + (bit >> 3)] >> (7 - (bit & 7))) & 1);
            }
            return value;
        }
    }

    /** Writes bits, padding the last byte with zeros. */
    static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int current;
        private int used;

        Writer(int opcode) {
            bytes.write(opcode);
        }

        Writer write(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                if (++used == 8) {
                    bytes.write(current);
                    current = 0;
                    used = 0;
                }
            }
            return this;
        }

        byte[] toByteArray() {
            if (used > 0) {
                bytes.write(current << (8 - used));
                current = 0;
                used = 0;
            }
            return bytes.toByteArray();
        }
    }
}
