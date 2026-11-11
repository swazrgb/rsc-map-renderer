package openrsc.sessionproxy.authentic;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import openrsc.sessionproxy.ProxyKey;

/**
 * The login of an authentic client of version 205 or later, read as the server reads it: the RSA block carries the
 * four ISAAC keys and the password, and the block XTEA encrypts with those keys carries the username.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/LoginPacketHandler.java#L103-L192">LoginPacketHandler.processLogin</a>
 */
public final class AuthenticLogin {

    /** The login's opcode; a client that lost its connection may send {@link #OPCODE_RELOGIN} instead. */
    public static final int OPCODE = 0;

    public static final int OPCODE_RELOGIN = 19;

    // The first byte of a block the key decrypted, which a client always sets.
    private static final int CHECKSUM = 10;
    private static final int PASSWORD_AT = 17;
    private static final int PASSWORD_LENGTH = 20;
    private static final int USERNAME_AT = 25;

    private final boolean reconnecting;
    private final int version;
    private final int[] keys;
    private final String username;
    private final String password;

    private AuthenticLogin(boolean reconnecting, int version, int[] keys, String username, String password) {
        this.reconnecting = reconnecting;
        this.version = version;
        this.keys = keys;
        this.username = username;
        this.password = password;
    }

    /** Whether the client logs in again after losing its connection. */
    public boolean reconnecting() {
        return reconnecting;
    }

    public int version() {
        return version;
    }

    /** The ISAAC keys, which seed both directions' ciphers. */
    public int[] keys() {
        return keys.clone();
    }

    public String username() {
        return username;
    }

    /** The password as the client sent it: at most 20 characters, its spaces already turned into underscores. */
    public String password() {
        return password;
    }

    /**
     * Reads a login: {@code body} is its opcode and payload, and {@code key} the key the client encrypted it with.
     *
     * @throws IOException when it is not the login of a client of version 205 or later, or ends early
     * @throws WrongKeyException when its block was not encrypted with {@code key}
     */
    public static AuthenticLogin parse(byte[] body, ProxyKey key) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(body, 1, body.length - 1);
        try {
            int reconnect = in.get() & 0xFF;
            int zero = in.get() & 0xFF;
            if (reconnect > 1 || zero != 0) {
                throw new IOException("a client older than version 178");
            }
            int high = in.get() & 0xFF;
            int version = high != 0 && high <= 204 ? high : high << 16 | in.getShort();
            if (version <= 204) {
                throw new IOException("client version " + version + "; the proxy reads the logins of 205 and later");
            }
            byte[] block = key.decrypt(bytes(in, in.getShort() & 0xFFFF));
            if (block.length < PASSWORD_AT + PASSWORD_LENGTH || block[0] != CHECKSUM) {
                throw new WrongKeyException();
            }
            ByteBuffer keyBytes = ByteBuffer.wrap(block, 1, 16);
            int[] keys = {keyBytes.getInt(), keyBytes.getInt(), keyBytes.getInt(), keyBytes.getInt()};
            String password = new String(block, PASSWORD_AT, PASSWORD_LENGTH, StandardCharsets.UTF_8).trim();
            byte[] tail = Xtea.decrypt(bytes(in, in.getShort() & 0xFFFF), keys);
            if (tail.length < USERNAME_AT) {
                throw new IOException("login block ends before the username");
            }
            return new AuthenticLogin(reconnect == 1, version, keys, username(tail), password);
        } catch (BufferUnderflowException e) {
            throw new IOException("login packet ends early", e);
        }
    }

    // The username runs to the end of the block, which the client may have padded past a terminator.
    private static String username(byte[] tail) {
        int end = USERNAME_AT;
        while (end < tail.length && tail[end] != 0 && tail[end] != '\n') {
            end++;
        }
        return new String(tail, USERNAME_AT, end - USERNAME_AT, StandardCharsets.UTF_8).trim();
    }

    private static byte[] bytes(ByteBuffer in, int length) {
        byte[] out = new byte[length];
        in.get(out);
        return out;
    }

    /** A login whose RSA block does not decrypt with the proxy's key: the client still carries its server's. */
    public static final class WrongKeyException extends IOException {
        private static final long serialVersionUID = 1L;

        WrongKeyException() {
            super("the login was not encrypted with the proxy's key");
        }
    }
}
