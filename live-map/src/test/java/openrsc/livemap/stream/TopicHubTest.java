package openrsc.livemap.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TopicHubTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private record Row(String name, int n) {}

    private TopicHub hub;

    @BeforeEach
    void start() {
        hub = new TopicHub(MAPPER);
        hub.start();
    }

    @AfterEach
    void stop() {
        hub.stop();
    }

    @Test
    void aSubscriberGetsEveryRecordThenOnlyWhatChanged() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1, "b", 2, "c", 3));
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> rows.get());
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 7, "t", Map.of());

        JsonNode full = v.next();
        assertEquals(7, full.get("id").intValue());
        assertEquals(
                json(
                        "[[\"a\",{\"name\":\"a\",\"n\":1}],[\"b\",{\"name\":\"b\",\"n\":2}],[\"c\",{\"name\":\"c\",\"n\":3}]]"),
                full.get("f"));

        rows.set(rows("a", 1, "b", 20, "c", 3));
        JsonNode delta = v.next();
        assertEquals(json("[[\"b\",{\"name\":\"b\",\"n\":20}]]"), delta.get("u"));
        assertNull(delta.get("r"));
        assertNull(delta.get("o"), "an unchanged order is not resent");

        rows.set(rows("a", 1, "c", 3, "d", 4));
        delta = v.next();
        assertEquals(json("[[\"d\",{\"name\":\"d\",\"n\":4}]]"), delta.get("u"));
        assertEquals(json("[\"b\"]"), delta.get("r"));
        assertNull(delta.get("o"), "a key appended at the end is where the viewer puts it anyway");

        assertNull(v.poll(), "nothing changed, nothing sent");
    }

    @Test
    void aTouchReadsTheSubscriptionsItPicksAgainAtOnce() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1));
        hub.polled("t", TopicHub.Access.ANYONE, 3_600_000, List.of("user"), q -> rows.get());
        Viewer alice = new Viewer(false);
        Viewer bob = new Viewer(false);
        hub.subscribe(alice.client, 1, "t", Map.of("user", "alice"));
        hub.subscribe(bob.client, 1, "t", Map.of("user", "bob"));
        alice.next();
        bob.next();

        rows.set(rows("a", 2));
        hub.touch("t", q -> "alice".equals(q.arg("user")));
        assertEquals(json("[[\"a\",{\"name\":\"a\",\"n\":2}]]"), alice.next().get("u"));
        assertNull(bob.poll(), "a subscription the touch did not pick waits for its own read");
    }

    @Test
    void aReorderIsSentAsTheFullKeyOrder() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1, "b", 2));
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> rows.get());
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 1, "t", Map.of());
        v.next();

        rows.set(rows("c", 3, "a", 1, "b", 2));
        JsonNode delta = v.next();
        assertEquals(json("[[\"c\",{\"name\":\"c\",\"n\":3}]]"), delta.get("u"));
        assertEquals(json("[\"c\",\"a\",\"b\"]"), delta.get("o"));
    }

    @Test
    void aKeyAppendedToOneListOfALayoutSendsNoOrder() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("solves[]/1", 1, "goals[]/a", 1));
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> rows.get());
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 1, "t", Map.of());
        v.next();

        rows.set(rows("solves[]/1", 1, "solves[]/2", 2, "goals[]/a", 1));
        JsonNode delta = v.next();
        assertNull(delta.get("o"), "the new solve follows the old ones, which is where the viewer puts it");

        rows.set(rows("solves[]/2", 2, "solves[]/1", 1, "goals[]/a", 1));
        assertEquals(
                json("[\"solves[]/2\",\"solves[]/1\",\"goals[]/a\"]"),
                v.next().get("o"),
                "a reorder within a list is sent");
    }

    @Test
    void aFailedReadReportsTheReasonAndARecoveryClearsIt() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1));
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> {
            Map<String, Row> r = rows.get();
            if (r == null) {
                throw new TopicException(503, "busy");
            }
            return r;
        });
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 1, "t", Map.of());
        v.next();

        rows.set(null);
        JsonNode err = v.next();
        assertEquals(503, err.get("err").intValue());
        assertEquals("busy", err.get("msg").stringValue());
        assertNull(v.poll(), "the same failure is reported once");

        rows.set(rows("a", 1));
        JsonNode back = v.next();
        assertTrue(back.get("u").isEmpty(), () -> "a recovery with nothing changed still clears the error: " + back);
    }

    @Test
    void anAuthedTopicRefusesAnAnonymousViewer() throws Exception {
        hub.polled("secret", TopicHub.Access.AUTHED, 50, q -> rows("a", 1));
        Viewer anon = new Viewer(false);
        hub.subscribe(anon.client, 1, "secret", Map.of());
        assertEquals(401, anon.next().get("err").intValue());

        Viewer unknown = new Viewer(true);
        hub.subscribe(unknown.client, 2, "nope", Map.of());
        assertEquals(404, unknown.next().get("err").intValue());
    }

    @Test
    void argumentsAndAuthReachTheSource() throws Exception {
        hub.polled(
                "echo",
                TopicHub.Access.ANYONE,
                50,
                List.of("who"),
                q -> Map.of("k", new Row(q.require("who"), q.authed() ? 1 : 0)));
        Viewer v = new Viewer(true);
        hub.subscribe(v.client, 1, "echo", Map.of("who", "zed"));
        assertEquals(json("[[\"k\",{\"name\":\"zed\",\"n\":1}]]"), v.next().get("f"));

        Viewer missing = new Viewer(true);
        hub.subscribe(missing.client, 1, "echo", Map.of());
        assertEquals(400, missing.next().get("err").intValue());

        Viewer junk = new Viewer(false);
        hub.subscribe(junk.client, 1, "echo", Map.of("who", "zed", "x", "1"));
        JsonNode refused = junk.next();
        assertEquals(400, refused.get("err").intValue(), "an argument the topic does not declare is refused");
        assertEquals("echo takes no argument x", refused.get("msg").stringValue());
    }

    @Test
    void aLiveTopicIsReadOnceAndThenFedByItsStore() throws Exception {
        AtomicReference<Integer> reads = new AtomicReference<>(0);
        TopicHub.Feed feed = hub.live(
                "live",
                TopicHub.Access.ANYONE,
                List.of(),
                q -> {
                    reads.set(reads.get() + 1);
                    return rows("a", 1, "b", 2);
                },
                (q, key) -> !key.startsWith("hidden"));
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 3, "live", Map.of());
        assertEquals(2, v.next().get("f").size());

        feed.put("a", new Row("a", 10));
        feed.put("hidden-x", new Row("x", 1));
        feed.remove("b");
        feed.put("c", new Row("c", 3));
        JsonNode delta = v.next();
        assertEquals(json("[[\"a\",{\"name\":\"a\",\"n\":10}],[\"c\",{\"name\":\"c\",\"n\":3}]]"), delta.get("u"));
        assertEquals(json("[\"b\"]"), delta.get("r"));

        feed.put("a", new Row("a", 10));
        assertNull(v.poll(), "a write of the same record sends nothing");
        assertEquals(1, reads.get(), "a live topic is not read again");
    }

    @Test
    void aLaterSubscriberSharesTheReadAndStartsFromTheCurrentRecords() throws Exception {
        AtomicReference<Integer> reads = new AtomicReference<>(0);
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1));
        hub.polled("t", TopicHub.Access.ANYONE, 10_000, q -> {
            reads.set(reads.get() + 1);
            return rows.get();
        });
        Viewer first = new Viewer(false);
        hub.subscribe(first.client, 1, "t", Map.of());
        first.next();

        Viewer second = new Viewer(false);
        hub.subscribe(second.client, 9, "t", Map.of());
        JsonNode full = second.next();
        assertEquals(9, full.get("id").intValue());
        assertEquals(1, full.get("f").size());
        assertEquals(1, reads.get(), "the second viewer is served from the first one's read");

        rows.set(rows("a", 2));
        hub.touchAll();
        assertNotNull(first.next());
        assertNotNull(second.next());
    }

    @Test
    void anUnsubscribedViewerHearsNothingMore() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1));
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> rows.get());
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 1, "t", Map.of());
        v.next();
        hub.unsubscribe(v.client, 1);
        rows.set(rows("a", 2));
        assertNull(v.poll());
    }

    @Test
    void aViewerThatStopsReadingIsDroppedWithoutHoldingUpTheOthers() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 0));
        hub.polled("t", TopicHub.Access.ANYONE, 10, q -> rows.get());
        CountDownLatch never = new CountDownLatch(1);
        Viewer stuck = new Viewer(false, never);
        Viewer healthy = new Viewer(false);
        hub.subscribe(stuck.client, 1, "t", Map.of());
        hub.subscribe(healthy.client, 1, "t", Map.of());
        healthy.next();

        String big = "x".repeat(64 * 1024);
        long start = System.nanoTime();
        for (int i = 0; i < 2000 && stuck.client.isOpen(); i++) {
            stuck.client.offer(new TextMessage(big));
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "offering to a stuck viewer never waits");
        assertTrue(stuck.closed.await(5, TimeUnit.SECONDS), "the stuck viewer's socket is closed");

        rows.set(rows("a", 1));
        assertEquals(json("[[\"a\",{\"name\":\"a\",\"n\":1}]]"), healthy.next().get("u"));
        never.countDown();
    }

    @Test
    void aViewerHoldsAtMostThirtyTwoSubscriptions() throws Exception {
        hub.polled("echo", TopicHub.Access.ANYONE, 10_000, List.of("n"), q -> Map.of("k", new Row(q.require("n"), 0)));
        Viewer v = new Viewer(true);
        for (int id = 1; id <= 32; id++) {
            hub.subscribe(v.client, id, "echo", Map.of("n", "" + id));
            assertNotNull(v.next().get("f"));
        }
        hub.subscribe(v.client, 33, "echo", Map.of("n", "33"));
        JsonNode refused = v.next();
        assertEquals(33, refused.get("id").intValue());
        assertEquals(429, refused.get("err").intValue());

        hub.unsubscribe(v.client, 1);
        hub.subscribe(v.client, 33, "echo", Map.of("n", "33"));
        assertNotNull(v.next().get("f"), "a released subscription frees its slot");
    }

    @Test
    void oneAddressHoldsAtMostSixtyFourAnonymousSubscriptions() throws Exception {
        hub.polled("echo", TopicHub.Access.ANYONE, 10_000, List.of("n"), q -> Map.of("k", new Row(q.require("n"), 0)));
        Viewer first = new Viewer(false, null, "1.2.3.4");
        Viewer second = new Viewer(false, null, "1.2.3.4");
        for (int id = 1; id <= 32; id++) {
            hub.subscribe(first.client, id, "echo", Map.of("n", "a" + id));
            hub.subscribe(second.client, id, "echo", Map.of("n", "b" + id));
            first.next();
            second.next();
        }
        Viewer third = new Viewer(false, null, "1.2.3.4");
        hub.subscribe(third.client, 1, "echo", Map.of("n", "c"));
        assertEquals(429, third.next().get("err").intValue());

        Viewer elsewhere = new Viewer(false, null, "5.6.7.8");
        hub.subscribe(elsewhere.client, 1, "echo", Map.of("n", "c"));
        assertNotNull(elsewhere.next().get("f"), "another address is not held to the first one's count");
        Viewer authed = new Viewer(true, null, "1.2.3.4");
        hub.subscribe(authed.client, 1, "echo", Map.of("n", "c"));
        assertNotNull(authed.next().get("f"), "a logged-in viewer is not counted against its address");
    }

    @Test
    void anAnonymousViewerSubscribesAtAPace() throws Exception {
        hub.polled("echo", TopicHub.Access.ANYONE, 10_000, List.of("n"), q -> Map.of("k", new Row(q.require("n"), 0)));
        Viewer v = new Viewer(false);
        for (int i = 1; i <= 32; i++) {
            hub.subscribe(v.client, 1, "echo", Map.of("n", "" + i));
        }
        hub.subscribe(v.client, 1, "echo", Map.of("n", "33"));
        JsonNode refused = v.nextWhere(d -> d.has("err"));
        assertEquals(429, refused.get("err").intValue());
        assertEquals("subscribing too fast", refused.get("msg").stringValue());

        Thread.sleep(600);
        hub.subscribe(v.client, 2, "echo", Map.of("n", "34"));
        assertNotNull(v.nextWhere(d -> d.get("id").intValue() == 2).get("f"), "the burst refills");
    }

    @Test
    void anonymousReadsLeaveASlotForLoggedInViewers() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        hub.polled("slow", TopicHub.Access.ANYONE, 10_000, List.of("n"), q -> {
            if (!q.authed()) {
                most.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            }
            return Map.of("k", new Row(q.require("n"), 0));
        });
        Viewer anon = new Viewer(false);
        for (int id = 1; id <= 6; id++) {
            hub.subscribe(anon.client, id, "slow", Map.of("n", "" + id));
        }
        Thread.sleep(500);
        assertEquals(3, most.get(), "anonymous viewers never hold every read slot");
        Viewer authed = new Viewer(true);
        hub.subscribe(authed.client, 1, "slow", Map.of("n", "1"));
        assertNotNull(authed.next().get("f"), "a logged-in viewer is read while anonymous reads are stuck");
        go.countDown();
    }

    @Test
    void aSourceThatThrowsAnErrorIsReadAgain() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> {
            if (reads.incrementAndGet() == 1) {
                throw new StackOverflowError();
            }
            return rows("a", 1);
        });
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 1, "t", Map.of());
        assertEquals(500, v.next().get("err").intValue());
        assertNotNull(v.next().get("f"), "the state is not left reading");
    }

    @Test
    void atMostFourReadsRunAtOnce() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        hub.polled("slow", TopicHub.Access.ANYONE, 10_000, List.of("n"), q -> {
            most.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.decrementAndGet();
            }
            return Map.of("k", new Row(q.require("n"), 0));
        });
        Viewer v = new Viewer(true);
        for (int id = 1; id <= 12; id++) {
            hub.subscribe(v.client, id, "slow", Map.of("n", "" + id));
        }
        Thread.sleep(500);
        assertEquals(4, most.get(), "twelve topics due at once are read four at a time");
        go.countDown();
        for (int id = 1; id <= 12; id++) {
            assertNotNull(v.next().get("f"), "every topic is read in the end");
        }
    }

    @Test
    void aFrameTooLargeToSendIsRefusedWithoutDroppingTheViewer() throws Exception {
        AtomicReference<Map<String, Row>> rows = new AtomicReference<>(rows("a", 1));
        hub.polled("t", TopicHub.Access.ANYONE, 50, q -> rows.get());
        Viewer v = new Viewer(false);
        hub.subscribe(v.client, 1, "t", Map.of());
        v.next();

        String huge = "x".repeat(9 << 20);
        rows.set(Map.of("a", new Row(huge, 1)));
        JsonNode refused = v.next();
        assertEquals(413, refused.get("err").intValue());
        assertTrue(v.client.isOpen(), "the viewer keeps its socket and its other topics");

        rows.set(rows("a", 2));
        JsonNode back = v.next();
        assertEquals(
                json("[[\"a\",{\"name\":\"a\",\"n\":2}]]"), back.get("f"), "the next read that fits is sent whole");
    }

    @Test
    void aSeedGoesOutAheadOfTheFramesHeldBackWhileItWasBuilt() throws Exception {
        Viewer v = new Viewer(false);
        v.client.hold();
        v.client.offer(new TextMessage("later"));
        assertNull(v.sent.poll(200, TimeUnit.MILLISECONDS), "a held frame waits");
        v.client.release(List.of(new TextMessage("seed")));
        assertEquals("seed", v.sent.poll(5, TimeUnit.SECONDS));
        assertEquals("later", v.sent.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void aHistoryWindowIsOneTheUiOffers() {
        assertEquals(3_600_000L, new TopicQuery(Map.of("lastMs", "3600000"), false).historyWindow());
        assertEquals(604_800_000L, new TopicQuery(Map.of(), false).historyWindow(), "a week when it is missing");
        TopicException e = assertThrows(
                TopicException.class, () -> new TopicQuery(Map.of("lastMs", "3600001"), false).historyWindow());
        assertEquals(400, e.status());
    }

    private static Map<String, Row> rows(Object... kv) {
        Map<String, Row> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], new Row((String) kv[i], (Integer) kv[i + 1]));
        }
        return out;
    }

    private static JsonNode json(String s) {
        return MAPPER.readTree(s);
    }
}
