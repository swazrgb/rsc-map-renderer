package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Rows.cache;
import static openrsc.nav.transports.spec.Rows.carrying;
import static openrsc.nav.transports.spec.Rows.notQuestAtLeast;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.survives;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * Down the rope from the Legends' Quest beam into the Viyeldi caves, played by a hand-written script:
 * searching the wooden beam ({@link #BEAM_ID}) shows the tied rope ({@link #ROPED_BEAM_ID}) for 8
 * ticks once {@link #BEAM_KEY} is set, and the roped beam's menu goes down. During the quest the
 * descent needs Gujuo's potion and is refused from stage 9. After it, on a world that keeps
 * post-quest regions open, a rope used on the beam ties it, spending the rope, and sets the key. One
 * descent in five falls for 10 to 15 damage and still lands.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L259">LegendsQuestGameObjects.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L211">LegendsQuestGameObjects.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L243">LegendsQuestGameObjects.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestGameObjects.java#L781">LegendsQuestGameObjects.blockUseLoc</a>
 */
public final class ViyeldiRopeDownTransport implements Transport, DeclarativeTransport {

    public static final EngageSpec ENGAGE = EngageSpec.bespoke(EngageSpec.BespokeScript.VIYELDI_ROPE_DOWN);

    public static final int BEAM_ID = 1156;
    public static final int ROPED_BEAM_ID = 1157;

    public static final int LAND_X = 426;
    public static final int LAND_Y = 3707;

    public static final String BEAM_KEY = "legends_wooden_beam";

    public static final int ROPE_ITEM_ID = ItemId.ROPE.id();

    private static final int FALL_DAMAGE = 15;

    private String name;
    private String placementId;

    private final int beamX, beamY;
    private final List<Point> entries;
    private final int cost;

    private final boolean tiesRope;

    /** The quest's own descent, or none when the world does not place the beam. */
    public static List<Transport> forWorld(ServerData env, String id) {
        return beam(env, id, false);
    }

    /**
     * The descent after the quest, which ties a rope round the beam when none is tied, or none when
     * the world does not place the beam. Whether the world allows it is the declaring row's gate.
     */
    public static List<Transport> afterQuest(ServerData env, String id) {
        return beam(env, id, true);
    }

    private static List<Transport> beam(ServerData env, String id, boolean tiesRope) {
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() != BEAM_ID) {
                continue;
            }
            List<Point> entries = new ArrayList<>(Transport.enumerateEntries(
                    env.collisionMap(), loc.pos().x(), loc.pos().y()));
            if (entries.isEmpty()) {
                return List.of();
            }
            return List.of(
                    new ViyeldiRopeDownTransport(loc.pos().x(), loc.pos().y(), entries, AgilityObstacles.COST, tiesRope)
                            .withPlacement(id));
        }
        return List.of();
    }

    ViyeldiRopeDownTransport(int beamX, int beamY, List<Point> entries, int cost, boolean tiesRope) {
        this.beamX = beamX;
        this.beamY = beamY;
        this.entries = List.copyOf(entries);
        this.cost = cost;
        this.tiesRope = tiesRope;
    }

    @Override
    public Prereq prereq() {
        if (tiesRope) {
            return Prereq.all(
                    questComplete(QuestId.LEGENDS_QUEST),
                    Prereq.any(cache(BEAM_KEY), carrying(ROPE_ITEM_ID, 1)),
                    survives(FALL_DAMAGE));
        }
        return Prereq.all(cache(BEAM_KEY), cache("gujuo_potion"), notQuestAtLeast(QuestId.LEGENDS_QUEST, 9));
    }

    @Override
    public List<Consumed> consumes(AccountFacts account) {
        return tiesRope && !account.cacheValues().bool(BEAM_KEY, false)
                ? List.of(new Consumed(ItemId.ROPE, 1))
                : List.of();
    }

    public boolean tiesRope() {
        return tiesRope;
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

    /** The tile the beam, and the roped beam, stand on. */
    public int beamX() {
        return beamX;
    }

    public int beamY() {
        return beamY;
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

    public ViyeldiRopeDownTransport withPlacement(String id) {
        this.placementId = id;
        return this;
    }

    @Override
    public String costOverrideKey() {
        return placementId != null ? placementId : name;
    }
}
