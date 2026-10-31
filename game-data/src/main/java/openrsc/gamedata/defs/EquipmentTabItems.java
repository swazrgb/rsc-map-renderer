package openrsc.gamedata.defs;

import openrsc.gamedata.data.ItemId;

/**
 * The item defs a world with {@code want_equipment_tab} changes as it loads: arrows and bolts become
 * wearable in the ammo slot, pickaxes in the weapon hand, and the unenchanted rings in the ring slot.
 * There a bow fires only the ammunition worn.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L463">EntityHandler.customItemConditions</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/RangeEvent.java#L134">RangeEvent.run</a>
 */
public final class EquipmentTabItems {

    private EquipmentTabItems() {}

    /**
     * The wearable type of arrows.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/RangeUtils.java#L30">RangeUtils.WEARABLE_ARROWS_ID</a>
     */
    public static final int WEARABLE_ARROWS = 1000;

    /**
     * The wearable type of bolts.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/RangeUtils.java#L31">RangeUtils.WEARABLE_BOLTS_ID</a>
     */
    public static final int WEARABLE_BOLTS = 1001;

    /**
     * The equipment slot ammunition is worn in.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L907">Equipment.EquipmentSlot.SLOT_AMMO</a>
     */
    public static final int AMMO_SLOT = 12;

    /** The equipment slot a ring is worn in. */
    public static final int RING_SLOT = 13;

    private static final int WEAPON_TYPE = 16;

    private static final int WEAPON_SLOT = 4;

    private static final int RING_TYPE = 1200;

    private static final int RANGED = 4;

    private static final int ATTACK = 0;

    private static final ItemId[] ARROWS = {
        ItemId.BRONZE_ARROWS,
        ItemId.POISON_BRONZE_ARROWS,
        ItemId.IRON_ARROWS,
        ItemId.POISON_IRON_ARROWS,
        ItemId.STEEL_ARROWS,
        ItemId.POISON_STEEL_ARROWS,
        ItemId.MITHRIL_ARROWS,
        ItemId.POISON_MITHRIL_ARROWS,
        ItemId.ADAMANTITE_ARROWS,
        ItemId.POISON_ADAMANTITE_ARROWS,
        ItemId.RUNE_ARROWS,
        ItemId.POISON_RUNE_ARROWS,
        ItemId.ICE_ARROWS,
        ItemId.DRAGON_ARROWS,
        ItemId.POISON_DRAGON_ARROWS
    };

    private static final ItemId[] BOLTS = {
        ItemId.CROSSBOW_BOLTS,
        ItemId.POISON_CROSSBOW_BOLTS,
        ItemId.OYSTER_PEARL_BOLTS,
        ItemId.POISON_DRAGON_BOLTS,
        ItemId.DRAGON_BOLTS
    };

    // Pickaxe, Attack level, aim, power and appearance, as the server sets them.
    private static final int[][] PICKAXES = {
        {ItemId.BRONZE_PICKAXE.id(), 1, 0, 0, 434},
        {ItemId.IRON_PICKAXE.id(), 1, 7, 5, 435},
        {ItemId.STEEL_PICKAXE.id(), 5, 11, 8, 436},
        {ItemId.MITHRIL_PICKAXE.id(), 20, 16, 12, 437},
        {ItemId.ADAMANTITE_PICKAXE.id(), 30, 23, 17, 438},
        {ItemId.RUNE_PICKAXE.id(), 40, 36, 26, 439},
    };

    private static final ItemId[] RINGS = {
        ItemId.GOLD_RING,
        ItemId.SAPPHIRE_RING,
        ItemId.EMERALD_RING,
        ItemId.RUBY_RING,
        ItemId.DIAMOND_RING,
        ItemId.DRAGONSTONE_RING,
        ItemId.OPAL_RING
    };

    /** A copy of {@code defs} with these changes made; {@code defs} is not changed. */
    public static ItemDefs apply(ItemDefs defs) {
        ItemDef[] out = new ItemDef[defs.size()];
        for (int id = 0; id < out.length; id++) {
            out[id] = defs.get(id);
        }
        for (ItemId arrow : ARROWS) {
            wear(out, arrow.id(), WEARABLE_ARROWS, AMMO_SLOT, 1, RANGED);
        }
        for (ItemId bolt : BOLTS) {
            wear(out, bolt.id(), WEARABLE_BOLTS, AMMO_SLOT, 1, RANGED);
        }
        for (int[] p : PICKAXES) {
            ItemDef d = p[0] < out.length ? out[p[0]] : null;
            if (d != null) {
                out[p[0]] = new ItemDef(
                        d.id(),
                        d.name(),
                        d.description(),
                        d.basePrice(),
                        d.stackable(),
                        true,
                        p[1],
                        ATTACK,
                        WEAPON_SLOT,
                        WEAPON_TYPE,
                        d.untradable(),
                        d.noteable(),
                        d.membersOnly(),
                        d.femaleOnly(),
                        d.command(),
                        p[4],
                        d.armourBonus(),
                        p[2],
                        p[3],
                        d.magicBonus(),
                        d.prayerBonus());
            }
        }
        for (ItemId ring : RINGS) {
            ItemDef d = ring.id() < out.length ? out[ring.id()] : null;
            if (d != null) {
                // The server sets a ring's skill and leaves its required level.
                wear(out, ring.id(), RING_TYPE, RING_SLOT, d.requiredLevel(), ATTACK);
            }
        }
        return ItemDefs.of(out);
    }

    private static void wear(ItemDef[] out, int id, int wearableId, int slot, int level, int skill) {
        ItemDef d = id < out.length ? out[id] : null;
        if (d == null) {
            return;
        }
        out[id] = new ItemDef(
                d.id(),
                d.name(),
                d.description(),
                d.basePrice(),
                d.stackable(),
                true,
                level,
                skill,
                slot,
                wearableId,
                d.untradable(),
                d.noteable(),
                d.membersOnly(),
                d.femaleOnly(),
                d.command(),
                d.appearanceId(),
                d.armourBonus(),
                d.weaponAimBonus(),
                d.weaponPowerBonus(),
                d.magicBonus(),
                d.prayerBonus());
    }
}
