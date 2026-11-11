package openrsc.sessionproxy.authentic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IsaacTest {

    @Test
    void generatesWhatTheServersCipherGenerates() {
        // Values the server's ISAACCipher returns for these keys, by index into its stream.
        assertStream(
                new int[] {0, 0, 0, 0},
                Map.of(0, 405143795, 1, 806046349, 7, 264870948, 255, -412232903, 256, 2053665039, 599, -1748361104));
        assertStream(
                new int[] {1, 2, 3, 4},
                Map.of(0, -621246914, 3, -2021884860, 255, 681500538, 256, 1010642953, 599, -408450882));
        assertStream(
                new int[] {-559038737, 305419896, -1, 2147483647},
                Map.of(0, -1086700168, 5, -1576058008, 255, 7279415, 256, 1790616123, 599, 879451037));
    }

    @Test
    void anOpcodeEncodedAtOneEndIsDecodedAtTheOther() {
        int[] keys = {11, 22, 33, 44};
        Isaac sender = new Isaac(keys);
        Isaac receiver = new Isaac(keys);
        for (int opcode = 0; opcode < 1000; opcode++) {
            assertEquals(opcode & 0xFF, receiver.decode(sender.encode(opcode & 0xFF)));
        }
    }

    private static void assertStream(int[] keys, Map<Integer, Integer> expected) {
        Isaac isaac = new Isaac(keys);
        for (int i = 0; i < 600; i++) {
            int value = isaac.next();
            Integer want = expected.get(i);
            if (want != null) {
                assertEquals(want, value, "value " + i);
            }
        }
    }
}
