package openrsc.gamedata.data;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bows, crossbows and the ammunition each fires, copied from the server's {@code RangeUtils} and
 * {@code Formulae}. Every id is an item id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/RangeUtils.java#L57">RangeUtils.ALLOWED_PROJECTILES</a>
 */
public final class RangeWeapons {

    private static final Set<Integer> BOWS = Set.of(
            ItemId.LONGBOW.id(),
            ItemId.SHORTBOW.id(),
            ItemId.OAK_LONGBOW.id(),
            ItemId.OAK_SHORTBOW.id(),
            ItemId.WILLOW_LONGBOW.id(),
            ItemId.WILLOW_SHORTBOW.id(),
            ItemId.MAPLE_LONGBOW.id(),
            ItemId.MAPLE_SHORTBOW.id(),
            ItemId.YEW_LONGBOW.id(),
            ItemId.YEW_SHORTBOW.id(),
            ItemId.MAGIC_LONGBOW.id(),
            ItemId.MAGIC_SHORTBOW.id(),
            ItemId.DRAGON_LONGBOW.id());

    private static final Set<Integer> CROSSBOWS =
            Set.of(ItemId.PHOENIX_CROSSBOW.id(), ItemId.CROSSBOW.id(), ItemId.DRAGON_CROSSBOW.id());

    private static final Set<Integer> SHORT_BOWS = Set.of(
            ItemId.SHORTBOW.id(),
            ItemId.OAK_SHORTBOW.id(),
            ItemId.WILLOW_SHORTBOW.id(),
            ItemId.MAPLE_SHORTBOW.id(),
            ItemId.YEW_SHORTBOW.id(),
            ItemId.MAGIC_SHORTBOW.id());

    /**
     * The range, in tiles, a longbow shoots from.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L41">Mob.DEFAULT_PROJECTILE_RADIUS</a>
     */
    public static final int DEFAULT_PROJECTILE_RADIUS = 5;

    private RangeWeapons() {}

    public static boolean isCrossbow(int weaponId) {
        return CROSSBOWS.contains(weaponId);
    }

    public static boolean isShortBow(int weaponId) {
        return SHORT_BOWS.contains(weaponId);
    }

    public static boolean isBow(int weaponId) {
        return BOWS.contains(weaponId);
    }

    public static boolean isRangeWeapon(int weaponId) {
        return isBow(weaponId) || isCrossbow(weaponId);
    }

    /**
     * The range, in tiles, the weapon shoots from: one less than a longbow for a shortbow or crossbow.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L3917">Player.getProjectileRadius</a>
     */
    public static int radius(int weaponId) {
        return isCrossbow(weaponId) || isShortBow(weaponId) ? DEFAULT_PROJECTILE_RADIUS - 1 : DEFAULT_PROJECTILE_RADIUS;
    }

    private static final Set<Integer> BASIC_ARROWS =
            ids(ItemId.BRONZE_ARROWS, ItemId.POISON_BRONZE_ARROWS, ItemId.IRON_ARROWS, ItemId.POISON_IRON_ARROWS);
    private static final Set<Integer> STEEL_ARROWS = ids(ItemId.STEEL_ARROWS, ItemId.POISON_STEEL_ARROWS);
    private static final Set<Integer> MITHRIL_ARROWS = ids(ItemId.MITHRIL_ARROWS, ItemId.POISON_MITHRIL_ARROWS);
    private static final Set<Integer> ADDY_ARROWS = ids(ItemId.ADAMANTITE_ARROWS, ItemId.POISON_ADAMANTITE_ARROWS);
    private static final Set<Integer> ICE_ARROWS = ids(ItemId.ICE_ARROWS);
    private static final Set<Integer> RUNE_ARROWS = ids(ItemId.RUNE_ARROWS, ItemId.POISON_RUNE_ARROWS);
    private static final Set<Integer> DRAGON_ARROWS = ids(ItemId.DRAGON_ARROWS, ItemId.POISON_DRAGON_ARROWS);
    private static final Set<Integer> BASIC_BOLTS =
            ids(ItemId.CROSSBOW_BOLTS, ItemId.POISON_CROSSBOW_BOLTS, ItemId.OYSTER_PEARL_BOLTS);
    private static final Set<Integer> DRAGON_BOLTS = ids(ItemId.DRAGON_BOLTS, ItemId.POISON_DRAGON_BOLTS);

