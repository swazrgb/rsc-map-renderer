package openrsc.gamedata.geometry;

import java.util.Collection;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;

/**
 * Where the desert's heat strikes and how often, as the server has it. Each stroke drinks a mouthful from a water
 * skin, or hits for 1 to 10 without one. It runs for every player but an admin, on a world with
 * {@code want_fixed_broken_mechanics} on, as both of ours are.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/DesertHeatEvent.java#L134-L158">DesertHeatEvent.doHeatStroke</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L4273-L4280">Player.desertHeatInit</a>
 */
public final class Desert {

    /** Every tile the heat may reach. */
    public static final Rect BOUNDS = Rect.of(48, 721, 189, 815);

    /**
     * The hardest a stroke strikes a player with no water aboard: one that leaves no hits kills.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/DesertHeatEvent.java#L152-L153">DesertHeatEvent.doHeatStroke</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L910-L917">Mob.damage</a>
     */
    public static final int STROKE_MAX_HIT = 10;

    /** Mouthfuls in a water skin, by the skin's item id; an empty skin holds none. */
    public static int mouthfuls(int id) {
        if (id == ItemId.FULL_WATER_SKIN.id()) {
            return 4;
        }
        if (id == ItemId.WATER_SKIN_MOSTLY_FULL.id()) {
            return 3;
        }
        if (id == ItemId.WATER_SKIN_MOSTLY_EMPTY.id()) {
            return 2;
        }
        return id == ItemId.WATER_SKIN_MOUTHFUL_LEFT.id() ? 1 : 0;
    }

    private Desert() {}

    /**
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/DesertHeatEvent.java#L54-L83">DesertHeatEvent.inDesert</a>
     */
    public static boolean contains(int x, int y) {
        if (!beyondThePass(x, y)) {
            return false;
        }
        boolean miningCamp = x >= 80 && x <= 91 && y >= 799 && y <= 812;
        boolean bedabinCamp = x >= 160 && y >= 784;
        return !(miningCamp || bedabinCamp);
    }

    /** Whether the tile lies in the desert or in a camp within it, which no walk reaches but through the heat. */
    public static boolean beyondThePass(int x, int y) {
        if (!BOUNDS.contains(x, y)) {
            return false;
        }
        boolean shantayPass = x >= 59 && x <= 67 && y >= 721 && y <= 732;
        boolean dunes = y <= 722 && x <= 82;
        return !(shantayPass || dunes);
    }

    /**
     * Ticks between strokes for a player wearing {@code worn}: 140, less for armour on the chest, legs, feet, hands
     * and head, more for the desert shirt, robe and boots; 40 to 190.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/DesertHeatEvent.java#L85-L131">DesertHeatEvent.calculateHeatDelay</a>
     */
    public static int strokeTicks(Collection<ItemDef> worn) {
        int ticks = 140;
        ticks += piece(worn, 1, 6, ItemId.DESERT_SHIRT, 20, -40);
        ticks += piece(worn, 2, 7, ItemId.DESERT_ROBE, 20, -30);
        ticks += piece(worn, 9, 9, ItemId.DESERT_BOOTS, 10, -10);
        ticks += piece(worn, 8, 8, null, 0, -10);
        ticks += piece(worn, 0, 5, null, 0, -10);
        return ticks;
    }

    // The server reads one item per part of the body, which can wear only one of its two slots at a time:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L4283-L4305
    private static int piece(
            Collection<ItemDef> worn, int slot, int otherSlot, @Nullable ItemId clothes, int cool, int hot) {
        @Nullable ItemDef item = null;
        for (ItemDef def : worn) {
            if (def.wearSlot() == slot) {
                item = def;
                break;
            }
            if (def.wearSlot() == otherSlot && item == null) {
                item = def;
            }
        }
        if (item == null) {
            return 0;
        }
        if (clothes != null && item.id() == clothes.id()) {
            return cool;
        }
        return item.armourBonus() > 0 ? hot : 0;
    }
}
