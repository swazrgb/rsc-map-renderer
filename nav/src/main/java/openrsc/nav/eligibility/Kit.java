package openrsc.nav.eligibility;

import java.util.List;
import java.util.stream.IntStream;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.eligibility.EligibilityInputs.Fact;

/**
 * Item families a server check accepts any member of, each a {@link Prereq#named} gate, so that a
 * description reads "a pickaxe" rather than an or-chain of six items.
 */
public final class Kit {

    private Kit() {}

    /**
     * Any pickaxe, best first. The server takes the best one the player carries and may use at its
     * Mining level; that level is not part of this gate.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/mining/Mining.java#L27">Mining.getAxe</a>
     */
    public static final Prereq PICKAXE = carryingAny(
            "a pickaxe",
            ItemId.RUNE_PICKAXE.id(),
            ItemId.ADAMANTITE_PICKAXE.id(),
            ItemId.MITHRIL_PICKAXE.id(),
            ItemId.STEEL_PICKAXE.id(),
            ItemId.IRON_PICKAXE.id(),
            ItemId.BRONZE_PICKAXE.id());

    /**
     * Any woodcutting axe. The dragon battle axe is not one: the server's list leaves it out.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L91">Formulae.woodcuttingAxeIDs</a>
     */
    public static final Prereq AXE = carryingAny(
            "an axe",
            ItemId.BRONZE_AXE.id(),
            ItemId.IRON_AXE.id(),
            ItemId.STEEL_AXE.id(),
            ItemId.BLACK_AXE.id(),
            ItemId.MITHRIL_AXE.id(),
            ItemId.ADAMANTITE_AXE.id(),
            ItemId.RUNE_AXE.id(),
            ItemId.DRAGON_WOODCUTTING_AXE.id());

    /** Bows and crossbows, strung ones first. */
    public static final List<ItemId> BOW = List.of(
            ItemId.DRAGON_CROSSBOW,
            ItemId.DRAGON_LONGBOW,
            ItemId.MAGIC_LONGBOW,
            ItemId.MAGIC_SHORTBOW,
            ItemId.YEW_LONGBOW,
            ItemId.YEW_SHORTBOW,
            ItemId.MAPLE_LONGBOW,
            ItemId.MAPLE_SHORTBOW,
            ItemId.WILLOW_LONGBOW,
            ItemId.WILLOW_SHORTBOW,
            ItemId.OAK_LONGBOW,
            ItemId.OAK_SHORTBOW,
            ItemId.LONGBOW,
            ItemId.SHORTBOW,
            ItemId.PHOENIX_CROSSBOW,
            ItemId.CROSSBOW,
            ItemId.UNSTRUNG_MAGIC_LONGBOW,
            ItemId.UNSTRUNG_MAGIC_SHORTBOW,
            ItemId.UNSTRUNG_YEW_LONGBOW,
            ItemId.UNSTRUNG_YEW_SHORTBOW,
            ItemId.UNSTRUNG_MAPLE_LONGBOW,
            ItemId.UNSTRUNG_MAPLE_SHORTBOW,
            ItemId.UNSTRUNG_WILLOW_LONGBOW,
            ItemId.UNSTRUNG_WILLOW_SHORTBOW,
            ItemId.UNSTRUNG_OAK_LONGBOW,
            ItemId.UNSTRUNG_OAK_SHORTBOW,
            ItemId.UNSTRUNG_LONGBOW,
            ItemId.UNSTRUNG_SHORTBOW);

    /**
     * Any of {@link #BOW}. The server's check passes any item whose name contains "bow", which every
     * member does.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L151">UndergroundPassMechanismMap1.hasABow</a>
     */
    public static final Prereq BOW_GATE =
            carryingAny("a bow", BOW.stream().mapToInt(ItemId::id).toArray());

    /** Every arrow stack, poisoned and headless ones included, cheapest first. */
    public static final List<ItemId> ARROWS = List.of(
            ItemId.BRONZE_ARROWS,
            ItemId.HEADLESS_ARROWS,
            ItemId.IRON_ARROWS,
            ItemId.POISON_BRONZE_ARROWS,
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
            ItemId.POISON_DRAGON_ARROWS);

    /**
     * Any of {@link #ARROWS}. The server takes any item whose name contains "arrows", which every
     * member does.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L31">UndergroundPassMechanismMap1.blockUseInv</a>
     */
    public static final Prereq ARROWS_GATE =
            carryingAny("arrows", ARROWS.stream().mapToInt(ItemId::id).toArray());

    /**
     * A lit candle, lit black candle or lit torch.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TempleOfIkov.java#L477">TempleOfIkov.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/watchtower/WatchTowerObstacles.java#L210">WatchTowerObstacles.onOpLoc</a>
     */
    public static final Prereq LIT_LIGHT = carryingAny(
            "a lit light source", ItemId.LIT_CANDLE.id(), ItemId.LIT_BLACK_CANDLE.id(), ItemId.LIT_TORCH.id());

    /**
     * A knife or a melee weapon, for a web an item is used on.
     *
     * @see MeleeWeapons
     */
    public static final Prereq WEB_CUTTER = Prereq.named(
            "a knife or a melee weapon",
            Prereq.any(
                    Prereq.fact(Fact.carriedAtLeast(ItemId.KNIFE.id(), 1)), Prereq.fact(Fact.carryingMeleeWeapon())));

    /**
     * The facts of {@link #WEB_CUTTER} under the name a world where webs are clicked deserves: there
     * {@link MeleeWeapons} counts every main-hand item, staves and bows included.
     */
    public static final Prereq WEB_CUTTER_ANY_WEAPON = Prereq.named(
            "a knife or any weapon",
            Prereq.any(
                    Prereq.fact(Fact.carriedAtLeast(ItemId.KNIFE.id(), 1)), Prereq.fact(Fact.carryingMeleeWeapon())));

    /**
     * What the Cooking Guild door asks to be worn, on a world that has the cooking cape.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L719">DoorAction.onWallObjectAction</a>
     */
    public static final Prereq CHEF_GEAR =
            wearingAny("a chef's hat or cooking cape", ItemId.CHEFS_HAT.id(), ItemId.COOKING_CAPE.id());

    /** {@link #CHEF_GEAR} on a world without the cooking cape. */
    public static final Prereq CHEFS_HAT = wearingAny("a chef's hat", ItemId.CHEFS_HAT.id());

    /**
     * What the Crafting Guild door asks to be worn, on a world that has the crafting cape.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L696">DoorAction.onWallObjectAction</a>
     */
    public static final Prereq CRAFTING_GEAR =
            wearingAny("a brown apron or crafting cape", ItemId.BROWN_APRON.id(), ItemId.CRAFTING_CAPE.id());

    /** {@link #CRAFTING_GEAR} on a world without the crafting cape. */
    public static final Prereq BROWN_APRON = wearingAny("a brown apron", ItemId.BROWN_APRON.id());

    private static Prereq carryingAny(String label, int... ids) {
        return Prereq.named(
                label,
                Prereq.any(IntStream.of(ids)
                        .mapToObj(id -> Prereq.fact(Fact.carriedAtLeast(id, 1)))
                        .toArray(Prereq[]::new)));
    }

    private static Prereq wearingAny(String label, int... ids) {
        return Prereq.named(
                label,
                Prereq.any(IntStream.of(ids)
                        .mapToObj(id -> Prereq.fact(Fact.wearing(id)))
                        .toArray(Prereq[]::new)));
    }
}
