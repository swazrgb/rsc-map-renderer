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
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * Cast a spell on a scenery object from one entry tile and be moved to a fixed tile, spending the
 * spell's runes. Gated on the current Magic level, as the server checks it, and the runes carried.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L207">SpellHandler.spellSanityChecks</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestDarkMetalGate.java#L90">LegendsQuestDarkMetalGate.onSpellLoc</a>
 */
public final class SpellLocTransport implements Transport, DeclarativeTransport {

    private String placementId;

    private String name;
    private final int spellId;
    private final int magicLevel;
    private final Map<Integer, Integer> runes;
    private final int objectId;
    private final int sceneryX, sceneryY;
    private final int entryX, entryY;
    private final int destX, destY;
    private final int cost;

    public SpellLocTransport(
            int spellId,
            int magicLevel,
            Map<Integer, Integer> runes,
            int objectId,
            int sceneryX,
            int sceneryY,
            int entryX,
            int entryY,
            int destX,
            int destY,
            int cost) {
        this.spellId = spellId;
        this.magicLevel = magicLevel;
        this.runes = Collections.unmodifiableMap(new LinkedHashMap<>(runes));
        this.objectId = objectId;
        this.sceneryX = sceneryX;
        this.sceneryY = sceneryY;
        this.entryX = entryX;
        this.entryY = entryY;
        this.destX = destX;
        this.destY = destY;
        this.cost = cost;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == entryX && y == entryY;
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(entryX, entryY));
    }

    @Override
    public Point sceneryAnchor() {
        return Point.of(sceneryX, sceneryY);
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public Prereq prereq() {
        var ps = new ArrayList<Prereq>(runes.size() + 1);
        ps.add(Prereq.fact(Fact.skillAtLeast(Skill.MAGIC, magicLevel)));
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

    public SpellLocTransport withPlacement(String id) {
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
        return new EngageSpec(
                new EngageSpec.Action.Cast(spellId),
                new EngageSpec.Target.Scenery(objectId, sceneryX, sceneryY),
                List.of(),
                true,
                null);
    }
}
