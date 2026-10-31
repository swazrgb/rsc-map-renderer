package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Rows.answers;
import static openrsc.nav.transports.spec.Rows.clickAt;
import static openrsc.nav.transports.spec.Rows.from;
import static openrsc.nav.transports.spec.Rows.pays;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.SceneryCrossing.sceneryMenu;

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

/**
 * The Shantay Pass stone gate into the desert, paid in coins alone and played by a hand-written
 * script: buy one desert pass from Shantay or his assistant, then click the gate from the north and
 * answer its poster, after which the guard takes the pass and moves the player through. The pass is
 * bought and spent inside the crossing. Southbound is {@link ShantayPassReturnTransport}.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L533">ShantayPassNpcs.onOpLoc</a>
 */
public final class ShantayPassTransport implements Transport, DeclarativeTransport {

    private String placementId;

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.SHANTAY_PASS);

    public static final int STONE_GATE_ID = SceneryId.STONE_GATE.id();
    public static final int SHANTAY_PASS_ITEM_ID = ItemId.SHANTAY_DESERT_PASS.id();
    public static final int COINS_ITEM_ID = ItemId.COINS.id();
    public static final int SHANTAY_NPC_ID = NpcId.SHANTAY.id();
    public static final int ASSISTANT_NPC_ID = NpcId.ASSISTANT.id();

    /**
     * The pass's price in coins with the shop at full stock: 120% of its base 5. The price rises as
     * the stock runs down.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L39">ShantayPassNpcs</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Shop.java#L220">Shop.calcItemBuyPrice</a>
     */
    public static final int PASS_BUY_COST = 6;

    public static final int GATE_X = 62;

    public static final int GATE_Y = 733;

    private static final int[] ENTRY_X = {GATE_X, GATE_X + 1};

    public static final int ENTRY_Y = GATE_Y - 1;

    public static final int DEST_X = 62;

    public static final int DEST_Y = 735;

    static final int BUY_COST = 540;

    private final EngageSpec gate;

    private ShantayPassTransport(EngageSpec gate) {
        this.gate = gate;
    }

    /** The crossing as declared by constant {@code id}. */
    public static List<Transport> forWorld(ServerData env, String id) {
        Transport row = sceneryMenu(
                        STONE_GATE_ID,
                        from(ENTRY_X[0], ENTRY_Y),
                        to(DEST_X, DEST_Y),
                        BUY_COST,
                        pays(SHANTAY_PASS_ITEM_ID, 1),
                        answers("yeah"),
                        clickAt(GATE_X, GATE_Y))
                .expand(env, id)
                .get(0);
        return List.of(new ShantayPassTransport(((DeclarativeTransport) row).engageSpec()).withPlacement(id));
    }

    /** The gate's part of the crossing, once the pass is carried: click the gate and answer the poster. */
    public EngageSpec gate() {
        return gate;
    }

    @Override
    public boolean isEntry(int x, int y) {
        if (y != ENTRY_Y) {
            return false;
        }
        for (int ex : ENTRY_X) {
            if (ex == x) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(ENTRY_X[0], ENTRY_Y), Point.of(ENTRY_X[1], ENTRY_Y));
    }

    @Override
    public Point sceneryAnchor() {
        return Point.of(GATE_X, GATE_Y);
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(DEST_X, DEST_Y);
    }

    @Override
    public Prereq prereq() {
        // The shop refuses a buy the pack has no room for before it takes the coins:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceShopHandler.java#L217
        return Prereq.all(
                Prereq.fact(Fact.carriedAtLeast(COINS_ITEM_ID, PASS_BUY_COST)),
                Prereq.fact(Fact.roomFor(SHANTAY_PASS_ITEM_ID, 1)));
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return List.of(new Consumed(ItemId.COINS, PASS_BUY_COST));
    }

    @Override
    public int cost() {
        return BUY_COST;
    }

    @Override
    public String arm() {
        return "buy";
    }

    @Override
    public EngageSpec engageSpec() {
        return ENGAGE;
    }

    public ShantayPassTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name();
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
}
