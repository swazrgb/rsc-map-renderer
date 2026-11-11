package openrsc.sessionproxy.authentic;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

/**
 * How an authentic client (protocol 183 and later) frames its packets, the same way in both directions. A frame of
 * fewer than 160 bytes has a one-byte length and carries the payload's last byte ahead of the opcode; a longer one has
 * a two-byte length. The opcode is scrambled by the sender's {@link Isaac} once the login has set one up.
 *
 * <p>A packet here is a body: its opcode, then its payload.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/RSCProtocolDecoder.java#L147-L238">RSCProtocolDecoder.decode</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/RSCProtocolEncoderMain.java#L47-L100">RSCProtocolEncoderMain.encode</a>
 */
public final class AuthenticFrames {

    private static final int TWO_BYTE_LENGTH = 160;

    private AuthenticFrames() {}

    /**
     * Reads one frame: its body, with the opcode unscrambled by {@code isaac} when one is given; null at the end of
     * the stream.
     *
     * @throws EOFException when the stream ends inside a frame
     */
    public static byte @Nullable [] read(DataInputStream in, @Nullable Isaac isaac) throws IOException {
        int length = in.read();
        if (length < 0) {
            return null;
        }
        boolean shortFrame = length < TWO_BYTE_LENGTH;
        if (!shortFrame) {
            length = (length - TWO_BYTE_LENGTH) * 256 + in.readUnsignedByte();
        }
        if (length == 0) {
            throw new IOException("empty authentic frame");
        }
        byte[] wire = new byte[length];
        in.readFully(wire);
        byte[] body = new byte[length];
        if (shortFrame) {
            System.arraycopy(wire, 1, body, 0, length - 1);
            body[length - 1] = wire[0];
        } else {
            System.arraycopy(wire, 0, body, 0, length);
        }
        if (isaac != null) {
            body[0] = (byte) isaac.decode(body[0] & 0xFF);
        }
        return body;
    }

    /** The frame that carries {@code body}, its opcode scrambled by {@code isaac} when one is given. */
    public static byte[] write(byte[] body, @Nullable Isaac isaac) {
        int length = body.length;
        int opcode = isaac == null ? body[0] & 0xFF : isaac.encode(body[0] & 0xFF);
        if (length >= TWO_BYTE_LENGTH) {
            byte[] out = new byte[length + 2];
            out[0] = (byte) (length / 256 + TWO_BYTE_LENGTH);
            out[1] = (byte) length;
            out[2] = (byte) opcode;
            System.arraycopy(body, 1, out, 3, length - 1);
            return out;
        }
        byte[] out = new byte[length + 1];
        out[0] = (byte) length;
        if (length == 1) {
            out[1] = (byte) opcode;
            return out;
        }
        out[1] = body[length - 1];
        out[2] = (byte) opcode;
        System.arraycopy(body, 1, out, 3, length - 2);
        return out;
    }
}
