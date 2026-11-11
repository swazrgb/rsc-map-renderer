package openrsc.livemap.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class StreamAuthScopeTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void anAuthedBroadcastReachesAuthedViewersOnly() throws Exception {
        StreamRegistry registry = new StreamRegistry(mapper);
        Viewer anon = new Viewer(false);
        Viewer authed = new Viewer(true);
        registry.register(anon.client);
        registry.register(authed.client);

        registry.broadcastAuthed("accounts", List.of(Map.of("name", "SomePlayer", "x", 220, "z", 450)));

        String frame = authed.sent.poll(5, TimeUnit.SECONDS);
        assertNotNull(frame, "the authed viewer is the audience for the frame");
        assertTrue(frame.contains("SomePlayer"), () -> "expected the name in " + frame);
        assertNull(anon.poll(), "an anonymous viewer is not sent an authed broadcast");
    }

    @Test
    void anAllAnonymousAudienceIsSkippedEntirely() throws Exception {
        StreamRegistry registry = new StreamRegistry(mapper);
        Viewer anon = new Viewer(false);
        registry.register(anon.client);

        registry.broadcastAuthed("accounts", List.of(Map.of("name", "SomePlayer")));

        assertNull(anon.poll());
    }

    @Test
    void ordinaryBroadcastsStillReachEveryone() throws Exception {
        StreamRegistry registry = new StreamRegistry(mapper);
        Viewer anon = new Viewer(false);
        Viewer authed = new Viewer(true);
        registry.register(anon.client);
        registry.register(authed.client);

        registry.broadcast("npcRespawns", List.of());

        assertTrue(anon.next("npcRespawns").isArray());
        assertTrue(authed.next("npcRespawns").isArray());
    }

    @Test
    void anEnvelopeCarriesItsEventAndData() throws Exception {
        StreamRegistry registry = new StreamRegistry(mapper);
        Viewer anon = new Viewer(false);
        registry.register(anon.client);

        anon.client.offer(registry.envelope("accounts", List.of()));

        JsonNode frame = mapper.readTree(anon.sent.poll(5, TimeUnit.SECONDS));
        assertEquals("accounts", frame.get("e").asString());
        assertTrue(
                frame.get("d").isArray() && frame.get("d").isEmpty(),
                () -> "expected an empty list, got " + frame.get("d"));
    }
}
