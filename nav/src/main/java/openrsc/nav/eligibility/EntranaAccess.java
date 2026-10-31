package openrsc.nav.eligibility;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;

/**
 * What the Monk of Entrana refuses: he ferries a player from Port Sarim only while nothing in the
 * inventory, or (on a world with an equipment tab) worn, is blocked. An item up to the scythe's id
 * is blocked when it is on the server's list; a later one when it is wearable anywhere but the neck
 * or cape and needs Ranged or has an aim, power or armour bonus, or, not wearable, when it is on the
 * server's short custom list. A custom-sprites world also blocks {@link #CABBAGE_ONLY}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/MonkOfEntrana.java#L349">MonkOfEntrana.itemIsBlocked</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/MonkOfEntrana.java#L375">MonkOfEntrana.playerNotAllowedOnEntrana</a>
 */
public final class EntranaAccess {

    /**
     * The server's list for items up to the scythe's id, followed by its list for later items that
     * are not wearable.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/MonkOfEntrana.java#L24">MonkOfEntrana.blockedItems</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/MonkOfEntrana.java#L341">MonkOfEntrana.blockedItemsCustom</a>
     */
    public static final Set<Integer> BLOCKED = IntStream.of(
                    ItemId.BRONZE_ARROWS.id(),
                    ItemId.IRON_ARROWS.id(),
                    ItemId.STEEL_ARROWS.id(),
                    ItemId.MITHRIL_ARROWS.id(),
                    ItemId.ADAMANTITE_ARROWS.id(),
                    ItemId.RUNE_ARROWS.id(),
                    ItemId.ICE_ARROWS.id(),
                    ItemId.POISON_BRONZE_ARROWS.id(),
                    ItemId.POISON_IRON_ARROWS.id(),
                    ItemId.POISON_STEEL_ARROWS.id(),
                    ItemId.POISON_MITHRIL_ARROWS.id(),
                    ItemId.POISON_ADAMANTITE_ARROWS.id(),
                    ItemId.POISON_RUNE_ARROWS.id(),
                    ItemId.BRONZE_ARROW_HEADS.id(),
                    ItemId.IRON_ARROW_HEADS.id(),
                    ItemId.STEEL_ARROW_HEADS.id(),
                    ItemId.MITHRIL_ARROW_HEADS.id(),
                    ItemId.ADAMANTITE_ARROW_HEADS.id(),
                    ItemId.RUNE_ARROW_HEADS.id(),
                    ItemId.BRONZE_AXE.id(),
                    ItemId.IRON_AXE.id(),
                    ItemId.STEEL_AXE.id(),
                    ItemId.BLACK_AXE.id(),
                    ItemId.MITHRIL_AXE.id(),
                    ItemId.ADAMANTITE_AXE.id(),
                    ItemId.RUNE_AXE.id(),
                    ItemId.BRONZE_BATTLE_AXE.id(),
                    ItemId.IRON_BATTLE_AXE.id(),
                    ItemId.STEEL_BATTLE_AXE.id(),
                    ItemId.BLACK_BATTLE_AXE.id(),
                    ItemId.MITHRIL_BATTLE_AXE.id(),
                    ItemId.ADAMANTITE_BATTLE_AXE.id(),
                    ItemId.RUNE_BATTLE_AXE.id(),
                    ItemId.DRAGON_AXE.id(),
                    ItemId.BATTLESTAFF.id(),
                    ItemId.BATTLESTAFF_OF_AIR.id(),
                    ItemId.BATTLESTAFF_OF_WATER.id(),
                    ItemId.BATTLESTAFF_OF_EARTH.id(),
                    ItemId.BATTLESTAFF_OF_FIRE.id(),
                    ItemId.ENCHANTED_BATTLESTAFF_OF_AIR.id(),
                    ItemId.ENCHANTED_BATTLESTAFF_OF_WATER.id(),
                    ItemId.ENCHANTED_BATTLESTAFF_OF_EARTH.id(),
                    ItemId.ENCHANTED_BATTLESTAFF_OF_FIRE.id(),
                    ItemId.LONGBOW.id(),
                    ItemId.OAK_LONGBOW.id(),
                    ItemId.WILLOW_LONGBOW.id(),
                    ItemId.MAPLE_LONGBOW.id(),
                    ItemId.YEW_LONGBOW.id(),
                    ItemId.MAGIC_LONGBOW.id(),
                    ItemId.SHORTBOW.id(),
                    ItemId.OAK_SHORTBOW.id(),
                    ItemId.WILLOW_SHORTBOW.id(),
                    ItemId.MAPLE_SHORTBOW.id(),
                    ItemId.YEW_SHORTBOW.id(),
                    ItemId.MAGIC_SHORTBOW.id(),
                    ItemId.LEATHER_ARMOUR.id(),
                    ItemId.BRONZE_CHAIN_MAIL_BODY.id(),
                    ItemId.IRON_CHAIN_MAIL_BODY.id(),
                    ItemId.STEEL_CHAIN_MAIL_BODY.id(),
                    ItemId.BLACK_CHAIN_MAIL_BODY.id(),
                    ItemId.MITHRIL_CHAIN_MAIL_BODY.id(),
                    ItemId.ADAMANTITE_CHAIN_MAIL_BODY.id(),
                    ItemId.RUNE_CHAIN_MAIL_BODY.id(),
                    ItemId.PHOENIX_CROSSBOW.id(),
                    ItemId.CROSSBOW.id(),
                    ItemId.CROSSBOW_BOLTS.id(),
                    ItemId.POISON_CROSSBOW_BOLTS.id(),
                    ItemId.OYSTER_PEARL_BOLTS.id(),
                    ItemId.OYSTER_PEARL_BOLT_TIPS.id(),
                    ItemId.BRONZE_DAGGER.id(),
                    ItemId.IRON_DAGGER.id(),
                    ItemId.STEEL_DAGGER.id(),
                    ItemId.BLACK_DAGGER.id(),
                    ItemId.MITHRIL_DAGGER.id(),
                    ItemId.ADAMANTITE_DAGGER.id(),
                    ItemId.RUNE_DAGGER.id(),
                    ItemId.POISONED_BRONZE_DAGGER.id(),
                    ItemId.POISONED_IRON_DAGGER.id(),
                    ItemId.POISONED_STEEL_DAGGER.id(),
                    ItemId.POISONED_BLACK_DAGGER.id(),
                    ItemId.POISONED_MITHRIL_DAGGER.id(),
                    ItemId.POISONED_ADAMANTITE_DAGGER.id(),
                    ItemId.POISONED_RUNE_DAGGER.id(),
                    ItemId.DWARF_CANNON_BASE.id(),
                    ItemId.DWARF_CANNON_STAND.id(),
                    ItemId.DWARF_CANNON_BARRELS.id(),
                    ItemId.DWARF_CANNON_FURNACE.id(),
                    ItemId.MEDIUM_BRONZE_HELMET.id(),
                    ItemId.MEDIUM_IRON_HELMET.id(),
                    ItemId.MEDIUM_STEEL_HELMET.id(),
                    ItemId.MEDIUM_BLACK_HELMET.id(),
                    ItemId.MEDIUM_MITHRIL_HELMET.id(),
                    ItemId.MEDIUM_ADAMANTITE_HELMET.id(),
                    ItemId.MEDIUM_RUNE_HELMET.id(),
                    ItemId.DRAGON_MEDIUM_HELMET.id(),
                    ItemId.LARGE_BRONZE_HELMET.id(),
                    ItemId.LARGE_IRON_HELMET.id(),
                    ItemId.LARGE_STEEL_HELMET.id(),
                    ItemId.LARGE_BLACK_HELMET.id(),
                    ItemId.LARGE_MITHRIL_HELMET.id(),
                    ItemId.LARGE_ADAMANTITE_HELMET.id(),
                    ItemId.LARGE_RUNE_HELMET.id(),
                    ItemId.BRONZE_MACE.id(),
                    ItemId.IRON_MACE.id(),
                    ItemId.STEEL_MACE.id(),
                    ItemId.BLACK_MACE.id(),
                    ItemId.MITHRIL_MACE.id(),
                    ItemId.ADAMANTITE_MACE.id(),
                    ItemId.RUNE_MACE.id(),
                    ItemId.BRONZE_PLATE_MAIL_BODY.id(),
                    ItemId.IRON_PLATE_MAIL_BODY.id(),
                    ItemId.STEEL_PLATE_MAIL_BODY.id(),
                    ItemId.BLACK_PLATE_MAIL_BODY.id(),
                    ItemId.MITHRIL_PLATE_MAIL_BODY.id(),
                    ItemId.ADAMANTITE_PLATE_MAIL_BODY.id(),
                    ItemId.RUNE_PLATE_MAIL_BODY.id(),
                    ItemId.BRONZE_PLATE_MAIL_LEGS.id(),
                    ItemId.IRON_PLATE_MAIL_LEGS.id(),
                    ItemId.STEEL_PLATE_MAIL_LEGS.id(),
                    ItemId.BLACK_PLATE_MAIL_LEGS.id(),
                    ItemId.MITHRIL_PLATE_MAIL_LEGS.id(),
                    ItemId.ADAMANTITE_PLATE_MAIL_LEGS.id(),
                    ItemId.RUNE_PLATE_MAIL_LEGS.id(),
                    ItemId.BRONZE_PLATE_MAIL_TOP.id(),
                    ItemId.IRON_PLATE_MAIL_TOP.id(),
                    ItemId.STEEL_PLATE_MAIL_TOP.id(),
                    ItemId.BLACK_PLATE_MAIL_TOP.id(),
                    ItemId.MITHRIL_PLATE_MAIL_TOP.id(),
                    ItemId.ADAMANTITE_PLATE_MAIL_TOP.id(),
                    ItemId.RUNE_PLATE_MAIL_TOP.id(),
                    ItemId.BRONZE_PLATED_SKIRT.id(),
                    ItemId.IRON_PLATED_SKIRT.id(),
                    ItemId.STEEL_PLATED_SKIRT.id(),
                    ItemId.BLACK_PLATED_SKIRT.id(),
                    ItemId.MITHRIL_PLATED_SKIRT.id(),
                    ItemId.ADAMANTITE_PLATED_SKIRT.id(),
                    ItemId.RUNE_SKIRT.id(),
                    ItemId.BRONZE_SCIMITAR.id(),
                    ItemId.IRON_SCIMITAR.id(),
                    ItemId.STEEL_SCIMITAR.id(),
                    ItemId.BLACK_SCIMITAR.id(),
                    ItemId.MITHRIL_SCIMITAR.id(),
                    ItemId.ADAMANTITE_SCIMITAR.id(),
                    ItemId.RUNE_SCIMITAR.id(),
                    ItemId.BRONZE_SQUARE_SHIELD.id(),
                    ItemId.IRON_SQUARE_SHIELD.id(),
                    ItemId.STEEL_SQUARE_SHIELD.id(),
                    ItemId.BLACK_SQUARE_SHIELD.id(),
                    ItemId.MITHRIL_SQUARE_SHIELD.id(),
                    ItemId.ADAMANTITE_SQUARE_SHIELD.id(),
                    ItemId.RUNE_SQUARE_SHIELD.id(),
                    ItemId.DRAGON_SQUARE_SHIELD.id(),
                    ItemId.BRONZE_KITE_SHIELD.id(),
                    ItemId.IRON_KITE_SHIELD.id(),
                    ItemId.STEEL_KITE_SHIELD.id(),
                    ItemId.BLACK_KITE_SHIELD.id(),
                    ItemId.MITHRIL_KITE_SHIELD.id(),
                    ItemId.ADAMANTITE_KITE_SHIELD.id(),
                    ItemId.RUNE_KITE_SHIELD.id(),
                    ItemId.WOODEN_SHIELD.id(),
                    ItemId.ANTI_DRAGON_BREATH_SHIELD.id(),
                    ItemId.BRONZE_SPEAR.id(),
                    ItemId.IRON_SPEAR.id(),
                    ItemId.STEEL_SPEAR.id(),
                    ItemId.MITHRIL_SPEAR.id(),
                    ItemId.ADAMANTITE_SPEAR.id(),
                    ItemId.RUNE_SPEAR.id(),
                    ItemId.POISONED_BRONZE_SPEAR.id(),
                    ItemId.POISONED_IRON_SPEAR.id(),
                    ItemId.POISONED_STEEL_SPEAR.id(),
                    ItemId.POISONED_MITHRIL_SPEAR.id(),
                    ItemId.POISONED_ADAMANTITE_SPEAR.id(),
                    ItemId.POISONED_RUNE_SPEAR.id(),
                    ItemId.BRONZE_2_HANDED_SWORD.id(),
                    ItemId.IRON_2_HANDED_SWORD.id(),
                    ItemId.STEEL_2_HANDED_SWORD.id(),
                    ItemId.BLACK_2_HANDED_SWORD.id(),
                    ItemId.MITHRIL_2_HANDED_SWORD.id(),
                    ItemId.ADAMANTITE_2_HANDED_SWORD.id(),
                    ItemId.RUNE_2_HANDED_SWORD.id(),
                    ItemId.BRONZE_SHORT_SWORD.id(),
                    ItemId.IRON_SHORT_SWORD.id(),
                    ItemId.STEEL_SHORT_SWORD.id(),
                    ItemId.BLACK_SHORT_SWORD.id(),
                    ItemId.MITHRIL_SHORT_SWORD.id(),
                    ItemId.ADAMANTITE_SHORT_SWORD.id(),
                    ItemId.RUNE_SHORT_SWORD.id(),
                    ItemId.BRONZE_LONG_SWORD.id(),
                    ItemId.IRON_LONG_SWORD.id(),
                    ItemId.STEEL_LONG_SWORD.id(),
                    ItemId.BLACK_LONG_SWORD.id(),
                    ItemId.MITHRIL_LONG_SWORD.id(),
                    ItemId.ADAMANTITE_LONG_SWORD.id(),
                    ItemId.RUNE_LONG_SWORD.id(),
                    ItemId.DRAGON_SWORD.id(),
                    ItemId.BRONZE_THROWING_DART.id(),
                    ItemId.IRON_THROWING_DART.id(),
                    ItemId.STEEL_THROWING_DART.id(),
                    ItemId.MITHRIL_THROWING_DART.id(),
                    ItemId.ADAMANTITE_THROWING_DART.id(),
                    ItemId.RUNE_THROWING_DART.id(),
                    ItemId.BRONZE_DART_TIPS.id(),
                    ItemId.IRON_DART_TIPS.id(),
                    ItemId.STEEL_DART_TIPS.id(),
                    ItemId.MITHRIL_DART_TIPS.id(),
                    ItemId.ADAMANTITE_DART_TIPS.id(),
                    ItemId.RUNE_DART_TIPS.id(),
                    ItemId.POISONED_BRONZE_THROWING_DART.id(),
                    ItemId.POISONED_IRON_THROWING_DART.id(),
                    ItemId.POISONED_STEEL_THROWING_DART.id(),
                    ItemId.POISONED_MITHRIL_THROWING_DART.id(),
                    ItemId.POISONED_ADAMANTITE_THROWING_DART.id(),
                    ItemId.POISONED_RUNE_THROWING_DART.id(),
                    ItemId.BRONZE_THROWING_KNIFE.id(),
                    ItemId.IRON_THROWING_KNIFE.id(),
                    ItemId.STEEL_THROWING_KNIFE.id(),
                    ItemId.BLACK_THROWING_KNIFE.id(),
                    ItemId.MITHRIL_THROWING_KNIFE.id(),
                    ItemId.ADAMANTITE_THROWING_KNIFE.id(),
                    ItemId.RUNE_THROWING_KNIFE.id(),
                    ItemId.POISONED_BRONZE_THROWING_KNIFE.id(),
                    ItemId.POISONED_IRON_THROWING_KNIFE.id(),
                    ItemId.POISONED_STEEL_THROWING_KNIFE.id(),
                    ItemId.POISONED_BLACK_THROWING_KNIFE.id(),
                    ItemId.POISONED_MITHRIL_THROWING_KNIFE.id(),
                    ItemId.POISONED_ADAMANTITE_THROWING_KNIFE.id(),
                    ItemId.POISONED_RUNE_THROWING_KNIFE.id(),
                    ItemId.SILVERLIGHT.id(),
                    ItemId.STAKE.id(),
                    ItemId.EXCALIBUR.id(),
                    ItemId.FALADIAN_KNIGHTS_SWORD.id(),
                    ItemId.MACE_OF_ZAMORAK.id(),
                    ItemId.KHAZARD_HELMET.id(),
                    ItemId.KHAZARD_CHAINMAIL.id(),
                    ItemId.BLOODY_AXE_OF_ZAMORAK.id(),
                    ItemId.CARNILLEAN_ARMOUR.id(),
                    ItemId.CATTLE_PROD.id(),
                    ItemId.STAFF_OF_IBAN.id(),
                    ItemId.STAFF_OF_ARMADYL.id(),
                    ItemId.PROTOTYPE_THROWING_DART.id(),
                    ItemId.PROTOTYPE_DART_TIP.id(),
                    ItemId.HALLOWEEN_CRACKER.id(),
                    ItemId.DRAGON_ARROWS.id(),
                    ItemId.POISON_DRAGON_ARROWS.id(),
                    ItemId.DRAGON_BOLTS.id(),
                    ItemId.POISON_DRAGON_BOLTS.id())
            .boxed()
            .collect(Collectors.toUnmodifiableSet());

