package openrsc.nav.transports;

import static openrsc.nav.transports.spec.Rows.carrying;
import static openrsc.nav.transports.spec.Rows.note;
import static openrsc.nav.transports.spec.Rows.onlyIn;
import static openrsc.nav.transports.spec.Rows.questComplete;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.Rows.when;
import static openrsc.nav.transports.spec.ScriptedClimb.anywhere;
import static openrsc.nav.transports.spec.WorldGate.feature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.SceneryId;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.spec.Rows.Option;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * The Runecraft portals, declared only on worlds with Runecraft switched on: each Mysterious Ruins
 * into its altar's room, each altar room's exit back beside its ruins, the chaos altar maze's fixed
 * portals and the rune stone mine's exit. Each handler matches its portal by id alone, so each row
 * is every placement of its id.
 *
 * <p>The maze's other portals land on a random tile of a fixed set, and the death and blood altar
 * exits on (0, 0), so they are not declared.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/ExitPortal.java#L82">ExitPortal.onOpLoc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/ExitPortal.java#L121">ExitPortal.onOpLoc</a>
 */
public enum Portals implements TransportDef {
    MYSTERIOUS_RUINS_AIR(ruins(
            SceneryId.MYSTERIOUS_RUINS_AIR,
            985,
            19,
            ItemId.AIR_TALISMAN,
            ItemId.CURSED_AIR_TALISMAN,
            ItemId.ENFEEBLED_AIR_TALISMAN)),
    MYSTERIOUS_RUINS_MIND(ruins(
            SceneryId.MYSTERIOUS_RUINS_MIND,
            934,
            14,
            ItemId.MIND_TALISMAN,
            ItemId.CURSED_MIND_TALISMAN,
            ItemId.ENFEEBLED_MIND_TALISMAN)),
    MYSTERIOUS_RUINS_WATER(ruins(
            SceneryId.MYSTERIOUS_RUINS_WATER,
            986,
            63,
            ItemId.WATER_TALISMAN,
            ItemId.CURSED_WATER_TALISMAN,
            ItemId.ENFEEBLED_WATER_TALISMAN)),
    MYSTERIOUS_RUINS_EARTH(ruins(
            SceneryId.MYSTERIOUS_RUINS_EARTH,
            934,
            70,
            ItemId.EARTH_TALISMAN,
            ItemId.CURSED_EARTH_TALISMAN,
            ItemId.ENFEEBLED_EARTH_TALISMAN)),
    MYSTERIOUS_RUINS_FIRE(ruins(
            SceneryId.MYSTERIOUS_RUINS_FIRE,
            887,
            26,
            ItemId.FIRE_TALISMAN,
            ItemId.CURSED_FIRE_TALISMAN,
            ItemId.ENFEEBLED_FIRE_TALISMAN)),
    MYSTERIOUS_RUINS_BODY(ruins(
            SceneryId.MYSTERIOUS_RUINS_BODY,
            893,
            71,
            ItemId.BODY_TALISMAN,
            ItemId.CURSED_BODY_TALISMAN,
            ItemId.ENFEEBLED_BODY_TALISMAN)),
    MYSTERIOUS_RUINS_COSMIC(ruins(
            SceneryId.MYSTERIOUS_RUINS_COSMIC,
            839,
            26,
            ItemId.COSMIC_TALISMAN,
            ItemId.CURSED_COSMIC_TALISMAN,
            ItemId.ENFEEBLED_COSMIC_TALISMAN)),
    MYSTERIOUS_RUINS_CHAOS(ruins(
            SceneryId.MYSTERIOUS_RUINS_CHAOS,
            826,
            90,
            ItemId.CHAOS_TALISMAN,
            ItemId.CURSED_CHAOS_TALISMAN,
            ItemId.ENFEEBLED_CHAOS_TALISMAN)),
    MYSTERIOUS_RUINS_NATURE(ruins(
            SceneryId.MYSTERIOUS_RUINS_NATURE,
            787,
            29,
            ItemId.NATURE_TALISMAN,
            ItemId.CURSED_NATURE_TALISMAN,
            ItemId.ENFEEBLED_NATURE_TALISMAN)),
    /**
     * Never passes: imbuing makes no law talisman.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/skills/runecraft/Runecraft.java#L83">Runecraft.talismanIds</a>
     */
    MYSTERIOUS_RUINS_LAW(ruins(
            SceneryId.MYSTERIOUS_RUINS_LAW,
            790,
            69,
            ItemId.LAW_TALISMAN,
            ItemId.CURSED_LAW_TALISMAN,
            ItemId.ENFEEBLED_LAW_TALISMAN,
            note("no law talisman can be made: imbuing has no law rune recipe"))),

