package openrsc.nav.transports;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.SceneryId;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.Rows;

/**
 * The Underground Pass east wall grill, played by a hand-written script: a rope tied to the grill
 * sets the player's {@link #CACHE_KEY}, and with it set a click climbs across. Finishing the quest
 * removes the key, but tying a rope sets it again at any stage and keeps the rope, from any tile but
 * {@link #REFUSED}. So the crossing is open to a player with the key or a rope.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap2.java#L35">UndergroundPassMechanismMap2.onUseLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L131">UndergroundPassObstaclesMap2.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/npcs/UndergroundPassIban.java#L95">UndergroundPassIban, the key removed</a>
 */
public final class UndergroundPassEastGrillTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.UNDERGROUND_PASS_EAST_GRILL);

    public static final String CACHE_KEY = "rope_wall_grill";

    public static final int GRILL_ID = SceneryId.WALL_GRILL_UNDERGROUND_PASS_EAST_SIDE.id();

    public static final Point GRILL = Point.of(762, 3463);

    /** The one tile the server refuses a tie from. */
    public static final Point REFUSED = Point.of(763, 3463);

    /** The other tile beside the grill on the near side. */
    public static final Point TIE_FROM = Point.of(763, 3462);

    public static final Point LANDING = Point.of(762, 3472);

    public static final int ROPE_ID = ItemId.ROPE.id();

    private static final Prereq CROSSES =
            Prereq.any(Rows.cache(CACHE_KEY), Prereq.fact(Fact.carriedAtLeast(ROPE_ID, 1)));

    private String name;
    private String placementId;
    private final List<Point> entries;

    /**
     * The crossing in {@code env}, or none when the world does not place the grill. It is fired from
     * the tiles beside the grill that walk to {@link #TIE_FROM}.
     */
    public static List<Transport> forWorld(ServerData env, String id) {
        Set<Point> beside = new LinkedHashSet<>();
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() != GRILL_ID || loc.pos().x() != GRILL.x() || loc.pos().y() != GRILL.y()) {
                continue;
            }
            ObjectDef def = env.objectDefs().get(GRILL_ID);
            beside.addAll(
                    def == null
                            ? Transport.enumerateEntries(env.collisionMap(), GRILL.x(), GRILL.y())
                            : Transport.entriesFor(
                                    env.collisionMap(),
                                    GRILL.x(),
                                    GRILL.y(),
                                    def.width(),
                                    def.height(),
                                    loc.direction()));
        }
        int nearSide = env.collisionMap().componentId(TIE_FROM.x(), TIE_FROM.y());
        List<Point> entries = beside.stream()
                .filter(p -> env.collisionMap().componentId(p.x(), p.y()) == nearSide)
                .toList();
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(new UndergroundPassEastGrillTransport(entries).withPlacement(id));
    }

    UndergroundPassEastGrillTransport(List<Point> entries) {
        this.entries = entries;
    }

    @Override
    public Prereq prereq() {
        return CROSSES;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return entries.contains(Point.of(x, y));
    }

    @Override
    public List<Point> entries() {
        return entries;
    }

    @Override
    public Point sceneryAnchor() {
        return GRILL;
    }

    @Override
    public Point destinationFrom(Point from) {
        return LANDING;
    }

    @Override
    public int cost() {
        return AgilityObstacles.COST;
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

    public UndergroundPassEastGrillTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }
}
