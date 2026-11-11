package openrsc.livemap.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.dto.PositionDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LiveFeedTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void aBotIsSentWholeOnceThenOnlyItsChangedFields() {
        Map<String, ObjectNode> sent = new HashMap<>();

        Map<String, Object> first = LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 10, 20, "walking", null)));
        assertEquals(1, ((List<?>) first.get("f")).size());
        assertNull(first.get("d"));

        Map<String, Object> second = LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 11, 20, "walking", "hi")));
        assertNull(second.get("f"));
        JsonNode d = MAPPER.valueToTree(second.get("d"));
        assertEquals(
                MAPPER.readTree(
                        "[{\"username\":\"alice\",\"position\":{\"x\":11,\"z\":20,\"floor\":0},\"msg\":\"hi\"}]"),
                d);

        Map<String, Object> third = LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 11, 20, "walking", null)));
        assertEquals(
                MAPPER.readTree("[{\"username\":\"alice\",\"msg\":null}]"),
                MAPPER.valueToTree(third.get("d")),
                "a field that is gone is sent as null");

        assertNull(
                LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 11, 20, "walking", null))), "no change, no frame");
    }

    @Test
    void theTickRidesAlongWithAChangeButIsNoChangeOfItsOwn() {
        Map<String, ObjectNode> sent = new HashMap<>();
        LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 1L, 10, 20, "x", null, null)));

        assertNull(
                LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 2L, 10, 20, "x", null, null))),
                "only the tick moved");

        assertEquals(
                MAPPER.readTree(
                        "[{\"username\":\"alice\",\"position\":{\"x\":11,\"z\":20,\"floor\":0},\"serverTick\":3}]"),
                reparsed(LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 3L, 11, 20, "x", null, null)))
                        .get("d")));
    }

    @Test
    void aRepeatedEventIsSentAgainOnItsOwnTick() {
        Map<String, ObjectNode> sent = new HashMap<>();
        LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 1L, 10, 20, "x", null, 4)));

        assertEquals(
                MAPPER.readTree("[{\"username\":\"alice\",\"dmg\":4,\"serverTick\":2}]"),
                reparsed(LiveFeed.botDelta(MAPPER, sent, List.of(bot("alice", 2L, 10, 20, "x", null, 4)))
                        .get("d")),
                "the same damage two ticks running is two hits");
    }

    /** As a viewer parses it: a {@code Long} field is no {@code LongNode} once on the wire. */
    private static JsonNode reparsed(Object frame) {
        return MAPPER.readTree(MAPPER.writeValueAsString(frame));
    }

    private static BotLiveDto bot(String name, int x, int z, String description, String msg) {
        return bot(name, 1L, x, z, description, msg, null);
    }

    private static BotLiveDto bot(String name, long tick, int x, int z, String description, String msg, Integer dmg) {
        return new BotLiveDto(
                name,
                "ONLINE",
                null,
                null,
                description,
                tick,
                new PositionDto(x, z, 0),
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
                dmg,
                null,
                msg,
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
