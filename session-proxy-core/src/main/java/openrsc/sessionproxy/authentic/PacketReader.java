package openrsc.sessionproxy.authentic;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Reads a packet's payload, big-endian; a read past its end throws {@link BufferUnderflowException}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/Packet.java#L160-L182">Packet.readString</a>
 */
final class PacketReader {

    private final ByteBuffer in;

    /** The payload of {@code body}: everything after its opcode. */
    PacketReader(byte[] body) {
        this.in = ByteBuffer.wrap(body, 1, body.length - 1);
    }

    int u8() {
        return in.get() & 0xFF;
    }

    int s16() {
        return in.getShort();
    }

    int u16() {
        return in.getShort() & 0xFFFF;
    }

    int i32() {
        return in.getInt();
    }

    long i64() {
        return in.getLong();
    }

    int remaining() {
        return in.remaining();
    }

    byte[] bytes(int length) {
        byte[] out = new byte[length];
        in.get(out);
        return out;
    }

    byte[] rest() {
        return bytes(in.remaining());
    }

    /** A string the custom protocol ends with a newline; one that runs to the end of the packet ends there. */
    String line() {
        StringBuilder out = new StringBuilder();
        while (in.hasRemaining()) {
            byte b = in.get();
            if (b == '\n') {
                break;
            }
            out.append((char) (b & 0xFF));
        }
        return out.toString();
    }

    /** A string between two zero bytes; without the opening zero, one byte is read and the string is empty. */
    String zeroQuoted() {
        if (in.get() != 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        while (in.hasRemaining()) {
            byte b = in.get();
            if (b == 0) {
                break;
            }
            out.append((char) (b & 0xFF));
        }
        return out.toString();
    }
}
