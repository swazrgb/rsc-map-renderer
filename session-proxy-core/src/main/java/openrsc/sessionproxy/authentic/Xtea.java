package openrsc.sessionproxy.authentic;

import java.nio.ByteBuffer;

/**
 * XTEA over whole 8-byte blocks, as an authentic client encrypts the second block of its login with its ISAAC keys.
 * Bytes past the last whole block are copied as they are.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/Crypto.java#L62-L86">Crypto.decryptXTEA</a>
 */
public final class Xtea {

    private static final int ROUNDS = 32;
    private static final int DELTA = 0x9e3779b9;
    // ROUNDS * DELTA, wrapped to 32 bits: the sum decryption starts from.
    private static final int DECRYPT_SUM = 0xc6ef3720;

    private Xtea() {}

    public static byte[] decrypt(byte[] data, int[] keys) {
        ByteBuffer in = ByteBuffer.wrap(data);
        ByteBuffer out = ByteBuffer.allocate(data.length);
        for (int block = 0; block < data.length / 8; block++) {
            int v0 = in.getInt();
            int v1 = in.getInt();
            int sum = DECRYPT_SUM;
            for (int i = 0; i < ROUNDS; i++) {
                v1 -= (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + keys[(sum >>> 11) & 3]);
                sum -= DELTA;
                v0 -= (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + keys[sum & 3]);
            }
            out.putInt(v0).putInt(v1);
        }
        out.put(in);
        return out.array();
    }

    public static byte[] encrypt(byte[] data, int[] keys) {
        ByteBuffer in = ByteBuffer.wrap(data);
        ByteBuffer out = ByteBuffer.allocate(data.length);
        for (int block = 0; block < data.length / 8; block++) {
            int v0 = in.getInt();
            int v1 = in.getInt();
            int sum = 0;
            for (int i = 0; i < ROUNDS; i++) {
                v0 += (((v1 << 4) ^ (v1 >>> 5)) + v1) ^ (sum + keys[sum & 3]);
                sum += DELTA;
                v1 += (((v0 << 4) ^ (v0 >>> 5)) + v0) ^ (sum + keys[(sum >>> 11) & 3]);
            }
            out.putInt(v0).putInt(v1);
        }
        out.put(in);
        return out.array();
    }
}