    PORTAL_AIR_ALTAR(runecraft(anywhere(SceneryId.PORTAL_AIR_ALTAR.id(), to(305, 594)))),
    PORTAL_MIND_ALTAR(runecraft(anywhere(SceneryId.PORTAL_MIND_ALTAR.id(), to(298, 441)))),
    PORTAL_WATER_ALTAR(runecraft(anywhere(SceneryId.PORTAL_WATER_ALTAR.id(), to(148, 683)))),
    PORTAL_EARTH_ALTAR(runecraft(anywhere(SceneryId.PORTAL_EARTH_ALTAR.id(), to(63, 467)))),
    PORTAL_FIRE_ALTAR(runecraft(anywhere(SceneryId.PORTAL_FIRE_ALTAR.id(), to(53, 634)))),
    PORTAL_BODY_ALTAR(runecraft(anywhere(SceneryId.PORTAL_BODY_ALTAR.id(), to(260, 502)))),
    PORTAL_COSMIC_ALTAR(runecraft(anywhere(SceneryId.PORTAL_COSMIC_ALTAR.id(), to(104, 3566)))),
    PORTAL_CHAOS_ALTAR(runecraft(anywhere(SceneryId.PORTAL_CHAOS_ALTAR.id(), to(236, 376)))),
    PORTAL_NATURE_ALTAR(runecraft(anywhere(SceneryId.PORTAL_NATURE_ALTAR.id(), to(393, 803)))),
    PORTAL_LAW_ALTAR(runecraft(anywhere(SceneryId.PORTAL_LAW_ALTAR.id(), to(410, 537)))),

    PORTAL_CHAOS_MAZE1(runecraft(anywhere(SceneryId.PORTAL_CHAOS_MAZE1.id(), to(835, 71)))),
    PORTAL_CHAOS_MAZE2(runecraft(anywhere(SceneryId.PORTAL_CHAOS_MAZE2.id(), to(825, 76)))),
    PORTAL_CHAOS_MAZE3(runecraft(anywhere(SceneryId.PORTAL_CHAOS_MAZE3.id(), to(842, 55)))),
    PORTAL_CHAOS_MAZE4(runecraft(anywhere(SceneryId.PORTAL_CHAOS_MAZE4.id(), to(819, 51)))),
    PORTAL_CHAOS_MAZE5(runecraft(anywhere(SceneryId.PORTAL_CHAOS_MAZE5.id(), to(859, 51)))),

    /**
     * The mine's exit lands beside Aubury when {@link #ESSENCE_ENTRANCE} is 0 and in the Wizards'
     * Tower basement when it is 1. With the key unset the server lands beside Aubury, but both arms
     * here stay closed.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/ExitPortal.java#L89">ExitPortal.onOpLoc</a>
     */
    PORTAL_RAW_ESSENCE_MINE_VARROCK(runecraft(anywhere(
            SceneryId.PORTAL_RAW_ESSENCE_MINE.id(),
            to(101, 523),
            when(Prereq.all(
                    Prereq.fact(Fact.cacheIntAtLeast(Portals.ESSENCE_ENTRANCE, 0)),
                    Prereq.notFact(Fact.cacheIntAtLeast(Portals.ESSENCE_ENTRANCE, 1))))))),
    PORTAL_RAW_ESSENCE_MINE_WIZARDS_TOWER(runecraft(anywhere(
            SceneryId.PORTAL_RAW_ESSENCE_MINE.id(),
            to(222, 3517),
            when(Prereq.all(
                    Prereq.fact(Fact.cacheIntAtLeast(Portals.ESSENCE_ENTRANCE, 1)),
                    Prereq.notFact(Fact.cacheIntAtLeast(Portals.ESSENCE_ENTRANCE, 2))))))),
    ;

    /**
     * The server's cache key for which wizard teleported the player into the rune stone mine: 0
     * Aubury, 1 Sedridor. Each teleport overwrites it and the mine's exit removes it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/quests/members/RuneMysteries.java#L88">RuneMysteries.sedridorDialog</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/quests/members/RuneMysteries.java#L353">RuneMysteries.auburyDialog</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/ExitPortal.java#L95">ExitPortal.onOpLoc</a>
     */
    public static final String ESSENCE_ENTRANCE = "essence_entrance";

    /** The rune stone mine's bounds, in tiles, inclusive. */
    public static final int MINE_X_MIN = 672, MINE_X_MAX = 719, MINE_Z_MIN = 0, MINE_Z_MAX = 47;

    public static boolean inRuneStoneMine(int x, int z) {
        return x >= MINE_X_MIN && x <= MINE_X_MAX && z >= MINE_Z_MIN && z <= MINE_Z_MAX;
    }

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    Portals(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    private static TransportSpec runecraft(TransportSpec spec) {
        return onlyIn(feature(WorldProfile.Feature.RUNECRAFT), spec);
    }

    /**
     * Every placement of {@code ruins}, into its altar's room: the server takes any of the altar's
     * three talismans unnoted, only shown, and Rune Mysteries complete.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/skills/runecraft/Runecraft.java#L239">Runecraft.onOpLoc</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/skills/runecraft/Runecraft.java#L392">Runecraft.onUseLoc</a>
     */
    private static TransportSpec ruins(
            SceneryId ruins,
            int destX,
            int destZ,
            ItemId talisman,
            ItemId cursed,
            ItemId enfeebled,
            Option... options) {
        Option[] all = Arrays.copyOf(options, options.length + 1);
        all[options.length] = when(Prereq.all(
                Prereq.any(carrying(talisman.id(), 1), carrying(cursed.id(), 1), carrying(enfeebled.id(), 1)),
                questComplete(QuestId.RUNE_MYSTERIES)));
        return runecraft(anywhere(ruins.id(), to(destX, destZ), all));
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (Portals p : values()) {
            out.addAll(p.expand(env));
        }
        return out;
    }
}
