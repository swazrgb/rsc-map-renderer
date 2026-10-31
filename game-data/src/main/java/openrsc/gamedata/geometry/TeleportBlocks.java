package openrsc.gamedata.geometry;

/**
 * Where the server refuses a teleport: at or past a wilderness level, and anywhere in the Fisher King
 * realm, on the Grand Tree's ground floor or in the mod room.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1821">SpellHandler.canTeleport</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/DragonstoneAmulet.java#L51">DragonstoneAmulet.onOpInv</a>
 */
public final class TeleportBlocks {

    /**
     * The wilderness level from which teleport spells are refused.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1821">SpellHandler.canTeleport</a>
     */
    public static final int SPELL_WILDERNESS_LIMIT = 20;

    /**
     * The wilderness level from which item teleports, such as a charged dragonstone amulet, are refused.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Constants.java#L89">Constants.GLORY_TELEPORT_LIMIT</a>
     */
    public static final int ITEM_WILDERNESS_LIMIT = 30;

    private TeleportBlocks() {}

    /** Whether a teleport refused from wilderness level {@code wildernessLimit} works on the tile. */
    public static boolean castable(int x, int z, int wildernessLimit) {
        return Wilderness.level(x, z) < wildernessLimit && !contains(x, z);
    }

    /**
     * Whether the tile is in a zone where every teleport is refused, whatever the wilderness level.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L361">Point.isInFisherKingRealm</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L369">Point.isInsideGrandTreeGround</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L265">Point.inModRoom</a>
     */
    public static boolean contains(int x, int z) {
        return in(x, z, 388, 4, 427, 40)
                || in(x, z, 484, 4, 523, 40)
                || in(x, z, 411, 976, 519, 984)
                || in(x, z, 411, 1920, 518, 1925)
                || in(x, z, 410, 158, 422, 170)
                || in(x, z, 64, 1639, 80, 1643);
    }

    private static boolean in(int x, int z, int x1, int z1, int x2, int z2) {
        return x >= x1 && x <= x2 && z >= z1 && z <= z2;
    }
}
