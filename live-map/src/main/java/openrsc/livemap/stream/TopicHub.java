package openrsc.livemap.stream;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.ObjectMapper;

/**
 * Streams keyed records to stream subscribers: a viewer subscribes to a topic and its arguments, gets every
 * record once, then only the records that changed ({@code equals}) and the keys that went away. A polled topic is
 * read again at its own cadence while anyone subscribes, and on {@link #touch} and {@link #touchAll}; a live topic is
 * read once and then fed by its store as records change. Subscribers to one topic, arguments and auth level share a read.
 *
 * <p>Frames are {@code {"e":"t","d":{"id":n, ...}}} carrying {@code f} (every record), or {@code u} (changed
 * records), {@code r} (gone keys) and {@code o} (every key, when the order is not the old one with new keys
 * appended), records as {@code [key, value]} pairs; or {@code err} and {@code msg} for a failed read, which leaves
 * the records a viewer has in place. A topic of more than {@value #ORDER_CAP} records keeps arrival order.
 *
 * <p>Viewers are mostly anonymous and untrusted: a topic takes only the arguments it declares, at most
 * {@value #MAX_READS} reads run at once (logged-in viewers' first, then the longest unread, and one slot kept for
 * logged-in viewers), and anonymous subscriptions are capped per connection, per address and in all, and paced.
 */
@Component
public class TopicHub {

    private static final Logger LOG = LoggerFactory.getLogger(TopicHub.class);

    private static final long TICK_MS = 100;
    private static final long RETOUCH_MS = 1_000;
    private static final long RETRY_MS = 5_000;
    private static final long MIN_REFRESH_GAP_MS = 1_000;
    private static final int MAX_ARG_CHARS = 256;
    private static final int ORDER_CAP = 2000;
    private static final int MAX_READS = 4;
    private static final int MAX_ANONYMOUS_READS = MAX_READS - 1;
    private static final int MAX_SUBSCRIPTIONS = 32;
    private static final int MAX_PEER_SUBSCRIPTIONS = 64;
    private static final int MAX_ANONYMOUS_STATES = 1024;
    private static final int MAX_FRAME_CHARS = 8 << 20;
    private static final Object GONE = new Object();

    /** How often a polled topic whose source reports its changes ({@link #touch}) is read anyway. */
    public static final long BACKSTOP_MS = 60_000;

    public enum Access {
        ANYONE,
        AUTHED
    }

    /** Every record of the topic for one subscription, in display order; called only while someone subscribes. */
    @FunctionalInterface
    public interface Source {
        Map<String, ?> read(TopicQuery q);
    }

    /** Whether the record a live topic's store changed under {@code key} belongs to a subscription. */
    @FunctionalInterface
    public interface Scope {
        boolean covers(TopicQuery q, String key);
    }

    /** A live topic's write side: cheap to call on its store's write path, and a no-op while nobody subscribes. */
    public final class Feed {
        private final String topic;

        private Feed(String topic) {
            this.topic = topic;
        }

        public void put(String key, Object record) {
            publish(topic, key, record);
        }

        public void remove(String key) {
            publish(topic, key, GONE);
        }
    }

    private record Def(
            String name,
            Access access,
            long refreshMs,
            Set<String> params,
            Source source,
            @Nullable Scope scope) {}

    private record Key(String topic, TopicQuery query) {}

    private record Sub(StreamClient client, int id) {}

    private static final class State {
        final Def def;
        final Key key;
        final List<Sub> subs = new ArrayList<>();
        final Map<String, Object> pending = new LinkedHashMap<>();

        @Nullable
        Map<String, Object> sent;

        @Nullable
        String full;

        @Nullable
        String err;

        volatile boolean touched;
        long dueAtMs;
        long readAtMs;
        boolean reading;
        boolean again;
        boolean resync;
        boolean dead;

        State(Def def, Key key) {
            this.def = def;
            this.key = key;
            this.readAtMs = System.currentTimeMillis();
        }
    }

