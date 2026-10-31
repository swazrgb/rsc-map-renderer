package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.planner.InputsProbeBot;
import openrsc.nav.transports.spec.TransportDef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransportGapsTest {
    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void branchesMatchTheServer(String world) {
        ServerData env = RealDefs.serverData(world);
        for (int stage : new int[] {0, 1, 2, -1}) {
            var context = context(env, Map.of(), Map.of(QuestId.TRIBAL_TOTEM.id(), stage), 52);
            assertEquals(
                    stage == 0 || stage == 1,
                    single(NpcRoutes.CROMPERTY_TELEPORT_BLOCK_RPDT_DEPOT, env)
                            .prereq()
                            .test(context));
            assertEquals(
                    stage == 2 || stage == -1,
                    single(NpcRoutes.CROMPERTY_TELEPORT_BLOCK_HANDELMORT_MANSION, env)
                            .prereq()
                            .test(context));
        }
        for (int light : new int[] {-1, ItemId.LIT_CANDLE.id(), ItemId.LIT_BLACK_CANDLE.id(), ItemId.LIT_TORCH.id()}) {
            var context = context(env, light == -1 ? Map.of() : Map.of(light, 1), Map.of(), 52);
            assertEquals(
                    light == -1,
                    single(LadderSpecials.TEMPLE_OF_IKOV_STAIRS_DOWN_UNLIT, env)
                            .prereq()
                            .test(context));
            assertEquals(
                    light != -1,
                    single(LadderSpecials.TEMPLE_OF_IKOV_STAIRS_DOWN, env)
                            .prereq()
                            .test(context));
        }
        for (TransportDef def : List.of(
                AgilityObstacles.WILDERNESS_AGILITY_COURSE_STEPPING_STONES,
                AgilityObstacles.WILDERNESS_AGILITY_COURSE_LEDGE)) {
            // Below 52 the course's roll always fails:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/WildernessAgilityCourse.java#L188
            Transport t = single(def, env);
            assertFalse(t.prereq().test(context(env, Map.of(), Map.of(), 51)));
            assertTrue(t.prereq().test(context(env, Map.of(), Map.of(), 52)));
        }
        assertEquals(
                Point.of(281, 665),
                single(NpcRoutes.SHANTAY_PORT_SARIM_PRISON, env).destinationFrom(Point.of(63, 731)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void diskWorksFromEveryRandomBlackHoleLandingAndSpendsOneDisk(String world) {
        ServerData env = RealDefs.serverData(world);
        Transport disk = single(ItemUses.DISK_OF_RETURNING, env);
        var carried = context(env, Map.of(ItemId.DISK_OF_RETURNING.id(), 1), Map.of(), 1);
        assertTrue(disk.prereq().test(carried));
        assertFalse(disk.prereq().test(context(env, Map.of(), Map.of(), 1)));
        assertEquals(List.of(new Transport.Consumed(ItemId.DISK_OF_RETURNING, 1)), disk.consumes(carried.account()));
        // The black hole ladder lands on a random tile of a 5x5 around (305,3300):
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L230
        assertEquals(25, disk.entries().size());
        for (int x = 303; x <= 307; x++) {
            for (int y = 3298; y <= 3302; y++) {
                assertTrue(disk.isEntry(x, y));
                assertEquals(Point.of(311, 3348), disk.destinationFrom(Point.of(x, y)));
            }
        }
        assertFalse(disk.isEntry(302, 3300));
        assertFalse(disk.isEntry(305, 3303));
        assertFalse(disk.isEntry(311, 3348));
        assertFalse(disk.entryFree());
    }

    private static Transport single(TransportDef def, ServerData env) {
        List<Transport> transports = def.expand(env);
        assertFalse(transports.isEmpty(), def.toString());
        return transports.getFirst();
    }

    private static RouteContext context(
            ServerData env, Map<Integer, Integer> items, Map<Integer, Integer> quests, int agility) {
        return new RouteContext(
                new InputsProbeBot(
                        env, Map.of(Skill.AGILITY, agility), items, Set.of(), Map.of(), quests, CacheValues.EMPTY),
                NavPolicy.DEFAULT,
                env);
    }
}
