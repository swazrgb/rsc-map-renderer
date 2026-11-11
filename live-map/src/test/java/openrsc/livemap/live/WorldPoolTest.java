package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.dto.MapEntityDto;
import openrsc.livemap.dto.PositionDto;
import org.junit.jupiter.api.Test;

class WorldPoolTest {

    @Test
    void ourOwnBotsAreLeftOutOfThePlayerPool() {
        WorldPool pool = new WorldPool();
        BotLiveDto alice = bot("alice", 5, List.of(player(6, "Bob", 11, 20), player(7, "stranger", 12, 20)));
        BotLiveDto bob = bot("Bob", 6, List.of(player(5, "alice", 10, 20)));
        Map<String, Object> seed = pool.update(List.of(alice, bob));
        assertEquals(
                List.of(7),
                rows(seed, "players", "u").stream().map(r -> r.get(0)).toList(),
                "players that are online bots come from the bot stream, not the pool");
    }

    @Test
    void aMovedEntitySendsOnlyItsChangedColumns() {
        WorldPool pool = new WorldPool();
        pool.update(List.of(bot("alice", 5, List.of(player(7, "stranger", 12, 20)))));
        Map<String, Object> delta = pool.update(List.of(bot("alice", 5, List.of(player(7, "stranger", 13, 20)))));
        assertNull(((Map<?, ?>) delta.get("players")).get("u"));
        assertEquals(List.of(List.of(7, 2, 13)), rows(delta, "players", "p"), "[serverIndex, column, value]");
    }

    @Test
    void anEntitySeenByTwoBotsKeepsTheViewOfOne() {
        WorldPool pool = new WorldPool();
        BotLiveDto a = bot("a", 1, List.of(player(7, "stranger", 12, 20)));
        BotLiveDto b = bot("b", 2, List.of(player(7, "stranger", 13, 20)));
        pool.update(List.of(a, b));
        for (int i = 0; i < 4; i++) {
            List<BotLiveDto> order = i % 2 == 0 ? List.of(b, a) : List.of(a, b);
            assertNull(pool.update(order), "the entity does not flip between the two bots' views");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> rows(Map<String, Object> delta, String cls, String kind) {
        Map<String, Object> d = (Map<String, Object>) delta.get(cls);
        assertTrue(d != null && d.get(kind) != null, () -> cls + "." + kind + " in " + delta);
        return (List<List<Object>>) d.get(kind);
    }

    private static MapEntityDto player(int index, String name, int x, int z) {
        return new MapEntityDto(index, 0, name, x, z, false);
    }

    private static BotLiveDto bot(String name, int serverIndex, List<MapEntityDto> players) {
        return new BotLiveDto(
                name,
                "ONLINE",
                null,
                null,
                null,
                1L,
                new PositionDto(0, 0, 0),
                null,
                null,
                null,
                serverIndex,
                null,
                players,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