    private static final Map<Integer, Set<Integer>> ALLOWED = Map.ofEntries(
            Map.entry(ItemId.SHORTBOW.id(), BASIC_ARROWS),
            Map.entry(ItemId.LONGBOW.id(), BASIC_ARROWS),
            Map.entry(ItemId.OAK_SHORTBOW.id(), union(BASIC_ARROWS, STEEL_ARROWS)),
            Map.entry(ItemId.OAK_LONGBOW.id(), union(BASIC_ARROWS, STEEL_ARROWS)),
            Map.entry(ItemId.WILLOW_SHORTBOW.id(), union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS)),
            Map.entry(ItemId.WILLOW_LONGBOW.id(), union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS)),
            Map.entry(ItemId.MAPLE_SHORTBOW.id(), union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS, ADDY_ARROWS)),
            Map.entry(ItemId.MAPLE_LONGBOW.id(), union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS, ADDY_ARROWS)),
            Map.entry(
                    ItemId.YEW_SHORTBOW.id(),
                    union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS, ADDY_ARROWS, ICE_ARROWS)),
            Map.entry(
                    ItemId.YEW_LONGBOW.id(),
                    union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS, ADDY_ARROWS, RUNE_ARROWS, ICE_ARROWS)),
            Map.entry(
                    ItemId.MAGIC_SHORTBOW.id(),
                    union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS, ADDY_ARROWS, RUNE_ARROWS, ICE_ARROWS)),
            Map.entry(
                    ItemId.MAGIC_LONGBOW.id(),
                    union(BASIC_ARROWS, STEEL_ARROWS, MITHRIL_ARROWS, ADDY_ARROWS, RUNE_ARROWS, ICE_ARROWS)),
            Map.entry(
                    ItemId.DRAGON_LONGBOW.id(),
                    union(
                            BASIC_ARROWS,
                            STEEL_ARROWS,
                            MITHRIL_ARROWS,
                            ADDY_ARROWS,
                            RUNE_ARROWS,
                            DRAGON_ARROWS,
                            ICE_ARROWS)),
            Map.entry(ItemId.CROSSBOW.id(), BASIC_BOLTS),
            Map.entry(ItemId.PHOENIX_CROSSBOW.id(), BASIC_BOLTS),
            Map.entry(ItemId.DRAGON_CROSSBOW.id(), union(BASIC_BOLTS, DRAGON_BOLTS)));

    /**
     * The order the server looks for arrows in the pack: it fires the first one held, and when the
     * bow cannot fire that one the shot fails rather than trying the next.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/projectile/RangeEvent.java#L196">RangeEvent.takeAmmoFromInventory</a>
     */
    public static final List<Integer> ARROW_ORDER = List.of(
            ItemId.POISON_DRAGON_ARROWS.id(),
            ItemId.DRAGON_ARROWS.id(),
            ItemId.ICE_ARROWS.id(),
            ItemId.POISON_RUNE_ARROWS.id(),
            ItemId.RUNE_ARROWS.id(),
            ItemId.POISON_ADAMANTITE_ARROWS.id(),
            ItemId.ADAMANTITE_ARROWS.id(),
            ItemId.POISON_MITHRIL_ARROWS.id(),
            ItemId.MITHRIL_ARROWS.id(),
            ItemId.POISON_STEEL_ARROWS.id(),
            ItemId.STEEL_ARROWS.id(),
            ItemId.POISON_IRON_ARROWS.id(),
            ItemId.IRON_ARROWS.id(),
            ItemId.POISON_BRONZE_ARROWS.id(),
            ItemId.BRONZE_ARROWS.id());

    /**
     * The order the server looks for bolts in the pack, as {@link #ARROW_ORDER} is for arrows.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L23">Formulae.boltIDs</a>
     */
    public static final List<Integer> BOLT_ORDER = List.of(
            ItemId.OYSTER_PEARL_BOLTS.id(),
            ItemId.POISON_CROSSBOW_BOLTS.id(),
            ItemId.CROSSBOW_BOLTS.id(),
            ItemId.DRAGON_BOLTS.id(),
            ItemId.POISON_DRAGON_BOLTS.id());

    public static boolean canFire(int weaponId, int ammoId) {
        Set<Integer> ok = ALLOWED.get(weaponId);
        return ok != null && ok.contains(ammoId);
    }

    /** {@link #BOLT_ORDER} for a crossbow, else {@link #ARROW_ORDER}. */
    public static List<Integer> ammoOrder(int weaponId) {
        return isCrossbow(weaponId) ? BOLT_ORDER : ARROW_ORDER;
    }

    public static boolean isAmmo(int itemId) {
        return ARROW_ORDER.contains(itemId) || BOLT_ORDER.contains(itemId);
    }

    private static Set<Integer> ids(ItemId... items) {
        return Stream.of(items).map(ItemId::id).collect(Collectors.toUnmodifiableSet());
    }

    @SafeVarargs
    private static Set<Integer> union(Set<Integer>... sets) {
        return Stream.of(sets).flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }
}
