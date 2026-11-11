package openrsc.sessionproxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.login.LoginPacket;
import openrsc.bot.core.login.Passwords;
import openrsc.bot.core.login.Usernames;
import org.jetbrains.annotations.Nullable;

/**
 * The login packet of an OpenRSC custom client (versions 10000 to 19999), read as the server reads it.
 *
 * <p>A client encrypts its password with the key the proxy hands it in the configs packet. When that is the server's
 * own, the packet goes upstream byte for byte; when the proxy writes its own logins, it is the proxy's, and
 * {@link OwnLogin} writes the login that goes upstream. Either way a client joining a session that is already open
 * proves its password by the same encrypted block under the server's key: the login's RSA has no padding, so one
 * password always encrypts to the same number.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/LoginPacketHandler.java#L466-L506">LoginPacketHandler.processLogin</a>
 */
public final class ClientLogin {

    private static final int ENCRYPTION_NONE = 0;
    private static final int ENCRYPTION_RSA = 1;

    // Below zero: no credential, which are all reduced modulo the key, ever equals it.
    private static final BigInteger NO_CREDENTIAL = BigInteger.valueOf(-1);

    private final byte[] body;
    private final int version;
    private final String username;
    private final byte @Nullable [] passwordBlock;
    private final @Nullable String plainPassword;
    private final int limitsAt;

    private ClientLogin(
            byte[] body,
            int version,
            String username,
            byte @Nullable [] passwordBlock,
            @Nullable String plainPassword,
            int limitsAt) {
        this.body = body;
        this.version = version;
        this.username = username;
        this.passwordBlock = passwordBlock;
        this.plainPassword = plainPassword;
        this.limitsAt = limitsAt;
    }

    /**
     * Reads a login packet: {@code body} is its opcode and payload, without the length prefix.
     *
     * @throws IOException when it is not a custom client's login, or ends early
     */
    public static ClientLogin parse(byte[] body) throws IOException {
        if (body.length == 0 || (body[0] & 0xFF) != LoginPacket.OPCODE) {
            throw new IOException("not a login packet");
        }
        ByteBuf in = Unpooled.wrappedBuffer(body, 1, body.length - 1);
        try {
            in.readByte();
            int version = in.readInt();
            if (version <= 10000 || version >= 20000) {
                throw new IOException("client version " + version + " is not a custom client's");
            }
            String username = string(in).trim();
            byte[] block = null;
            String plain = null;
            if (version < 10010 || version == 10069) {
                plain = string(in).trim();
            } else {
                int encryption = in.readByte();
                if (encryption == ENCRYPTION_NONE) {
                    plain = string(in).trim();
                } else if (encryption == ENCRYPTION_RSA) {
                    block = new byte[in.readUnsignedShort()];
                    if (block.length == 0) {
                        throw new IOException("empty password block");
                    }
                    in.readBytes(block);
                    in.skipBytes(in.readUnsignedShort());
                } else {
                    throw new IOException("unknown login encryption " + encryption);
                }
            }
            in.readLong();
            return new ClientLogin(body, version, username, block, plain, 1 + in.readerIndex());
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("login packet ends early", e);
        }
    }

    /** Reads a login packet that starts with its two-byte length, as {@link LoginPacket#build} makes one. */
    public static ClientLogin of(ByteBuf framed) throws IOException {
        int length = framed.readUnsignedShort();
        byte[] body = new byte[length];
        framed.readBytes(body);
        return parse(body);
    }

    public int version() {
        return version;
    }

    /** Whether the client says it is reconnecting: the byte after the opcode. */
    byte reconnecting() {
        return body[1];
    }

    /** The password block as the client sent it; null for a password sent in plain text. */
    byte @Nullable [] passwordBlock() {
        return passwordBlock;
    }

    /** The limits the client declares, from past its id to the end, as it sent them: empty for none. */
    byte[] limits() {
        return java.util.Arrays.copyOfRange(body, limitsAt, body.length);
    }

    public String username() {
        return username;
    }

    /** The account this login is for, as {@link #accountOf} names it. */
    public String account() {
        return accountOf(username);
    }

    /** The key one account's sessions go by: usernames that differ only in case, or in spaces against underscores, match. */
    public static String accountOf(String username) {
        return Usernames.canonicalize(username.trim()).toLowerCase(Locale.ROOT);
    }

    /** The packet as the client sent it, with its length prefix, ready to send upstream. */
    public byte[] frame() {
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) (body.length >> 8);
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    /**
     * The password as the server's key encrypts it, as a number below the modulus. Two logins carry the same
     * password, as the server reads it, when their credentials are equal; a plain-text password is encrypted the way
     * the stock client encrypts it before it is compared. A plain-text password the stock client could not have sent
     * (longer than 20 characters, or with a character it would turn into {@code _}) matches nothing: the server reads
     * a plain one as it is, so no encrypted login is the same password.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L14796-L14797">mudclient.login</a>
     */
    public BigInteger credential(BigInteger exponent, BigInteger modulus) {
        if (passwordBlock != null) {
            // Read as the server reads the block, before it decrypts it: a signed number, reduced by modPow.
            return new BigInteger(passwordBlock).mod(modulus);
        }
        String plain = plainPassword == null ? "" : plainPassword;
        String padded = Passwords.pad(plain);
        if (!padded.stripTrailing().equals(plain)) {
            return NO_CREDENTIAL;
        }
        byte[] plaintext = (padded + (char) RscPackets.NEWLINE).getBytes(StandardCharsets.US_ASCII);
        return new BigInteger(plaintext).modPow(exponent, modulus);
    }

    /** What the log names this client by: its version, and whether it sent its password in plain text. */
    public String describe() {
        return "v" + version + (passwordBlock == null ? " plain" : "");
    }

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/LoginPacketHandler.java#L36-L42
    private static String string(ByteBuf in) {
        StringBuilder out = new StringBuilder();
        while (in.isReadable()) {
            byte b = in.readByte();
            if (b == RscPackets.NEWLINE) {
                break;
            }
            out.append((char) b);
        }
        return out.toString();
    }
}
