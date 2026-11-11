package openrsc.sessionproxy.authentic;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import openrsc.bot.core.codec.Huffman;

/**
 * Writes a packet body: its opcode, then its payload, big-endian.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/PacketBuilder.java">PacketBuilder</a>
 */
final class PacketWriter {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    PacketWriter(int opcode) {
        bytes.write(opcode);
    }

    PacketWriter u8(int value) {
        bytes.write(value);
        return this;
    }

    PacketWriter u16(int value) {
        bytes.write(value >> 8);
        bytes.write(value);
        return this;
    }

    PacketWriter i32(int value) {
        return u16(value >>> 16).u16(value);
    }

    PacketWriter i64(long value) {
        return i32((int) (value >>> 32)).i32((int) value);
    }

    /**
     * An amount in two bytes up to 32767, and in four, with the top bit set, past that.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/PacketBuilder.java#L96-L103">PacketBuilder.writeUnsignedShortInt</a>
     */
    PacketWriter shortOrInt(int value) {
        int v = value & Integer.MAX_VALUE;
        return v <= Short.MAX_VALUE ? u16(v) : i32(Integer.MIN_VALUE + v);
    }

    PacketWriter bytes(byte[] from) {
        bytes.write(from, 0, from.length);
        return this;
    }

    /** A string ended with a newline, as the custom protocol writes one. */
    PacketWriter line(String text) {
        bytes(text.getBytes(StandardCharsets.ISO_8859_1));
        bytes.write('\n');
        return this;
    }

    /**
     * A string between two zero bytes, as protocol 235 writes one.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/PacketBuilder.java#L309-L313">PacketBuilder.writeZeroQuotedString</a>
     */
    PacketWriter zeroQuoted(String text) {
        bytes.write(0);
        bytes(text.getBytes(StandardCharsets.ISO_8859_1));
        bytes.write(0);
        return this;
    }

    /**
     * Chat text: its length in one or two bytes, then the text Huffman-coded.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/PacketBuilder.java#L291-L307">PacketBuilder.writeRSCString</a>
     */
    PacketWriter chat(String text) {
        int length = text.length();
        if (length < 128) {
            u8(length);
        } else {
            u16(length + 32768);
        }
        return bytes(Huffman.encode(text));
    }

    byte[] toByteArray() {
        return bytes.toByteArray();
    }
}
