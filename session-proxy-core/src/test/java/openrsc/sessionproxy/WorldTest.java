package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Map;
import openrsc.bot.core.login.PreLoginConfigs;
import org.junit.jupiter.api.Test;

class WorldTest {

    @Test
    void theSettingsNameUraniumOrColeslawAndNothingElse() {
        assertEquals(World.URANIUM, World.named(" Uranium "));
        assertEquals(World.COLESLAW, World.named("coleslaw"));
        assertThrows(IllegalArgumentException.class, () -> World.named("cabbage"));
        assertThrows(IllegalArgumentException.class, () -> World.named("preservation"));
        assertEquals(World.COLESLAW, World.ofConf("rsccoleslaw"));
        assertThrows(IllegalArgumentException.class, () -> World.ofConf("rsccabbage"));
    }

    @Test
    void aServerIsTheWorldsOnlyWithItsNameAndKey() throws IOException {
        ServerIdentity uranium = World.URANIUM.identity();
        assertEquals("game.openrsc.com", uranium.host());
        assertEquals(43235, uranium.port());
        assertEquals(43599, World.COLESLAW.identity().port());
        assertEquals("game.openrsc.com:43599", World.COLESLAW.address());

        uranium.check(configs("RSC Uranium", uranium.modulus()));

        IOException cabbage =
                assertThrows(IOException.class, () -> uranium.check(configs("RSC Cabbage", uranium.modulus())));
        assertTrue(cabbage.getMessage().contains("\"RSC Cabbage\""), cabbage.getMessage());
        IOException key = assertThrows(IOException.class, () -> uranium.check(configs("RSC Uranium", BigInteger.TWO)));
        assertTrue(key.getMessage().contains("login key"), key.getMessage());
    }

    private static PreLoginConfigs configs(String name, BigInteger modulus) {
        return new PreLoginConfigs(name, BigInteger.valueOf(65537), modulus, Map.of());
    }
}
