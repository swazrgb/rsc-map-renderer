package openrsc.nav.transports;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * The rope swing onto Grew's island, played by a hand-written script: use a rope on the tree
 * ({@link #ROPE_ATTACH_ID}), which spends the rope and turns the tree into the tied rope
 * ({@link #ROPE_TIED_ID}) for a minute, then click the tied rope to swing across. The swing needs
 * Agility 30. The way back is an ordinary {@link AgilityObstacles} row.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L802">AgilityShortcuts.onUseLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/AgilityShortcuts.java#L416">AgilityShortcuts.onOpLoc</a>
 */
public final class GrewIslandRopeSwingTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.GREW_ISLAND_ROPE_SWING);

    public static final int ROPE_ATTACH_ID = 662;
    public static final int ROPE_TIED_ID = 663;

    public static final int ROPE_ITEM_ID = ItemId.ROPE.id();
    public static final int MIN_AGILITY = 30;
    private static final int ISLAND_X = 664;

    private static final int ISLAND_Y = 755;

    private String name;
    private String placementId;

    private final int attachX, attachY;
    private final int destX, destY;
    private final List<Point> entries;
    private final int cost;

    /**
     * The swing in {@code env}, or none when the world does not place the tree. It is fired from the
     * tiles beside the tree that are not on the island; from all of them when the walking components
     * cannot tell the sides apart.
     */
    public static List<Transport> forWorld(ServerData env, String id) {
        CollisionMap map = env.collisionMap();
        Point anchor = null;
        Set<Point> raw = new LinkedHashSet<>();
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() != ROPE_ATTACH_ID) {
                continue;
            }
            if (anchor == null) {
                anchor = Point.of(loc.pos().x(), loc.pos().y());
            }
            ObjectDef def = env.objectDefs().get(ROPE_ATTACH_ID);
            raw.addAll(
                    def == null
                            ? Transport.enumerateEntries(
                                    map, loc.pos().x(), loc.pos().y())
                            : Transport.entriesFor(
                                    map, loc.pos().x(), loc.pos().y(), def.width(), def.height(), loc.direction()));
        }
        if (anchor == null) {
            return List.of();
        }
        int island = map.componentId(ISLAND_X, ISLAND_Y);
        List<Point> entries = new ArrayList<>();
        for (Point e : raw) {
            int c = map.componentId(e.x(), e.y());
            if (c != 0 && c != island) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            entries.addAll(raw);
        }
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(new GrewIslandRopeSwingTransport(
                        anchor.x(), anchor.y(), ISLAND_X, ISLAND_Y, entries, AgilityObstacles.COST)
                .withPlacement(id));
    }

    GrewIslandRopeSwingTransport(int attachX, int attachY, int destX, int destY, List<Point> entries, int cost) {
        this.attachX = attachX;
        this.attachY = attachY;
        this.destX = destX;
        this.destY = destY;
        this.entries = List.copyOf(entries);
        this.cost = cost;
    }

    @Override
    public Prereq prereq() {
        return Prereq.all(
                Prereq.fact(Fact.skillAtLeast(Skill.AGILITY, MIN_AGILITY)),
                Prereq.fact(Fact.carriedAtLeast(ROPE_ITEM_ID, 1)));
    }

    @Override
    public boolean isEntry(int x, int y) {
        for (Point e : entries) {
            if (e.x() == x && e.y() == y) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return entries;
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return List.of(new Consumed(ItemId.getById(ROPE_ITEM_ID), 1));
    }

    @Override
    public int cost() {
        return cost;
    }

    /** The tile the tree, and the tied rope after it, stands on. */
    public int attachX() {
        return attachX;
    }

    public int attachY() {
        return attachY;
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    public GrewIslandRopeSwingTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }
}