    /**
     * What the monk blocks only on a world with custom sprites: a present and every pickaxe.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/portsarim/MonkOfEntrana.java#L331">MonkOfEntrana.blockedItemsOnlyOnCabbage</a>
     */
    public static final Set<Integer> CABBAGE_ONLY = Set.of(
            ItemId.PRESENT.id(),
            ItemId.BRONZE_PICKAXE.id(),
            ItemId.IRON_PICKAXE.id(),
            ItemId.STEEL_PICKAXE.id(),
            ItemId.MITHRIL_PICKAXE.id(),
            ItemId.ADAMANTITE_PICKAXE.id(),
            ItemId.RUNE_PICKAXE.id());

    private static final int NECK = 10;

    private static final int CAPE = 11;

    private EntranaAccess() {}

    /** Whether the monk blocks {@code itemId} on {@code sd}'s world. */
    public static boolean blocked(int itemId, ServerData sd) {
        if (sd.world().customSprites() && CABBAGE_ONLY.contains(itemId)) {
            return true;
        }
        if (itemId <= ItemId.SCYTHE.id()) {
            return BLOCKED.contains(itemId);
        }
        ItemDef def = sd.item(itemId);
        if (def != null && def.wearable()) {
            if (def.wearSlot() == NECK || def.wearSlot() == CAPE) {
                return false;
            }
            if (def.requiredSkillId() == Skill.RANGED.id) {
                return true;
            }
            return def.weaponPowerBonus() != 0 || def.weaponAimBonus() != 0 || def.armourBonus() != 0;
        }
        return BLOCKED.contains(itemId);
    }

    /** Whether any carried slot, worn or not, holds an item the monk blocks. */
    public static boolean carriesBlockedGear(AccountFacts state, ServerData sd) {
        for (InventorySlot it : state.inventory().slots()) {
            if (blocked(it.id(), sd)) {
                return true;
            }
        }
        return false;
    }
}
