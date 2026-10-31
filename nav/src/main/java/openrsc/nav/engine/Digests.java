package openrsc.nav.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import openrsc.gamedata.world.CollisionMap;

/**
 * Feeds what a persisted plane is computed from into its SHA-256 stamp. Arrays are length-prefixed,
 * so two adjacent ones cannot trade elements and hash the same, and a null one hashes apart from an
 * empty one.
 */
final class Digests {

    private Digests() {}

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM ships SHA-256", e);
        }
    }

    /** A class's hand-bumped plane version, under the class's name so two classes' numbers cannot swap. */
    static void version(MessageDigest md, String owner, int version) {
        string(md, owner);
        md.update(ByteBuffer.allocate(4).putInt(version).array());
    }

    static void ints(MessageDigest md, int[] values) {
        ByteBuffer b = ByteBuffer.allocate(4 + 4 * (values == null ? 0 : values.length));
        b.putInt(values == null ? -1 : values.length);
        if (values != null) {
            for (int v : values) {
                b.putInt(v);
            }
        }
        md.update(b.array());
    }

    static void shorts(MessageDigest md, short[] values) {
        ByteBuffer b = ByteBuffer.allocate(4 + 2 * (values == null ? 0 : values.length));
        b.putInt(values == null ? -1 : values.length);
        if (values != null) {
            for (short v : values) {
                b.putShort(v);
            }
        }
        md.update(b.array());
    }

    static void string(MessageDigest md, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        md.update(ByteBuffer.allocate(4).putInt(utf8.length).array());
        md.update(utf8);
    }

    /** Every tile's static collision and object flags, row by row. */
    static void collisionMap(MessageDigest md, CollisionMap map) {
        int width = map.width();
        byte[] row = new byte[2 * width];
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < width; x++) {
                row[2 * x] = (byte) map.flags(x, y);
                row[2 * x + 1] = (byte) map.objectFlags(x, y);
            }
            md.update(row);
        }
    }
}
