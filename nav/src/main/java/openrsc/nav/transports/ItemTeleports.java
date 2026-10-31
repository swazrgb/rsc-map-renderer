package openrsc.nav.transports;

import static openrsc.nav.transports.spec.ItemRub.rub;
import static openrsc.nav.transports.spec.OperateItem.operate;
import static openrsc.nav.transports.spec.Rows.canKillAt;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.when;
import static openrsc.nav.transports.spec.WorldGate.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.nav.NavPolicy;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

public enum ItemTeleports implements TransportDef {
    DRAGONSTONE_AMULET_EDGEVILLE(rub(ItemId.CHARGED_DRAGONSTONE_AMULET.id(), to(226, 447), 60, "edgeville")),
    DRAGONSTONE_AMULET_KARAMJA(rub(ItemId.CHARGED_DRAGONSTONE_AMULET.id(), to(360, 696), 60, "karamja")),
    DRAGONSTONE_AMULET_DRAYNOR(rub(ItemId.CHARGED_DRAGONSTONE_AMULET.id(), to(214, 632), 60, "draynor")),
    DRAGONSTONE_AMULET_AL_KHARID(rub(ItemId.CHARGED_DRAGONSTONE_AMULET.id(), to(72, 696), 60, "al kharid")),

    /**
     * Trips on 2 in 255 uses, for 1 damage, and leaves the player where they stood.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/AgilityCape.java#L39">AgilityCape.onOpInv</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/RuneScript.java#L173">RuneScript.ifrandom</a>
     */
    AGILITY_CAPE_YANILLE_AGILITY_DUNGEON(onlyIn(
            item(ItemId.AGILITY_CAPE.id()),
            operate(
                    ItemId.AGILITY_CAPE.id(),
                    to(591, 765),
                    60,
                    when(Prereq.fact(SpellTransport.NOT_HOLDING_ANA)),
                    canKillAt(1),
                    note("soft roll: trips 2/255 of uses (ifrandom(2)) for 1 damage and stays put")))),
    /**
     * The server destroys a carried plague sample rather than refusing; the gate keeps the sample.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/CraftingCape.java#L39">CraftingCape.checkPlagueSample</a>
     */
    CRAFTING_CAPE_CRAFTING_GUILD(onlyIn(
            item(ItemId.CRAFTING_CAPE.id()),
            operate(
                    ItemId.CRAFTING_CAPE.id(),
                    to(347, 599),
                    60,
                    when(Prereq.all(
                            Prereq.fact(SpellTransport.NOT_HOLDING_ANA),
                            Prereq.fact(SpellTransport.NOT_CARRYING_PLAGUE_SAMPLE)))))),
    /**
     * Each use spends one of up to 10 sharks stored in the cape. The server destroys a carried plague
     * sample, and Karamja rum when used in Karamja, rather than refusing; the gate keeps both.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/FishingCape.java#L65">FishingCape.onOpInv</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/FishingCape.java#L54">FishingCape.onOpInv</a>
     */
    FISHING_CAPE_FISHING_GUILD(onlyIn(
            item(ItemId.FISHING_CAPE.id()),
            operate(
                    ItemId.FISHING_CAPE.id(),
                    to(586, 522),
                    60,
                    when(Prereq.all(
                            Prereq.fact(SpellTransport.NOT_HOLDING_ANA),
                            Prereq.fact(SpellTransport.NOT_CARRYING_PLAGUE_SAMPLE),
                            Prereq.fact(SpellTransport.NOT_CARRYING_KARAMJA_RUM),
                            Prereq.fact(Fact.cacheBool(ItemTeleports.FISHING_CAPE_EMPTY, false))))))),
    ;

    /**
     * The cache key that is {@code true} once the fishing cape is known to hold no sharks. Unset counts
     * as usable: the server fills a cape it has no count for on its first use.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/FishingCape.java#L47">FishingCape.onOpInv</a>
     */
    public static final String FISHING_CAPE_EMPTY = "fishing.capeEmpty";

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    ItemTeleports(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static NavPolicy prohibitAll(NavPolicy policy) {
        NavPolicy out = policy;
        for (ItemTeleports t : values()) {
            out = out.prohibit(t);
        }
        return out;
    }

    public static boolean allProhibited(NavPolicy policy) {
        for (ItemTeleports t : values()) {
            Integer cost = policy.costOverrides().get(t.key());
            if (cost == null || cost != NavPolicy.PROHIBITIVE) {
                return false;
            }
        }
        return true;
    }

    /** Returns {@code policy} without any cost override on an item teleport, whatever its value. */
    public static NavPolicy withoutProhibits(NavPolicy policy) {
        Map<String, Integer> kept = new HashMap<>(policy.costOverrides());
        for (ItemTeleports t : values()) {
            kept.remove(t.key());
        }
        return new NavPolicy(
                policy.danger(),
                policy.teleportSpells(),
                policy.teleportJewellery(),
                Map.copyOf(kept),
                policy.assumeAllEligible(),
                policy.avoidSkulled(),
                policy.berth());
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (ItemTeleports t : values()) {
            out.addAll(t.expand(env));
        }
        return out;
    }
}