    private final ObjectMapper mapper;
    private final Map<String, Def> defs = new ConcurrentHashMap<>();
    private final Map<Key, State> states = new ConcurrentHashMap<>();
    private final Map<StreamClient, Map<Integer, State>> byClient = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "web-topics");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore readSlots = new Semaphore(MAX_READS);
    private final AtomicInteger anonymousReading = new AtomicInteger();
    private final AtomicBoolean retouchQueued = new AtomicBoolean();
    private volatile long touchedAtMs;

    public TopicHub(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Defines a topic of no arguments, read again every {@code refreshMs} while anyone subscribes. */
    public void polled(String name, Access access, long refreshMs, Source source) {
        polled(name, access, refreshMs, List.of(), source);
    }

    /** Defines a topic read again every {@code refreshMs} while anyone subscribes; it takes only {@code params}. */
    public void polled(String name, Access access, long refreshMs, List<String> params, Source source) {
        define(new Def(name, access, refreshMs, Set.copyOf(params), source, null));
    }

    /** Defines a topic read once per subscription and kept current through the returned feed from then on. */
    public Feed live(String name, Access access, List<String> params, Source snapshot, Scope scope) {
        define(new Def(name, access, 0, Set.copyOf(params), snapshot, scope));
        return new Feed(name);
    }

    private void define(Def def) {
        if (defs.putIfAbsent(def.name(), def) != null) {
            throw new IllegalStateException("topic " + def.name() + " is defined twice");
        }
    }

    /** Starts reading and delivering. Spring calls it; a hub constructed directly must call it once. */
    @PostConstruct
    @SuppressWarnings("FutureReturnValueIgnored")
    public void start() {
        ticker.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        ticker.shutdownNow();
        readers.shutdownNow();
    }

    /**
     * Subscribes {@code client} to {@code topic} under its own {@code id}, replacing what that id was subscribed to. A
     * refusal (no such topic, an argument the topic does not take, a cap) is sent to the client as an {@code err}
     * frame.
     */
    public void subscribe(StreamClient client, int id, String topic, Map<String, String> args) {
        unsubscribe(client, id);
        String refused = refusal(client, topic, args);
        if (refused != null) {
            client.offer(frame(id, refused));
            return;
        }
        Def def = defs.get(topic);
        Key key = new Key(topic, new TopicQuery(new TreeMap<>(args), client.authed()));
        if (!client.authed() && !states.containsKey(key) && anonymousStates() >= MAX_ANONYMOUS_STATES) {
            client.offer(frame(id, error(429, "too many viewers")));
            return;
        }
        Sub sub = new Sub(client, id);
        State joined;
        while (true) {
            State s = states.computeIfAbsent(key, k -> new State(def, k));
            synchronized (s) {
                if (s.dead) {
                    continue;
                }
                s.subs.add(sub);
                byClient.computeIfAbsent(client, c -> new ConcurrentHashMap<>()).put(id, s);
                offerFull(s, sub);
                if (s.err != null) {
                    client.offer(frame(id, s.err));
                }
                joined = s;
                break;
            }
        }
        if (!client.isOpen()) {
            // The socket closed meanwhile, and its forget may already have walked past this subscription.
            drop(joined, client, id);
            forget(client);
        }
    }

    private @Nullable String refusal(StreamClient client, String topic, Map<String, String> args) {
        Def def = defs.get(topic);
        if (def == null) {
            return error(404, "no topic " + topic);
        }
        if (def.access() == Access.AUTHED && !client.authed()) {
            return error(401, "authentication required");
        }
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (!def.params().contains(e.getKey())) {
                return error(400, topic + " takes no argument " + e.getKey());
            }
            if (e.getValue().length() > MAX_ARG_CHARS) {
                return error(400, e.getKey() + " is too long");
            }
        }
        if (!client.isOpen()) {
            return error(410, null);
        }
        Map<Integer, State> mine = byClient.get(client);
        if (mine != null && mine.size() >= MAX_SUBSCRIPTIONS) {
            return error(429, "too many subscriptions");
        }
        if (!client.authed() && peerSubscriptions(client.peer()) >= MAX_PEER_SUBSCRIPTIONS) {
            return error(429, "too many subscriptions");
        }
        if (!client.authed() && !client.takeSubscribeToken()) {
            return error(429, "subscribing too fast");
        }
        return null;
    }

    private int peerSubscriptions(String peer) {
        int n = 0;
        for (Map.Entry<StreamClient, Map<Integer, State>> e : byClient.entrySet()) {
            if (!e.getKey().authed() && e.getKey().peer().equals(peer)) {
                n += e.getValue().size();
            }
        }
        return n;
    }

    private int anonymousStates() {
        int n = 0;
        for (Key k : states.keySet()) {
            if (!k.query().authed()) {
                n++;
            }
        }
        return n;
    }

    public void unsubscribe(StreamClient client, int id) {
        Map<Integer, State> mine = byClient.get(client);
        State s = mine == null ? null : mine.remove(id);
        if (s != null) {
            drop(s, client, id);
        }
    }

    /**
     * Reads the subscription's topic again now, unless it was read in the last second; for a live topic, a fresh
     * snapshot replaces what its feed built.
     */
    public void refresh(StreamClient client, int id) {
        Map<Integer, State> mine = byClient.get(client);
        State s = mine == null ? null : mine.get(id);
        if (s == null) {
            return;
        }
        synchronized (s) {
            if (System.currentTimeMillis() - s.readAtMs >= MIN_REFRESH_GAP_MS) {
                due(s);
            }
        }
    }

    public void forget(StreamClient client) {
        Map<Integer, State> mine = byClient.remove(client);
        if (mine != null) {
            mine.forEach((id, s) -> drop(s, client, id));
        }
    }

    /**
     * Reads the subscriptions to {@code topic} that {@code which} picks again on the next tick of this hub, or right
     * after a read under way. Takes no lock, so it may be called from a hot path.
     */
    public void touch(String topic, Predicate<TopicQuery> which) {
        for (State s : states.values()) {
            if (s.key.topic().equals(topic) && which.test(s.key.query())) {
                s.touched = true;
            }
        }
    }

    /** Reads every polled topic again now and once more shortly after, for writes a store applies later. */
    @SuppressWarnings("FutureReturnValueIgnored")
    public void touchAll() {
        touchedAtMs = System.currentTimeMillis();
        touchNow();
        if (retouchQueued.compareAndSet(false, true)) {
            ticker.schedule(this::retouch, RETOUCH_MS, TimeUnit.MILLISECONDS);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void retouch() {
        long wait = touchedAtMs + RETOUCH_MS - System.currentTimeMillis();
        if (wait > 0) {
            ticker.schedule(this::retouch, wait, TimeUnit.MILLISECONDS);
            return;
        }
        retouchQueued.set(false);
        touchNow();
    }

    private void touchNow() {
        for (State s : states.values()) {
            if (s.def.refreshMs() > 0) {
                due(s);
            }
        }
    }

    private static void due(State s) {
        synchronized (s) {
            if (s.reading) {
                s.again = true;
            } else {
                s.dueAtMs = 0;
                s.resync = true;
            }
        }
    }

    private void drop(State s, StreamClient client, int id) {
        synchronized (s) {
            s.subs.removeIf(sub -> sub.client() == client && sub.id() == id);
            if (s.subs.isEmpty()) {
                s.dead = true;
                states.remove(s.key, s);
            }
        }
    }

    private void publish(String topic, String key, Object record) {
        for (State s : states.values()) {
            Scope scope = s.def.scope();
            if (scope == null || !s.key.topic().equals(topic) || !scope.covers(s.key.query(), key)) {
                continue;
            }
            synchronized (s) {
                if (!s.dead) {
                    s.pending.put(key, record);
                }
            }
        }
    }

    private void tick() {
        try {
            startReads(dueStates());
        } catch (RuntimeException e) {
            LOG.warn("topics: tick failed", e);
        }
    }

    private List<State> dueStates() {
        long now = System.currentTimeMillis();
        List<State> due = new ArrayList<>();
        for (State s : states.values()) {
            try {
                synchronized (s) {
                    if (s.dead || s.reading) {
                        continue;
                    }
                    if (s.touched) {
                        s.touched = false;
                        s.dueAtMs = 0;
                    }
                    if (s.sent != null && !s.pending.isEmpty()) {
                        flushPending(s);
                    }
                    if ((s.sent == null || s.resync || s.def.refreshMs() > 0) && now >= s.dueAtMs) {
                        due.add(s);
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("topic {}: tick failed", s.key.topic(), e);
            }
        }
        return due;
    }

    private void startReads(List<State> due) {
        long now = System.currentTimeMillis();
        // A state's readAtMs changes only below, on this thread, so the sort keys hold still.
        due.sort(Comparator.comparing((State s) -> !s.key.query().authed()).thenComparingLong(s -> s.readAtMs));
        for (State s : due) {
            boolean anonymous = !s.key.query().authed();
            if (anonymous && anonymousReading.get() >= MAX_ANONYMOUS_READS) {
                return;
            }
            if (!readSlots.tryAcquire()) {
                return;
            }
            boolean start;
            synchronized (s) {
                start = !s.dead && !s.reading;
                if (start) {
                    s.reading = true;
                    s.resync = false;
                    s.readAtMs = now;
                }
            }
            if (start) {
                if (anonymous) {
                    anonymousReading.incrementAndGet();
                }
                readers.execute(() -> read(s));
            } else {
                readSlots.release();
            }
        }
    }

    private void read(State s) {
        Map<String, ?> next = null;
        String failure = error(500, "internal error");
        try {
            next = s.def.source().read(s.key.query());
        } catch (TopicException e) {
            failure = error(e.status(), e.reason());
        } catch (RuntimeException e) {
            LOG.warn("topic {} {}: read failed", s.key.topic(), s.key.query().args(), e);
        } finally {
            readSlots.release();
            if (!s.key.query().authed()) {
                anonymousReading.decrementAndGet();
            }
            settle(s, next, failure);
        }
    }

    /** Delivers a read, or its failure when {@code next} is null, and schedules the next one. */
    private void settle(State s, @Nullable Map<String, ?> next, String failure) {
        synchronized (s) {
            try {
                if (s.dead) {
                    return;
                }
                if (next != null) {
                    deliver(s, next);
                } else {
                    fail(s, failure);
                }
            } catch (RuntimeException e) {
                LOG.warn("topic {}: delivery failed", s.key.topic(), e);
            } finally {
                s.reading = false;
                long refresh = s.def.refreshMs();
                long wait = refresh <= 0 ? RETRY_MS : s.err == null ? refresh : Math.min(refresh, RETRY_MS);
                s.dueAtMs = s.again ? 0 : System.currentTimeMillis() + wait;
                s.resync = s.again;
                s.again = false;
            }
        }
    }

    /**
     * Sends what changed from {@code s.sent} to the merged read and makes that the new {@code s.sent}. A frame is
     * serialized before anything is committed, so a failure leaves viewers and {@code s.sent} agreeing.
     */
    private void deliver(State s, Map<String, ?> read) {
        Map<String, Object> next = new LinkedHashMap<>(read);
        for (Map.Entry<String, Object> e : s.pending.entrySet()) {
            if (e.getValue() == GONE) {
                next.remove(e.getKey());
            } else {
                next.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> was = s.sent;
        if (was == null) {
            String f = full(next);
            if (tooLarge(s, f)) {
                return;
            }
            s.pending.clear();
            s.sent = next;
            s.full = f;
            s.err = null;
            for (Sub sub : s.subs) {
                sub.client().offer(frame(sub.id(), f));
            }
            return;
        }
        List<List<Object>> up = new ArrayList<>();
        Map<String, Object> appended = new LinkedHashMap<>();
        for (String k : was.keySet()) {
            if (next.containsKey(k)) {
                appended.put(k, next.get(k));
            }
        }
        for (Map.Entry<String, Object> e : next.entrySet()) {
            if (!was.containsKey(e.getKey()) || !Objects.equals(was.get(e.getKey()), e.getValue())) {
                up.add(Arrays.asList(e.getKey(), e.getValue()));
            }
            appended.putIfAbsent(e.getKey(), e.getValue());
        }
        List<String> gone = new ArrayList<>();
        for (String k : was.keySet()) {
            if (!next.containsKey(k)) {
                gone.add(k);
            }
        }
        List<String> order = null;
        if (next.size() <= ORDER_CAP && !grouped(appended.keySet()).equals(grouped(next.keySet()))) {
            order = List.copyOf(next.keySet());
        }
        String payload = delta(up, gone, order, s.err != null);
        if (payload != null && tooLarge(s, payload)) {
            return;
        }
        s.pending.clear();
        s.sent = order != null ? next : appended;
        if (payload != null) {
            s.full = null;
            s.err = null;
            offerAll(s, payload);
        }
    }

    /**
     * The keys in order within each group of keys sharing what precedes their first {@code /}: one list or map of a
     * {@link Parts} layout, whose order is all a viewer assembles by.
     */
    private static Map<String, List<String>> grouped(Collection<String> keys) {
        Map<String, List<String>> out = new HashMap<>();
        for (String k : keys) {
            int slash = k.indexOf('/');
            out.computeIfAbsent(slash < 0 ? "" : k.substring(0, slash), g -> new ArrayList<>())
                    .add(k);
        }
        return out;
    }

    private void flushPending(State s) {
        Map<String, Object> sent = Objects.requireNonNull(s.sent);
        Map<String, Object> changed = new LinkedHashMap<>();
        List<List<Object>> up = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, Object> e : s.pending.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == GONE) {
                if (sent.containsKey(k)) {
                    changed.put(k, v);
                    gone.add(k);
                }
            } else if (!sent.containsKey(k) || !Objects.equals(sent.get(k), v)) {
                changed.put(k, v);
                up.add(Arrays.asList(k, v));
            }
        }
        String payload;
        try {
            payload = delta(up, gone, null, false);
        } catch (RuntimeException e) {
            LOG.warn("topic {}: delivery failed", s.key.topic(), e);
            resync(s);
            return;
        }
        if (payload != null && tooLarge(s, payload)) {
            return;
        }
        s.pending.clear();
        if (payload == null) {
            return;
        }
        changed.forEach((k, v) -> {
            if (v == GONE) {
                sent.remove(k);
            } else {
                sent.put(k, v);
            }
        });
        s.full = null;
        offerAll(s, payload);
    }

    /** Forgets what viewers were sent, so the next read sends every record again. */
    private static void resync(State s) {
        s.sent = null;
        s.full = null;
        s.pending.clear();
    }

    /** True when {@code payload} is too big for one frame: the topic's viewers are told so, and keep what they have. */
    private boolean tooLarge(State s, String payload) {
        if (payload.length() <= MAX_FRAME_CHARS) {
            return false;
        }
        resync(s);
        fail(s, error(413, "too large to send"));
        return true;
    }

    private @Nullable String delta(
            List<List<Object>> up, List<String> gone, @Nullable List<String> order, boolean recovered) {
        if (up.isEmpty() && gone.isEmpty() && order == null && !recovered) {
            return null;
        }
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("u", up);
        if (!gone.isEmpty()) {
            delta.put("r", gone);
        }
        if (order != null) {
            delta.put("o", order);
        }
        String json = mapper.writeValueAsString(delta);
        return json.substring(1, json.length() - 1);
    }

    private static void offerAll(State s, String payload) {
        for (Sub sub : s.subs) {
            sub.client().offer(frame(sub.id(), payload));
        }
    }

    private void offerFull(State s, Sub sub) {
        if (s.sent == null) {
            return;
        }
        if (s.full == null) {
            s.full = full(s.sent);
        }
        sub.client()
                .offer(frame(sub.id(), s.full.length() <= MAX_FRAME_CHARS ? s.full : error(413, "too large to send")));
    }

    private void fail(State s, String failure) {
        if (failure.equals(s.err)) {
            return;
        }
        s.err = failure;
        for (Sub sub : s.subs) {
            sub.client().offer(frame(sub.id(), failure));
        }
    }

    private String full(Map<String, Object> records) {
        List<List<Object>> all = new ArrayList<>(records.size());
        records.forEach((k, v) -> all.add(Arrays.asList(k, v)));
        return "\"f\":" + mapper.writeValueAsString(all);
    }

    private String error(int status, @Nullable String reason) {
        return "\"err\":" + status + ",\"msg\":" + mapper.writeValueAsString(reason);
    }

    private static TextMessage frame(int id, String payload) {
        return new TextMessage("{\"e\":\"t\",\"d\":{\"id\":" + id + "," + payload + "}}");
    }
}
