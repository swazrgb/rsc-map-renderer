package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Rows.carrying;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * Scot Ruth's tunnel to the chaos altar for a player who has not yet bought its use, played by a
 * hand-written script: pay Scot Ruth {@link #PRICE} coins, then enter the tunnel. The tunnel lets
 * through only a player whose cache holds {@link #KEY}, which the purchase sets for good; a player
 * who holds it takes the plain tunnel row of {@link SceneryCrossings} instead, so the two gates
 * exclude each other.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/npcs/ScotRuth.java#L33">ScotRuth.onTalkNpc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L131">RandomObjects.onOpLoc</a>
 */
public final class ScotRuthTunnelTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.SCOT_RUTH_TUNNEL);

    public static final int TUNNEL_ID = 1241;

    public static final String KEY = "scotruth_to_chaos_altar";

    public static final int PRICE = 200_000;

    public static final int SCOT_RUTH_NPC_ID = NpcId.SCOTRUTH.id();

    public static final int LAND_X = 331;

    public static final int LAND_Y = 213;

    private static final int COST = 350;

    private static final Prereq GATE =
            Prereq.all(Prereq.notFact(Fact.cacheBool(KEY, true)), carrying(ItemId.COINS.id(), PRICE));

    private String name;
    private String placementId;

    private final int tunnelX, tunnelY;
    private final List<Point> entries;
    private final int cost;

    /** The crossing in {@code env}, fired from the tunnel's reach tiles; none when it is not placed. */
    public static List<Transport> forWorld(ServerData env, String id) {
        ObjectDef def = env.objectDefs().get(TUNNEL_ID);
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() != TUNNEL_ID) {
                continue;
            }
            List<Point> entries = new ArrayList<>(
                    def == null
                            ? Transport.enumerateEntries(
                                    env.collisionMap(), loc.pos().x(), loc.pos().y())
                            : Transport.entriesFor(
                                    env.collisionMap(),
                                    loc.pos().x(),
                                    loc.pos().y(),
                                    def.width(),
                                    def.height(),
                                    loc.direction()));
            if (entries.isEmpty()) {
                return List.of();
            }
            return List.of(new ScotRuthTunnelTransport(loc.pos().x(), loc.pos().y(), entries, COST).withPlacement(id));
        }
        return List.of();
    }

    ScotRuthTunnelTransport(int tunnelX, int tunnelY, List<Point> entries, int cost) {
        this.tunnelX = tunnelX;
        this.tunnelY = tunnelY;
        this.entries = List.copyOf(entries);
        this.cost = cost;
    }

    @Override
    public Prereq prereq() {
        return GATE;
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return List.of(new Consumed(ItemId.COINS, PRICE));
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
        return Point.of(LAND_X, LAND_Y);
    }

    @Override
    public int cost() {
        return cost;
    }

    /** The tunnel's anchor tile. */
    public int tunnelX() {
        return tunnelX;
    }

    public int tunnelY() {
        return tunnelY;
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

    public ScotRuthTunnelTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }
}
