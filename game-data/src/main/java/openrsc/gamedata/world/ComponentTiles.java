package openrsc.gamedata.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One floor's {@link CollisionMap#componentId walking components} as a little-endian binary:
 * {@code int32} width, {@code int32} height, {@code int32} component count, then one unsigned 16-bit
 * id per tile, row by row from the floor's first row. Id 0 is a blocked tile.
 */
public final class ComponentTiles {

    private ComponentTiles() {}

    public static byte[] encode(CollisionMap map, int floor) {
        int width = CollisionMap.WIDTH;
        int height = CollisionMap.FLOOR_HEIGHT;
        ByteBuffer buf = ByteBuffer.allocate(12 + width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(width).putInt(height).putInt(map.componentCount());
        int base = floor * CollisionMap.FLOOR_HEIGHT;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                buf.putShort((short) map.componentId(x, base + z));
            }
        }
        return buf.array();
    }
}
