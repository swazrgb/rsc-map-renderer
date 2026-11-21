package openrsc.sessionproxy.authentic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import openrsc.bot.core.login.PreLoginConfigs;
import org.junit.jupiter.api.Test;

/**
 * Each 235 packet is built as the server's {@code Payload235Parser} reads it, and each expectation as its
 * {@code PayloadCustomParser} reads the same thing.
 */
class ToServerTest {

    private final ToServer translate =
            new ToServer(new PreLoginConfigs("test", BigInteger.ONE, BigInteger.ONE, Map.of()), slot -> 0);

    @Test
    void theSleepWordLosesItsDelayAndItsZeroQuotes() {
        byte[] word = new PacketWriter(45).u8(10).zeroQuoted("asleep").toByteArray();
        assertEquals(
                "2d" + HexFormat.of().formatHex("asleep\n".getBytes(StandardCharsets.ISO_8859_1)),
                hex(translate.translate(word)));
    }

    @Test
    void aDropIsOfTheWholeSlot() {
        ToServer drop = new ToServer(
                new PreLoginConfigs("test", BigInteger.ONE, BigInteger.ONE, Map.of()), slot -> slot == 3 ? 700 : 0);
        assertEquals(
                "f60003000002bc",
                hex(drop.translate(new PacketWriter(246).u16(3).toByteArray())));
    }

    private static String hex(byte[] body) {
        return HexFormat.of().formatHex(body);
    }
}
