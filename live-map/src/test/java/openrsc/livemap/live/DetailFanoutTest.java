package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.dto.PositionDto;
import openrsc.livemap.dto.WindowsDto;
import openrsc.livemap.stream.Viewer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class DetailFanoutTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final DetailFanout fanout = new DetailFanout(MAPPER, bot -> bot.startsWith("public"));

    @Test
    void aWatchedBotIsSentWholeThenEveryChangeIncludingEachTick() throws Exception {
        fanout.flush(List.of(bot("alice", 1, "walking\nstep 1")));
        Viewer v = new Viewer(true);

        fanout.interest(v.client, Set.of("alice"), Set.of());
        assertEquals(
                json("[{\"username\":\"alice\",\"serverTick\":1,\"description\":\"walking\\nstep 1\","
                        + "\"dialog\":null,\"fatiguePercent\":null,\"windows\":null}]"),
                v.next("bx"),
                "the bot's fields whole when the viewer starts watching");

        fanout.flush(List.of(bot("alice", 2, "walking\nstep 1")));
        assertEquals(json("[{\"username\":\"alice\",\"serverTick\":2}]"), v.next("bx"), "the tick alone is a change");
    }

    @Test
    void aListedBotIsSentOnlyItsFirstLineAndOnlyWhenThatChanges() throws Exception {
        Viewer v = new Viewer(true);
        fanout.interest(v.client, Set.of(), Set.of("alice"));

        fanout.flush(List.of(bot("alice", 1, "walking\nstep 1")));
        assertEquals(json("[{\"username\":\"alice\",\"doing\":\"walking\"}]"), v.next("bx"));

        fanout.flush(List.of(bot("alice", 2, "walking\nstep 2")));
        fanout.flush(List.of(bot("alice", 3, "banking\nstep 1")));
        assertEquals(
                json("[{\"username\":\"alice\",\"doing\":\"banking\"}]"),
                v.next("bx"),
                "a tick or a later line moving is nothing a listing shows");
    }

    @Test
    void anAnonymousViewerSeesNoDescriptionAndOnlyBotsItMaySee() throws Exception {
        Viewer v = new Viewer(false);
        fanout.interest(v.client, Set.of("publicBot", "private"), Set.of());

        fanout.flush(List.of(bot("publicBot", 1, "walking"), bot("private", 1, "walking")));
        assertEquals(json("[{\"username\":\"publicBot\",\"serverTick\":1}]"), v.next("bx"));
    }

    @Test
    void anOpenWindowGoesToAWatchingViewerAndItsClosingAsNull() throws Exception {
        Viewer v = new Viewer(true);
        fanout.interest(v.client, Set.of("alice"), Set.of());
        fanout.flush(List.of(bot(
                "alice",
                1,
                "banking",
                new WindowsDto(new WindowsDto.Bank(false, true, false, false, false, false, null), null, null))));
        assertEquals(
                json("[{\"username\":\"alice\",\"serverTick\":1,\"description\":\"banking\","
                        + "\"windows\":{\"bank\":{\"notes\":false,\"wornDeposits\":true,\"depositAll\":false,"
                        + "\"depositWorn\":false,\"unequip\":false,\"rearrange\":false}}}]"),
                v.next("bx"));

        fanout.flush(List.of(bot("alice", 2, "banking")));
        assertEquals(json("[{\"username\":\"alice\",\"serverTick\":2,\"windows\":null}]"), v.next("bx"));
    }

    @Test
    void aBotNobodyAsksForIsSentToNobody() throws Exception {
        Viewer v = new Viewer(true);
        fanout.interest(v.client, Set.of("alice"), Set.of());
        fanout.flush(List.of(bot("bob", 1, "walking")));
        assertNull(v.poll());
    }

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }

    private static BotLiveDto bot(String name, long tick, String description) {
        return bot(name, tick, description, null);
    }

    private static BotLiveDto bot(String name, long tick, String description, @Nullable WindowsDto windows) {
        return new BotLiveDto(
                name,
                "ONLINE",
                null,
                null,
                description,
                tick,
                new PositionDto(1, 1, 0),
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
                null,
                null,
                null,
                null,
                null,
                null,
                windows);
    }
}
