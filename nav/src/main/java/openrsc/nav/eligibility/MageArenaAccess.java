package openrsc.nav.eligibility;

import java.util.Set;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;

/**
 * What the Mage Arena barrier refuses: it lets a player into the arena only while nothing carried,
 * in the pack or (on a world with an equipment tab) worn, is refused. A wearable is refused unless it
 * goes on the neck or cape, is one of {@link #STAVES}, is low-tier magic or prayer gear (a bonus of 1
 * to 10 with a melee bonus of at most 5), is armour of at most 2 with no aim or power, or is the ice
 * gloves. A non-wearable passes, except the {@link #STAT_BOOSTING_POTIONS} on a Combat Odyssey world.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/mage_arena/MageArena.java#L446">MageArena.isNotAllowed</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/mage_arena/MageArena.java#L494">MageArena.cantGo</a>
 */
public final class MageArenaAccess {

    /**
     * The staves the barrier lets through although they are weapons.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/mage_arena/MageArena.java#L415">MageArena.staves</a>
     */
    public static final Set<Integer> STAVES = Set.of(
            ItemId.STAFF.id(),
            ItemId.MAGIC_STAFF.id(),
            ItemId.STAFF_OF_AIR.id(),
            ItemId.STAFF_OF_WATER.id(),
            ItemId.STAFF_OF_EARTH.id(),
            ItemId.STAFF_OF_FIRE.id(),
            ItemId.STAFF_OF_SARADOMIN.id(),
            ItemId.STAFF_OF_ZAMORAK.id(),
            ItemId.STAFF_OF_GUTHIX.id());

    /**
     * The potions the barrier also refuses on a Combat Odyssey world.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/minigames/mage_arena/MageArena.java#L427">MageArena.statBoostingPotions</a>
     */
    public static final Set<Integer> STAT_BOOSTING_POTIONS = Set.of(
            ItemId.FULL_ATTACK_POTION.id(),
            ItemId.TWO_ATTACK_POTION.id(),
            ItemId.ONE_ATTACK_POTION.id(),
            ItemId.FULL_STRENGTH_POTION.id(),
            ItemId.THREE_STRENGTH_POTION.id(),
            ItemId.TWO_STRENGTH_POTION.id(),
            ItemId.ONE_STRENGTH_POTION.id(),
            ItemId.FULL_SUPER_ATTACK_POTION.id(),
            ItemId.TWO_SUPER_ATTACK_POTION.id(),
            ItemId.ONE_SUPER_ATTACK_POTION.id(),
            ItemId.FULL_SUPER_STRENGTH_POTION.id(),
            ItemId.TWO_SUPER_STRENGTH_POTION.id(),
            ItemId.ONE_SUPER_STRENGTH_POTION.id(),
            ItemId.FULL_STAT_RESTORATION_POTION.id(),
            ItemId.TWO_STAT_RESTORATION_POTION.id(),
            ItemId.ONE_STAT_RESTORATION_POTION.id());

    private static final int OFFHAND = 3;

    private static final int MAINHAND = 4;
    private static final int NECK = 10;
    private static final int CAPE = 11;

    private MageArenaAccess() {}

    /** Whether the barrier refuses {@code itemId} on {@code sd}'s world; an unknown id passes. */
    public static boolean refused(int itemId, ServerData sd) {
        ItemDef def = sd.item(itemId);
        if (def == null) {
            return false;
        }
        if (!def.wearable()) {
            return sd.world().rules().combatOdyssey() && STAT_BOOSTING_POTIONS.contains(itemId);
        }
        int pos = def.wearSlot();
        if (pos == NECK || pos == CAPE) {
            return false;
        }
        if (STAVES.contains(itemId)) {
            return false;
        }
        if (pos == MAINHAND || pos == OFFHAND) {
            return true;
        }
        // The server's melee bonus counts aim twice and power not at all:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/ItemDefinition.java#L433
        long meleeBonus = (long) def.armourBonus() + def.weaponAimBonus() + def.weaponAimBonus();
        if (((def.magicBonus() > 0 && def.magicBonus() <= 10) || (def.prayerBonus() > 0 && def.prayerBonus() <= 10))
                && meleeBonus <= 5) {
            return false;
        }
        if ((def.armourBonus() <= 2 && def.weaponPowerBonus() == 0 && def.weaponAimBonus() == 0)
                || itemId == ItemId.ICE_GLOVES.id()) {
            return false;
        }
        return true;
    }

    /** Whether any carried slot, worn or not and on every world, holds something the barrier refuses. */
    public static boolean carriesRefusedGear(AccountFacts state, ServerData sd) {
        for (InventorySlot it : state.inventory().slots()) {
            if (refused(it.id(), sd)) {
                return true;
            }
        }
        return false;
    }
}
