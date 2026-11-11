package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.login.LoginCapabilities;
import openrsc.bot.core.login.LoginPacket;
import openrsc.bot.core.login.Passwords;
import openrsc.bot.core.login.PreLoginConfigs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnLoginTest {

    private static ProxyKey proxy;
    // The server's key, held as a ProxyKey so the test can read what the server would.
    private static ProxyKey server;
    private static PreLoginConfigs serverConfigs;

    @BeforeAll
    static void keys(@TempDir Path dir) throws IOException {
        proxy = ProxyKey.load(dir.resolve("proxy"));
        server = ProxyKey.load(dir.resolve("server"));
        serverConfigs = new PreLoginConfigs("RSC Uranium", server.exponent(), server.modulus(), Map.of());
    }

    @Test
    void the_login_sent_up_is_the_clients_with_its_password_for_the_server_and_the_proxy_named() throws IOException {
        ClientLogin client = login("Some Body", "hunter2", proxy, 7L);

        ClientLogin up = OwnLogin.of(client, proxy, serverConfigs);
        assertNotNull(up);

        Read read = read(up.frame());
        assertEquals(1, read.reconnecting, "the client's reconnect flag");
        assertEquals(10010, read.version, "the client's version");
        assertEquals(client.username(), read.username, "the username as the client sent it");
        assertEquals(
                Passwords.pad("hunter2") + "\n",
                new String(server.decrypt(read.password), StandardCharsets.US_ASCII),
                "the password, as the server decrypts it");
        assertEquals(
                OwnLogin.IDENTITY + "\n",
                new String(server.decrypt(read.details), StandardCharsets.UTF_8),
                "the login details the server logs");
        assertEquals(0L, read.uid);
        assertArrayEquals(client.limits(), read.limits, "the limits the client declares");
        assertEquals(
                login("Some Body", "hunter2", server, 0L).credential(server.exponent(), server.modulus()),
                up.credential(server.exponent(), server.modulus()),
                "the same credential as a login the client encrypted for the server itself");
    }

    @Test
    void a_password_encrypted_with_another_key_is_no_login_the_proxy_can_write() throws IOException {
        assertNull(OwnLogin.of(login("Some Body", "hunter2", server, 7L), proxy, serverConfigs));
    }

    @Test
    void a_password_sent_in_plain_text_goes_up_as_the_client_sent_it() throws IOException {
        ByteBuf b = RscPackets.create(LoginPacket.OPCODE);
        b.writeByte(0);
        b.writeInt(10009);
        RscPackets.writeNlString(b, "Some Body");
        RscPackets.writeNlString(b, "hunter2");
        b.writeLong(0L);
        LoginCapabilities.AUTHENTIC.write(b, "63");
        ClientLogin plain = ClientLogin.of(RscPackets.finish(b));

        assertSame(plain, OwnLogin.of(plain, proxy, serverConfigs));
    }

    @Test
    void the_configs_a_client_is_handed_are_the_servers_with_the_proxys_key() throws IOException {
        byte[] configs = configs("RSC Uranium", "a", "b", "c");

        byte[] handed = OwnLogin.configsWithKey(configs, proxy);

        PreLoginConfigs was = PreLoginConfigs.parse(Unpooled.wrappedBuffer(configs, 1, configs.length - 1));
        PreLoginConfigs is = PreLoginConfigs.parse(Unpooled.wrappedBuffer(handed, 1, handed.length - 1));
        assertEquals(proxy.exponent(), is.rsaExponent());
        assertEquals(proxy.modulus(), is.rsaModulus());
        assertEquals(was.serverName(), is.serverName());
        assertEquals(was.flags(), is.flags());
        assertArrayEquals(OwnLogin.configsWithKey(handed, server), OwnLogin.configsWithKey(configs, server));
    }

    @Test
    void a_configs_packet_that_ends_before_its_key_is_refused() {
        byte[] configs = configs("RSC Uranium", "a", "b", "c");
        byte[] cut = java.util.Arrays.copyOf(configs, 20);

        assertThrows(IOException.class, () -> OwnLogin.configsWithKey(cut, proxy));
    }

    private static ClientLogin login(String user, String password, ProxyKey key, long uid) throws IOException {
        ByteBuf built = LoginPacket.build(
                user,
                password,
                10010,
                key.exponent(),
                key.modulus(),
                "C:/Games/client.jar",
                "63",
                LoginCapabilities.AUTHENTIC,
                uid);
        // A reconnecting client, so the flag is seen to carry over.
        built.setByte(3, 1);
        return ClientLogin.of(built);
    }

    /** A configs packet as the server lays it out, with {@code a}, {@code b} and {@code c} in its unread strings. */
    private static byte[] configs(String name, String a, String b, String c) {
        Map<Integer, Integer> flags = new HashMap<>();
        ByteBuf out = Unpooled.buffer();
        out.writeByte(19);
        RscPackets.writeNlString(out, name);
        RscPackets.writeNlString(out, a);
        flags(out, 3, 41, flags);
        RscPackets.writeNlString(out, b);
        flags(out, 43, 44, flags);
        RscPackets.writeNlString(out, c);
        flags(out, 46, 86, flags);
        RscPackets.writeNlString(out, server.exponent().toString());
        RscPackets.writeNlString(out, server.modulus().toString());
        flags(out, 89, 90, flags);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    private static void flags(ByteBuf out, int first, int last, Map<Integer, Integer> flags) {
        for (int field = first; field <= last; field++) {
            out.writeByte(field % 3);
            flags.put(field, field % 3);
        }
    }

    private record Read(
            int reconnecting, int version, String username, byte[] password, byte[] details, long uid, byte[] limits) {}

    private static Read read(byte[] frame) {
        ByteBuf in = Unpooled.wrappedBuffer(frame, 3, frame.length - 3);
        int reconnecting = in.readByte();
        int version = in.readInt();
        String username = RscPackets.readNlString(in);
        in.readByte();
        byte[] password = new byte[in.readUnsignedShort()];
        in.readBytes(password);
        byte[] details = new byte[in.readUnsignedShort()];
        in.readBytes(details);
        long uid = in.readLong();
        byte[] limits = new byte[in.readableBytes()];
        in.readBytes(limits);
        return new Read(reconnecting, version, username, password, details, uid, limits);
    }
}
