package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.bot.core.world.Opcode;
import org.junit.jupiter.api.Test;

class HeldMessagesTest {

    private static final int LINE = Opcode.SEND_SERVER_MESSAGE.wire();
    private static final int COORDS = Opcode.SEND_NPC_COORDS.wire();

    @Test
    void keeps_server_lines_in_order_and_nothing_else() {
        HeldMessages held = new HeldMessages();
        held.capture(COORDS, new byte[] {(byte) COORDS, 1});
        held.capture(LINE, new byte[] {(byte) LINE, 1});
        held.capture(LINE, new byte[] {(byte) LINE, 2});
        List<byte[]> lines = held.drain();
        assertEquals(2, lines.size());
        assertEquals((byte) 1, lines.get(0)[1]);
        assertEquals((byte) 2, lines.get(1)[1]);
        assertTrue(held.drain().isEmpty(), "drained once, gone");
    }

    @Test
    void the_oldest_line_gives_way_past_the_cap() {
        HeldMessages held = new HeldMessages();
        for (int i = 0; i < HeldMessages.CAPACITY + 3; i++) {
            held.capture(LINE, new byte[] {(byte) LINE, (byte) (i % 100)});
        }
        assertEquals(HeldMessages.CAPACITY, held.size());
        assertEquals((byte) 3, held.drain().get(0)[1]);
    }
}
