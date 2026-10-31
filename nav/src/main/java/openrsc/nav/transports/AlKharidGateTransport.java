package openrsc.nav.transports;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * One direction through the Al Kharid toll gate, by talking to the border guard on the entry side.
 * Whoever is at Prince Ali Rescue stage 3 or has completed it passes free and lands a tile further
 * on; anyone else answers the toll menu, pays 10 coins and is walked through. The server picks the
 * flow by quest stage, not by which of the four transports was planned, so a toll arm spends coins
 * only for a player the server will charge.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/BorderGuard.java#L20">BorderGuard.onTalkNpc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/BorderGuard.java#L47">BorderGuard.onTalkNpc</a>
 */
public final class AlKharidGateTransport implements Transport, DeclarativeTransport {

    private String placementId;

    public static final int NPC_ID_LUMBRIDGE = NpcId.BORDER_GUARD_LUMBRIDGE.id();
    public static final int NPC_ID_ALKHARID = NpcId.BORDER_GUARD_ALKHARID.id();
    private static final int COINS_ITEM_ID = ItemId.COINS.id();
    public static final int WEST_TILE_X = 91;
    public static final int EAST_TILE_X = 92;
    public static final int GATE_Y = 649;

    private final boolean free;
    private final boolean fromEast;

    private final EngageSpec spec;

    private AlKharidGateTransport(boolean free, boolean fromEast) {
        this.free = free;
        this.fromEast = fromEast;
        this.spec = EngageSpec.of(
                EngageSpec.Action.INTERACT_1,
                new EngageSpec.Target.Npcs(new int[0], new int[] {fromEast ? NPC_ID_LUMBRIDGE : NPC_ID_ALKHARID}),
                List.<String[]>of(new String[] {"yes"}));
    }

    /** The 10-coin toll, from the east tile ({@link #EAST_TILE_X}) to the west. */
    public static AlKharidGateTransport tollWestward() {
        return new AlKharidGateTransport(false, true);
    }

    public static AlKharidGateTransport tollEastward() {
        return new AlKharidGateTransport(false, false);
    }

    /** The free passage, from the east tile to the west. */
    public static AlKharidGateTransport freeWestward() {
        return new AlKharidGateTransport(true, true);
    }

    public static AlKharidGateTransport freeEastward() {
        return new AlKharidGateTransport(true, false);
    }

    @Override
    public Prereq prereq() {
        return free
                ? Prereq.fact(Fact.questAtLeast(QuestId.PRINCE_ALI_RESCUE.id(), 3))
                : Prereq.fact(Fact.carriedAtLeast(COINS_ITEM_ID, 10));
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == (fromEast ? EAST_TILE_X : WEST_TILE_X) && y == GATE_Y;
    }

    @Override
    public Point sceneryAnchor() {
        return Point.of(EAST_TILE_X, GATE_Y);
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(fromEast ? EAST_TILE_X : WEST_TILE_X, GATE_Y));
    }

    @Override
    public Point destinationFrom(Point from) {
        return fromEast
                ? Point.of(free ? WEST_TILE_X - 1 : WEST_TILE_X, GATE_Y)
                : Point.of(free ? EAST_TILE_X + 1 : EAST_TILE_X, GATE_Y);
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        if (free
                || account.quests().stage(QuestId.PRINCE_ALI_RESCUE) < 0
                || account.quests().stage(QuestId.PRINCE_ALI_RESCUE) >= 3) {
            return List.of();
        }
        return List.of(new Consumed(ItemId.getById(COINS_ITEM_ID), 10));
    }

    @Override
    public int cost() {
        return free ? 20 : 30;
    }

    private String name;

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    @Override
    public String arm() {
        return (free ? "free" : "toll") + (fromEast ? "West" : "East");
    }

    public AlKharidGateTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : "Al Kharid border";
    }

    @Override
    public EngageSpec engageSpec() {
        return spec;
    }
}
