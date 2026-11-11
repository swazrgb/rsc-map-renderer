package openrsc.sessionproxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.login.LoginPacket;
import openrsc.bot.core.login.Passwords;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.bot.core.login.RsaCrypto;
import org.jetbrains.annotations.Nullable;

/**
 * The login a proxy that writes its own ({@link ProxyConfig#writingOwnLogins}) sends upstream for a client, and the
 * configs packet it hands its clients: the server's, carrying the proxy's key in place of the server's.
 *
 * <p>The login is the one the server reads from the client's: its reconnect flag, version, username and declared
 * limits, the password encrypted with the server's key, and {@link #IDENTITY} as its login details, which the server
 * logs. A client of a version whose login has no details (below 10010, or 10069), or one that sends its password in
 * plain text, has its login sent as it is.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/LoginPacketHandler.java#L472-L505">LoginPacketHandler.processLogin</a>
 */
public final class OwnLogin {

    /** What the proxy calls itself in every login it writes. */
    public static final String IDENTITY = "SwazRGB Session Proxy";

    private static final int ENCRYPTION_RSA = 1;

    private OwnLogin() {}

    /**
     * The login the proxy sends for {@code client}, whose password block {@code key} encrypts; null when it does not,
     * as for a client that took the server's key from elsewhere.
     */
    static @Nullable ClientLogin of(ClientLogin client, ProxyKey key, PreLoginConfigs server) {
        byte[] block = client.passwordBlock();
        if (block == null) {
            return client;
        }
        byte[] plaintext = key.decrypt(block);
        // As the stock client encrypts it: the padded password and a newline.
        if (plaintext.length != Passwords.LENGTH + 1 || plaintext[Passwords.LENGTH] != RscPackets.NEWLINE) {
            return null;
        }
        byte[] password = RsaCrypto.encrypt(plaintext, server.rsaExponent(), server.rsaModulus());
        byte[] details = RsaCrypto.encrypt(
                (IDENTITY + (char) RscPackets.NEWLINE).getBytes(StandardCharsets.UTF_8),
                server.rsaExponent(),
                server.rsaModulus());
        byte[] limits = client.limits();
        ByteBuf b = Unpooled.buffer(64 + password.length + details.length + limits.length);
        b.writeByte(LoginPacket.OPCODE);
        b.writeByte(client.reconnecting());
        b.writeInt(client.version());
        // The username's bytes as the client sent them, which the server reads a byte to a character:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/LoginPacketHandler.java#L36-L42
        String username = client.username();
        for (int i = 0; i < username.length(); i++) {
            b.writeByte(username.charAt(i));
        }
        b.writeByte(RscPackets.NEWLINE);
        b.writeByte(ENCRYPTION_RSA);
        b.writeShort(password.length);
        b.writeBytes(password);
        b.writeShort(details.length);
        b.writeBytes(details);
        b.writeLong(0L);
        b.writeBytes(limits);
        byte[] body = new byte[b.readableBytes()];
        b.readBytes(body);
        try {
            return ClientLogin.parse(body);
        } catch (IOException e) {
            throw new UncheckedIOException("the proxy wrote a login it cannot read", e);
        }
    }

    /**
     * {@code configs}, a configs packet's opcode and payload, with {@code key} in place of the server's: every other
     * byte as the server sent it.
     *
     * @throws IOException when it ends before its key
     */
    static byte[] configsWithKey(byte[] configs, ProxyKey key) throws IOException {
        // The fields PreLoginConfigs.parse reads before the key: the server's name, a string, flags 3 to 41, a string,
        // flags 43 and 44, a string, and flags 46 to 86.
        int at = 1;
        at = pastString(configs, at);
        at = pastString(configs, at);
        at += 39;
        at = pastString(configs, at);
        at += 2;
        at = pastString(configs, at);
        at += 41;
        int keyAt = at;
        at = pastString(configs, at);
        at = pastString(configs, at);
        ByteBuf out = Unpooled.buffer(configs.length + 160);
        out.writeBytes(configs, 0, keyAt);
        RscPackets.writeNlString(out, key.exponent().toString());
        RscPackets.writeNlString(out, key.modulus().toString());
        out.writeBytes(configs, at, configs.length - at);
        return Arrays.copyOf(out.array(), out.writerIndex());
    }

    private static int pastString(byte[] bytes, int from) throws IOException {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == RscPackets.NEWLINE) {
                return i + 1;
            }
        }
        throw new IOException("the configs packet ends before its key");
    }
}
