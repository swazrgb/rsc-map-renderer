package openrsc.gamedata.data;

import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Rect;

/**
 * The rectangles the server counts as inside a bank, copied from {@code Point.isInBank}, with the
 * lowest {@code based_map_data} each bank exists at. The server asks it of a banker's tile before
 * the banker opens the bank, of the player's tile for the {@code ::b} command, and of the victim's
 * tile before a PK-mode attack.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L498">Point.isInBank</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/Bankers.java#L74">Bankers.onTalkNpc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/commands/RegularPlayer.java#L108">RegularPlayer.onCommand</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/shared/AttackPlayer.java#L14">AttackPlayer.attackPrevented</a>
 */
public enum BankArea {
    AL_KHARID(29, Rect.of(87, 689, 93, 700)),
    CATHERBY(Rect.of(437, 491, 443, 496)),
    DRAYNOR(24, Rect.of(216, 634, 223, 638)),
    EAST_ARDOUGNE_NORTH(Rect.of(577, 572, 585, 576)),
    EAST_ARDOUGNE_SOUTH(Rect.of(551, 609, 554, 616)),
    EDGEVILLE(23, Rect.of(212, 448, 220, 453)),
    FALADOR_EAST(27, Rect.of(280, 564, 286, 573)),
    FALADOR_WEST(Rect.of(328, 549, 334, 557)),
    GRAND_TREE_SECOND_FLOOR(Rect.of(714, 1399, 718, 1403)),
    LEGENDS_GUILD(Rect.of(508, 2421, 517, 2423)),
    MAGE_ARENA(Rect.of(451, 3376, 457, 3380)),
    SEERS_VILLAGE(Rect.of(498, 447, 504, 453)),
    SHANTAY_PASS(Rect.of(59, 731, 59, 731)),
    SHILO_VILLAGE(Rect.of(399, 848, 404, 854)),
    TUTORIAL_ISLAND(Rect.of(196, 746, 203, 754)),
    TREE_GNOME_STRONGHOLD(Rect.of(712, 1440, 716, 1464)),
    VARROCK_EAST(24, Rect.of(98, 510, 106, 515)),
    VARROCK_WEST(Rect.of(147, 498, 153, 506)),
    YANILLE(Rect.of(585, 750, 590, 758)),
    ZANARIS(Rect.of(172, 3521, 176, 3528), Rect.of(172, 3529, 174, 3529), Rect.of(170, 3521, 171, 3525));

    private final int minMapData;

    @SuppressWarnings("ImmutableEnumChecker")
    private final List<Rect> boxes;

    BankArea(Rect... boxes) {
        this(0, boxes);
    }

    BankArea(int minMapData, Rect... boxes) {
        this.minMapData = minMapData;
        this.boxes = List.of(boxes);
    }

    /** The rectangles of this bank, inclusive: one, except for {@link #ZANARIS}. */
    public List<Rect> boxes() {
        return boxes;
    }

    /** The lowest {@code based_map_data} this bank exists at; 0 when every world has it. */
    public int minMapData() {
        return minMapData;
    }

    public boolean existsAt(int mapData) {
        return mapData >= minMapData;
    }

    /** Whether the tile is inside this bank on a world running {@code based_map_data = mapData}. */
    public boolean contains(int x, int z, int mapData) {
        if (!existsAt(mapData)) {
            return false;
        }
        for (Rect r : boxes) {
            if (r.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /** Any tile inside this bank. */
    public Dest dest() {
        return boxes.size() == 1 ? boxes.getFirst() : new Dest.AnyOf(List.<Area>copyOf(boxes));
    }

    /** The bank the tile is inside on a world running {@code based_map_data = mapData}, or {@code null}. */
    public static @Nullable BankArea at(int x, int z, int mapData) {
        for (BankArea b : values()) {
            if (b.contains(x, z, mapData)) {
                return b;
            }
        }
        return null;
    }

    public static boolean isInBank(int x, int z, int mapData) {
        return at(x, z, mapData) != null;
    }
}
