package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Rows.to;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.data.SceneryId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.ScriptedClimb;

/**
 * The Paramaya hostel's ladder up to the dormitory, paid in coins alone and played by a hand-written
 * script: buy one rest ticket from Kaleb, the barman beside the ladder, then climb, and the barman
 * takes the ticket. The ticket is bought and spent inside the crossing.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L159">Ladders.onObjectAction</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/Kaleb.java#L52">Kaleb.onTalkNpc</a>
 */
public final class ParamayaDormitoryTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.PARAMAYA_DORMITORY);

    public static final int LADDER_ID = SceneryId.LADDER_PARAMAYA_DORMITORY_UP.id();

    public static final int TICKET_ITEM_ID = ItemId.PARAMAYA_REST_TICKET.id();

    public static final int KALEB_NPC_ID = NpcId.KALEB.id();

    /**
     * One night's ticket, in coins.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/shilo/Kaleb.java#L54">Kaleb.onTalkNpc</a>
     */
    public static final int TICKET_PRICE = 35;

    private static final int TALK_COST = 150;

    // Kaleb takes the coins before handing the ticket over, and a ticket with no slot for it falls
    // to the floor:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L155
    private static final Prereq GATE = Prereq.all(
            Prereq.fact(Fact.carriedAtLeast(ItemId.COINS.id(), TICKET_PRICE)),
            Prereq.fact(Fact.roomFor(TICKET_ITEM_ID, 1)));

    private final Transport ladder;
    private String name;
    private String placementId;

    private ParamayaDormitoryTransport(Transport ladder) {
        this.ladder = ladder;
    }

    /** The crossing at every placement of the ladder in {@code env}, declared by constant {@code id}. */
    public static List<Transport> forWorld(ServerData env, String id) {
        List<Transport> out = new ArrayList<>();
        for (Transport climb : ScriptedClimb.anywhere(LADDER_ID, to(395, 2713)).expand(env, id)) {
            out.add(new ParamayaDormitoryTransport(climb).withPlacement(id));
        }
        return out;
    }

    /** The ladder's part of the crossing, once the ticket is carried. */
    public EngageSpec climb() {
        return ((DeclarativeTransport) ladder).engageSpec();
    }

    @Override
    public boolean isEntry(int x, int y) {
        return ladder.isEntry(x, y);
    }

    @Override
    public List<Point> entries() {
        return ladder.entries();
    }

    @Override
    public Point sceneryAnchor() {
        return ladder.sceneryAnchor();
    }

    @Override
    public Point destinationFrom(Point from) {
        return ladder.destinationFrom(from);
    }

    @Override
    public Prereq prereq() {
        return GATE;
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return List.of(new Consumed(ItemId.COINS, TICKET_PRICE));
    }

    @Override
    public int cost() {
        return TALK_COST + ladder.cost();
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    public ParamayaDormitoryTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }
}
