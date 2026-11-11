package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.login.LoginCapabilities;
import openrsc.bot.core.login.LoginPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClientLoginTest {

    private static BigInteger exponent;
    private static BigInteger modulus;

    @BeforeAll
    static void serverKey() throws Exception {
        // The size and kind of key the server makes for itself.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(512);
        RSAPublicKey key = (RSAPublicKey) generator.generateKeyPair().getPublic();
        exponent = key.getPublicExponent();
        modulus = key.getModulus();
    }

    @Test
    void reads_the_stock_clients_login_and_sends_it_on_unchanged() throws IOException {
        ByteBuf built = rsaPacket("Some Body", "hunter2", "client details");
        byte[] wire = new byte[built.readableBytes()];
        built.getBytes(built.readerIndex(), wire);

        ClientLogin login = ClientLogin.of(built);

        assertEquals("Some_Body", login.username());
        assertEquals("some_body", login.account());
        assertEquals(10010, login.version());
        assertArrayEquals(wire, login.frame());
    }

    @Test
    void one_password_is_one_credential_whatever_else_the_login_says() throws IOException {
        BigInteger stock = credential(rsaLogin("bob", "hunter2", "client details"));

        assertEquals(stock, credential(rsaLogin("BOB", "hunter2", "plutonium")));
        assertNotEquals(stock, credential(rsaLogin("bob", "hunter3", "plutonium")));
    }

    @Test
    void a_plain_text_password_matches_the_same_password_encrypted() throws IOException {
        BigInteger encrypted = credential(rsaLogin("bob", "hunter2", "plutonium"));

        assertEquals(encrypted, credential(plainLogin("bob", "hunter2")));
        assertNotEquals(encrypted, credential(plainLogin("bob", "hunter3")));
    }

    @Test
    void a_plain_text_password_the_stock_client_could_not_send_matches_nothing() throws IOException {
        BigInteger encrypted = credential(rsaLogin("bob", "abc def", "plutonium"));

        assertNotEquals(encrypted, credential(plainLogin("bob", "abc def")), "the server reads it with its space");
        assertEquals(encrypted, credential(plainLogin("bob", "abc_def")));
        assertNotEquals(
                credential(rsaLogin("bob", "a".repeat(20), "plutonium")),
                credential(plainLogin("bob", "a".repeat(21))),
                "the stock client cuts a password at 20 characters; the server reads a plain one whole");
    }

    @Test
    void a_block_sent_with_a_sign_byte_is_the_same_number() throws IOException {
        byte[] plaintext = "hunter2             \n".getBytes(StandardCharsets.US_ASCII);
        byte[] block = new BigInteger(plaintext).modPow(exponent, modulus).toByteArray();
        if (block[0] == 0) {
            block = Arrays.copyOfRange(block, 1, block.length);
        }
        byte[] signed = new byte[block.length + 1];
        System.arraycopy(block, 0, signed, 1, block.length);

        BigInteger expected = credential(rsaLogin("bob", "hunter2", "plutonium"));
        assertEquals(expected, credential(withBlock(signed)));
        if ((block[0] & 0x80) == 0) {
            assertEquals(expected, credential(withBlock(block)));
        }
    }

    @Test
    void refuses_what_is_not_a_custom_clients_login() {
        assertThrows(IOException.class, () -> ClientLogin.parse(new byte[] {19}));
        ByteBuf authentic = RscPackets.create(LoginPacket.OPCODE);
        authentic.writeByte(0);
        authentic.writeInt(235);
        assertThrows(IOException.class, () -> ClientLogin.of(RscPackets.finish(authentic)));
        assertThrows(IOException.class, () -> withBlock(new byte[0]), "an empty password block");
    }

    private static BigInteger credential(ClientLogin login) {
        return login.credential(exponent, modulus);
    }

    private static ByteBuf rsaPacket(String user, String password, String details) {
        return LoginPacket.build(
                user, password, 10010, exponent, modulus, details, "63", LoginCapabilities.AUTHENTIC, 0L);
    }

    private static ClientLogin rsaLogin(String user, String password, String details) throws IOException {
        return ClientLogin.of(rsaPacket(user, password, details));
    }

    private static ClientLogin plainLogin(String user, String password) throws IOException {
        ByteBuf b = RscPackets.create(LoginPacket.OPCODE);
        b.writeByte(0);
        b.writeInt(10009);
        RscPackets.writeNlString(b, user);
        RscPackets.writeNlString(b, password);
        b.writeLong(0L);
        return ClientLogin.of(RscPackets.finish(b));
    }

    private static ClientLogin withBlock(byte[] block) throws IOException {
        ByteBuf b = RscPackets.create(LoginPacket.OPCODE);
        b.writeByte(0);
        b.writeInt(10010);
        RscPackets.writeNlString(b, "bob");
        b.writeByte(LoginPacket.LOGIN_ENC_RSA);
        b.writeShort(block.length);
        b.writeBytes(block);
        b.writeShort(0);
        b.writeLong(0L);
        return ClientLogin.of(RscPackets.finish(b));
    }
}
