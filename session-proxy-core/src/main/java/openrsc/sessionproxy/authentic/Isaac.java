package openrsc.sessionproxy.authentic;

import java.util.Arrays;

/**
 * The ISAAC generator that scrambles an authentic client's opcodes, seeded with the four keys of its login. Each end
 * of a connection keeps one per direction, seeded alike, and adds (or subtracts) the next value to every opcode.
 *
 * <p>Not thread-safe.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/login/ISAACCipher.java">ISAACCipher</a>
 */
public final class Isaac {

    private static final int SIZE_LOG = 8;
    private static final int SIZE = 1 << SIZE_LOG;
    private static final int MASK = (SIZE - 1) << 2;
    private static final int RATIO = 0x9e3779b9;

    private final int[] results = new int[SIZE];
    private final int[] memory = new int[SIZE];
    private int count;
    private int a;
    private int b;
    private int c;

    /** Seeded with the four keys of a login. */
    public Isaac(int[] keys) {
        System.arraycopy(keys, 0, results, 0, keys.length);
        init();
    }

    /** The opcode as it goes on the wire. */
    public int encode(int opcode) {
        return (opcode + next()) & 0xFF;
    }

    /** The opcode an encoded one stands for. */
    public int decode(int encoded) {
        return (encoded - next()) & 0xFF;
    }

    /** The next value of the stream. */
    int next() {
        if (count-- == 0) {
            isaac();
            count = SIZE - 1;
        }
        return results[count];
    }

    private void isaac() {
        int i = 0;
        int j = SIZE / 2;
        b += ++c;
        while (i < SIZE / 2) {
            i = round(i, j++, a ^= a << 13);
            i = round(i, j++, a ^= a >>> 6);
            i = round(i, j++, a ^= a << 2);
            i = round(i, j++, a ^= a >>> 16);
        }
        j = 0;
        while (j < SIZE / 2) {
            i = round(i, j++, a ^= a << 13);
            i = round(i, j++, a ^= a >>> 6);
            i = round(i, j++, a ^= a << 2);
            i = round(i, j++, a ^= a >>> 16);
        }
    }

    // The shifted accumulator is computed by the caller, before the step that adds memory[j] to it.
    private int round(int i, int j, int mixed) {
        int x = memory[i];
        a = mixed + memory[j];
        int y = memory[(x & MASK) >> 2] + a + b;
        memory[i] = y;
        b = memory[((y >> SIZE_LOG) & MASK) >> 2] + x;
        results[i] = b;
        return i + 1;
    }

    private void init() {
        int[] s = new int[8];
        Arrays.fill(s, RATIO);
        for (int i = 0; i < 4; i++) {
            mix(s);
        }
        for (int i = 0; i < SIZE; i += 8) {
            for (int k = 0; k < 8; k++) {
                s[k] += results[i + k];
            }
            mix(s);
            System.arraycopy(s, 0, memory, i, 8);
        }
        for (int i = 0; i < SIZE; i += 8) {
            for (int k = 0; k < 8; k++) {
                s[k] += memory[i + k];
            }
            mix(s);
            System.arraycopy(s, 0, memory, i, 8);
        }
        isaac();
        count = SIZE;
    }

    private static void mix(int[] s) {
        s[0] ^= s[1] << 11;
        s[3] += s[0];
        s[1] += s[2];
        s[1] ^= s[2] >>> 2;
        s[4] += s[1];
        s[2] += s[3];
        s[2] ^= s[3] << 8;
        s[5] += s[2];
        s[3] += s[4];
        s[3] ^= s[4] >>> 16;
        s[6] += s[3];
        s[4] += s[5];
        s[4] ^= s[5] << 10;
        s[7] += s[4];
        s[5] += s[6];
        s[5] ^= s[6] >>> 4;
        s[0] += s[5];
        s[6] += s[7];
        s[6] ^= s[7] << 8;
        s[1] += s[6];
        s[7] += s[0];
        s[7] ^= s[0] >>> 9;
        s[2] += s[7];
        s[0] += s[1];
    }
}
