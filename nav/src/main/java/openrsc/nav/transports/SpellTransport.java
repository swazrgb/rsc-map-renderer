package openrsc.nav.transports;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.TeleportRegion;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * Cast a teleport spell on oneself and land on its fixed tile. It lists no entries: it fires from
 * any tile of {@link TeleportRegion#SPELLS}. Gated on the current Magic level, as the server checks
 * it, the runes carried, which it spends, and the account's opt-in to teleport spells.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L207">SpellHandler.spellSanityChecks</a>
 */
public final class SpellTransport implements Transport, DeclarativeTransport {

    private String placementId;

    /** The runes one cast spends, item id to count, in the order given. */
    public static final class RuneCost extends LinkedHashMap<Integer, Integer> {

        /** From {@code itemId, count} pairs; a trailing odd value is ignored. */
        public RuneCost(int... pairs) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                put(pairs[i], pairs[i + 1]);
            }
        }
    }

    /**
     * Every teleport is refused while Ana in a barrel is carried.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1832">SpellHandler.canTeleport</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/DragonstoneAmulet.java#L59">DragonstoneAmulet.onOpInv</a>
     */
    public static final Fact NOT_HOLDING_ANA = Fact.notCarrying(ItemId.ANA_IN_A_BARREL.id());

    /**
     * Every teleport destroys a carried plague sample.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1867">SpellHandler.handleTeleport</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/DragonstoneAmulet.java#L81">DragonstoneAmulet.onOpInv</a>
     */
    public static final Fact NOT_CARRYING_PLAGUE_SAMPLE = Fact.notCarrying(ItemId.PLAGUE_SAMPLE.id());

    /**
     * A teleport from Karamja (for a spell, also from Brimhaven) destroys every Karamja rum carried.
     * Required everywhere, since a route does not say where a teleport is taken from.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1859">SpellHandler.handleTeleport</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/DragonstoneAmulet.java#L74">DragonstoneAmulet.onOpInv</a>
     */
    public static final Fact NOT_CARRYING_KARAMJA_RUM = Fact.notCarrying(ItemId.KARAMJA_RUM.id());

    /** The account's opt-in to spending runes on teleport spells; not a server rule. */
    public static final Fact OPTED_IN = Fact.teleportSpellsAllowed();

    private String name;
    private final int spellId;
    private final int magicLevel;
    private final Map<Integer, Integer> runes;
    private final int destX, destY;
    private final int cost;
    private final int requiresQuestComplete;
    private final String scrollKey;

    /**
     * A teleport the server allows only once the reward scroll of quest {@code requiresQuestComplete}
     * has been read, which stores cache key {@code scrollKey}; {@code -1} and {@code null} for none.
     * The scroll is untradable and given only on completion, so the gate asks for both the finished
     * quest and the key; an account that does not track the key is answered by the quest alone.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1839">SpellHandler.canTeleport</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/itemactions/InvAction.java#L466">InvAction.handleMagicScroll</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/itemactions/InvAction.java#L486">InvAction.handleSpellScroll</a>
     */
    public SpellTransport(
            int spellId,
            int magicLevel,
            Map<Integer, Integer> runes,
            int destX,
            int destY,
            int cost,
            int requiresQuestComplete,
            String scrollKey) {
        this.spellId = spellId;
        this.magicLevel = magicLevel;
        // Insertion order is kept: it orders the gate's facts and the spend.
        this.runes = Collections.unmodifiableMap(new LinkedHashMap<>(runes));
        this.destX = destX;
        this.destY = destY;
        this.cost = cost;
        this.requiresQuestComplete = requiresQuestComplete;
        this.scrollKey = scrollKey;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return TeleportRegion.SPELLS.contains(x, y);
    }

    @Override
    public TeleportRegion region() {
        return TeleportRegion.SPELLS;
    }

    @Override
    public List<Point> entries() {
        return List.of();
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public Prereq prereq() {
        var ps = new ArrayList<Prereq>(runes.size() + 6);
        ps.add(Prereq.fact(OPTED_IN));
        ps.add(Prereq.fact(NOT_HOLDING_ANA));
        ps.add(Prereq.fact(NOT_CARRYING_PLAGUE_SAMPLE));
        ps.add(Prereq.fact(NOT_CARRYING_KARAMJA_RUM));
        ps.add(Prereq.fact(Fact.skillAtLeast(Skill.MAGIC, magicLevel)));
        if (requiresQuestComplete >= 0) {
            ps.add(Prereq.fact(Fact.questComplete(requiresQuestComplete)));
        }
        if (scrollKey != null) {
            ps.add(Prereq.fact(Fact.cacheBool(scrollKey, true)));
        }
        for (var e : runes.entrySet()) {
            ps.add(Prereq.fact(Fact.carriedAtLeast(e.getKey(), e.getValue())));
        }
        return Prereq.all(ps.toArray(new Prereq[0]));
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        var out = new ArrayList<Consumed>(runes.size());
        for (var e : runes.entrySet()) {
            out.add(new Consumed(ItemId.getById(e.getKey()), e.getValue()));
        }
        return out;
    }

    @Override
    public int cost() {
        return cost;
    }

    public SpellTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    @Override
    public EngageSpec engageSpec() {
        return EngageSpec.of(new EngageSpec.Action.Cast(spellId), EngageSpec.Target.SELF);
    }
}
