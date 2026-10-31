package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.planner.InputsProbeBot;
import openrsc.nav.transports.spec.TransportDef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Gates that must admit exactly the accounts the server's handler lets through. */
class ServerGatesTest {

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void princeAliJailOpensWithTheKeyAtStageTwoOnly(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport in = from(SpecialDoor.DOOR_PRINCE_ALI_JAIL, env, Point.of(198, 640));
        for (int stage : new int[] {1, 2, 3, 4, -1}) {
            Account a = new Account().item(ItemId.BRONZE_KEY.id(), 1).quest(QuestId.PRINCE_ALI_RESCUE, stage);
            assertEquals(stage == 2, in.prereq().test(a.context(env)), "stage " + stage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void blackKnightGuardDoorTakesTheChainMailBodyOrTop(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport in = from(SpecialDoor.DOOR_BLACK_KNIGHT_GUARD, env, Point.of(270, 441));
        int helmet = ItemId.MEDIUM_BRONZE_HELMET.id();
        for (int mail : new int[] {ItemId.IRON_CHAIN_MAIL_BODY.id(), ItemId.IRON_CHAIN_MAIL_TOP.id()}) {
            assertTrue(
                    in.prereq().test(new Account().item(mail, 1).item(helmet, 1).context(env)), "mail " + mail);
            assertFalse(in.prereq().test(new Account().item(mail, 1).context(env)), "mail " + mail + ", no helmet");
        }
        assertFalse(in.prereq().test(new Account().item(helmet, 1).context(env)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void omartCrossesTheWallAtStagesThreeToFive(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport ladder = from(NpcRoutes.OMART_ROPE_LADDER_EAST_WEST_ARDOUGNE, env, Point.of(624, 616));
        for (int stage : new int[] {2, 3, 4, 5, 6, -1}) {
            boolean crosses = stage >= 3 && stage <= 5;
            assertEquals(
                    crosses,
                    ladder.prereq().test(account(QuestId.BIOHAZARD, stage).context(env)),
                    "stage " + stage);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void cavernousOpeningAdmitsTheCrystalOrAFinishedQuest(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport in = from(AgilityObstacles.KHARAZI_CAVERNOUS_OPENING, env, Point.of(395, 3725));
        String key = "cavernous_opening";
        assertTrue(in.prereq()
                .test(account(QuestId.LEGENDS_QUEST, 8).cache(key, true).context(env)));
        assertTrue(in.prereq()
                .test(account(QuestId.LEGENDS_QUEST, -1).cache(key, false).context(env)));
        assertFalse(in.prereq()
                .test(account(QuestId.LEGENDS_QUEST, 8).cache(key, false).context(env)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void femiIsPaidAThousandCoins(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport cart = from(NpcRoutes.FEMIS_SMUGGLE_CART_INTO_THE_GNOME_STRONGHOLD_STAGE_10, env, Point.of(703, 532));
        RouteContext paid = account(QuestId.GRAND_TREE, 10)
                .item(ItemId.COINS.id(), 1000)
                .cache("helped_femi", false)
                .context(env);
        assertTrue(cart.prereq().test(paid));
        assertEquals(List.of(new Transport.Consumed(ItemId.COINS, 1000)), cart.consumes(paid.account()));
        assertFalse(cart.prereq()
                .test(account(QuestId.GRAND_TREE, 10)
                        .item(ItemId.COINS.id(), 999)
                        .context(env)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void ogreChasmJumpPaysTheTwentyCoinToll(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport jump = from(AgilityObstacles.WATCHTOWER_OGRE_CHASM_JUMP_OVER_TOLL, env, Point.of(646, 802));
        RouteContext paid = new Account()
                .level(Skill.AGILITY, 30)
                .item(ItemId.COINS.id(), 20)
                .context(env);
        assertTrue(jump.prereq().test(paid));
        assertEquals(List.of(new Transport.Consumed(ItemId.COINS, 20)), jump.consumes(paid.account()));
        assertFalse(jump.prereq()
                .test(new Account()
                        .level(Skill.AGILITY, 30)
                        .item(ItemId.COINS.id(), 19)
                        .context(env)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void questTeleportsNeedTheirScrollRead(String world) {
        ServerData env = RealDefs.serverData(world);
        NavPolicy spells = new NavPolicy(NavPolicy.DangerAvoidance.ON, true, false, Map.of(), false);
        record Case(TeleportSpellRoutes spell, QuestId quest, String key, int earthOrWater) {}
        for (Case c : List.of(
                new Case(
                        TeleportSpellRoutes.ARDOUGNE_TELEPORT,
                        QuestId.PLAGUE_CITY,
                        "ardougne_scroll",
                        ItemId.WATER_RUNE.id()),
                new Case(
                        TeleportSpellRoutes.WATCHTOWER_TELEPORT,
                        QuestId.WATCHTOWER,
                        "watchtower_scroll",
                        ItemId.EARTH_RUNE.id()))) {
            Transport cast = c.spell().expand(env).getFirst();
            Account caster = account(c.quest(), -1)
                    .level(Skill.MAGIC, 99)
                    .item(ItemId.LAW_RUNE.id(), 2)
                    .item(c.earthOrWater(), 2);
            assertTrue(cast.prereq().test(caster.cache(c.key(), true).context(env, spells)), c.key());
            assertFalse(cast.prereq().test(caster.cache(c.key(), false).context(env, spells)), c.key() + " unread");
            assertFalse(
                    cast.prereq()
                            .test(account(c.quest(), 5)
                                    .level(Skill.MAGIC, 99)
                                    .item(ItemId.LAW_RUNE.id(), 2)
                                    .item(c.earthOrWater(), 2)
                                    .context(env, spells)),
                    c.key() + " before the quest is done");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void blackArmDoorOpensForTheBlackArmGangOnly(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport in = from(SpecialDoor.DOOR_BLACKARM_HQ, env, Point.of(148, 532));
        for (int stage : new int[] {5, -1}) {
            assertTrue(in.prereq()
                    .test(account(QuestId.SHIELD_OF_ARRAV, stage)
                            .cache("arrav_gang", 0)
                            .context(env)));
            assertFalse(
                    in.prereq()
                            .test(account(QuestId.SHIELD_OF_ARRAV, stage)
                                    .cache("arrav_gang", 1)
                                    .context(env)),
                    "a Phoenix Gang member at stage " + stage);
        }
        assertFalse(in.prereq()
                .test(account(QuestId.SHIELD_OF_ARRAV, 4).cache("arrav_gang", 0).context(env)));
        assertTrue(
                in.prereq()
                        .test(account(QuestId.SHIELD_OF_ARRAV, -2)
                                .cache("arrav_gang", 1)
                                .context(env)),
                "the quest done as a Black Arm member names the gang whatever the cache says");
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void phoenixDoorOpensForThePhoenixGangOnly(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport in = from(SpecialDoor.DOOR_PHOENIX_HQ, env, Point.of(110, 3369));
        for (int stage : new int[] {5, -2}) {
            assertTrue(in.prereq()
                    .test(account(QuestId.SHIELD_OF_ARRAV, stage)
                            .cache("arrav_gang", 1)
                            .context(env)));
            assertFalse(
                    in.prereq()
                            .test(account(QuestId.SHIELD_OF_ARRAV, stage)
                                    .cache("arrav_gang", 0)
                                    .context(env)),
                    "a Black Arm member at stage " + stage);
        }
        assertFalse(in.prereq()
                .test(account(QuestId.SHIELD_OF_ARRAV, 4).cache("arrav_gang", 1).context(env)));
        assertTrue(
                in.prereq()
                        .test(account(QuestId.SHIELD_OF_ARRAV, -1)
                                .cache("arrav_gang", 0)
                                .context(env)),
                "the quest done as a Phoenix member names the gang whatever the cache says");
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void dormitoryLadderBuysItsTicketWithCoins(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport climb = from(LadderSpecials.PARAMAYA_DORMITORY_LADDER_UP, env, Point.of(395, 1771));
        assertEquals(Point.of(395, 2713), climb.destinationFrom(Point.of(395, 1771)));
        RouteContext paid = new Account().item(ItemId.COINS.id(), 35).context(env);
        assertTrue(climb.prereq().test(paid));
        assertEquals(List.of(new Transport.Consumed(ItemId.COINS, 35)), climb.consumes(paid.account()));
        assertFalse(
                climb.prereq().test(new Account().item(ItemId.COINS.id(), 34).context(env)));
        assertTrue(climb.eligibilityInputs().anyFactOfKind(Fact.Kind.ROOM_FOR), "the ticket needs a slot");
    }

    private static Account account(QuestId quest, int stage) {
        return new Account().quest(quest, stage);
    }

    /** The one transport of {@code def} fired from {@code entry}. */
    static Transport from(TransportDef def, ServerData env, Point entry) {
        List<Transport> hits = def.expand(env).stream()
                .filter(t -> t.isEntry(entry.x(), entry.y()))
                .toList();
        assertFalse(hits.isEmpty(), def + " from " + entry);
        assertEquals(1, hits.size(), def + " from " + entry + ": " + hits);
        return hits.getFirst();
    }

    static final class Account {
        private final EnumMap<Skill, Integer> skills = new EnumMap<>(Skill.class);
        private final HashMap<Integer, Integer> items = new HashMap<>();
        private final HashMap<Integer, Integer> quests = new HashMap<>();
        private final HashMap<String, Boolean> bools = new HashMap<>();
        private final HashMap<String, Integer> ints = new HashMap<>();

        Account level(Skill skill, int level) {
            skills.put(skill, level);
            return this;
        }

        Account item(int id, int count) {
            items.put(id, count);
            return this;
        }

        Account quest(QuestId q, int stage) {
            quests.put(q.id(), stage);
            return this;
        }

        Account cache(String key, boolean on) {
            bools.put(key, on);
            return this;
        }

        Account cache(String key, int value) {
            ints.put(key, value);
            return this;
        }

        RouteContext context(ServerData env) {
            return context(env, NavPolicy.DEFAULT);
        }

        RouteContext context(ServerData env, NavPolicy policy) {
            return new RouteContext(
                    new InputsProbeBot(env, skills, items, Set.of(), Map.of(), quests, new CacheValues(bools, ints)),
                    policy,
                    env);
        }
    }
}
