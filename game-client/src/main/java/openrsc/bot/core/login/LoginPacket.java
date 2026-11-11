package openrsc.bot.core.login;

import io.netty.buffer.ByteBuf;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import openrsc.bot.core.codec.RscPackets;

public final class LoginPacket {

    public static final int OPCODE = 0;
    public static final byte LOGIN_ENC_RSA = 1;

    private LoginPacket() {}

    public static ByteBuf build(
            String username,
            String password,
            int clientVersion,
            BigInteger rsaExponent,
            BigInteger rsaModulus,
            String loginDetails,
            String mapHash,
            LoginCapabilities capabilities,
            long uid) {

        byte[] encPass = encryptPassword(password, rsaExponent, rsaModulus);
        byte[] encDetails = encryptDetails(loginDetails, rsaExponent, rsaModulus);

        ByteBuf b = RscPackets.create(OPCODE);
        b.writeByte(0);
        b.writeInt(clientVersion);
        RscPackets.writeNlString(b, Usernames.canonicalize(username));
        b.writeByte(LOGIN_ENC_RSA);
        b.writeShort(encPass.length);
        b.writeBytes(encPass);
        b.writeShort(encDetails.length);
        b.writeBytes(encDetails);
        b.writeLong(uid);
        capabilities.write(b, mapHash);
        return RscPackets.finish(b);
    }

    private static byte[] encryptPassword(String pw, BigInteger e, BigInteger n) {
        String padded = Passwords.pad(pw);
        byte[] plaintext = new byte[Passwords.LENGTH + 1];
        System.arraycopy(padded.getBytes(StandardCharsets.UTF_8), 0, plaintext, 0, Passwords.LENGTH);
        plaintext[Passwords.LENGTH] = RscPackets.NEWLINE;
        return RsaCrypto.encrypt(plaintext, e, n);
    }

    private static byte[] encryptDetails(String details, BigInteger e, BigInteger n) {
        byte[] body = details.getBytes(StandardCharsets.UTF_8);
        byte[] plaintext = new byte[body.length + 1];
        System.arraycopy(body, 0, plaintext, 0, body.length);
        plaintext[body.length] = RscPackets.NEWLINE;
        return RsaCrypto.encrypt(plaintext, e, n);
    }
}
