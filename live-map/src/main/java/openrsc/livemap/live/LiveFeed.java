package openrsc.livemap.live;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.stream.StreamRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends every viewer the bots and what they see, as deltas. Each flush sends the bots parked in the
 * {@link BotLiveStore} since the last ({@code bd}); every third also sends the bots' merged view of the world
 * ({@code world}, as {@link WorldPool} lays it out), now and then the server's clock ({@code serverTime}), and each
 * {@link LiveOverlay}'s changes. An anonymous viewer is sent only what the {@link AnonymousView} shows. Flushes run on
 * a thread of the feed's own, every {@code openrsc.web.flushMs} ms (200 by default), and only while anyone watches.
 */
@Component
public class LiveFeed {

    private static final Logger LOG = LoggerFactory.getLogger(LiveFeed.class);

    private static final long FLUSH_MS = Math.max(20L, Long.getLong("openrsc.web.flushMs", 200L));
    private static final int WORLD_EVERY = 3;
    private static final int SERVER_TIME_EVERY = 128;

    private static final String TICK = "serverTick";

    private static final Set<String> EVENTS = Set.of("dmg", "projectiles");

    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "web-batch-flusher");
        t.setDaemon(true);
        return t;
    });

    private final StreamRegistry registry;
    private final BotLiveStore store;
    private final DetailFanout detail;
    private final LiveRoster roster;
    private final AnonymousView anonymous;
    private final ObjectProvider<LiveOverlay> overlays;
    private final ObjectMapper mapper;

    private final Object streamLock = new Object();
    private final Map<String, ObjectNode> sentAll = new HashMap<>();
    private final Map<String, ObjectNode> sentAnon = new HashMap<>();
    private final WorldPool worldAll = new WorldPool();
    private final WorldPool worldAnon = new WorldPool();
    private int sinceWorld;
    private int sinceServerTime = SERVER_TIME_EVERY;

    public LiveFeed(
            StreamRegistry registry,
            BotLiveStore store,
            DetailFanout detail,
            LiveRoster roster,
            AnonymousView anonymous,
            ObjectProvider<LiveOverlay> overlays,
            ObjectMapper mapper) {
        this.registry = registry;
        this.store = store;
        this.detail = detail;
        this.roster = roster;
        this.anonymous = anonymous;
        this.overlays = overlays;
        this.mapper = mapper;
    }

    @PostConstruct
    @SuppressWarnings("FutureReturnValueIgnored")
    void start() {
        flusher.scheduleWithFixedDelay(this::flush, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        flusher.shutdownNow();
    }

    private void flush() {
        try {
            synchronized (streamLock) {
                flushBatch();
                if (++sinceWorld >= WORLD_EVERY) {
                    sinceWorld = 0;
                    flushWorld();
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("stream flush failed", e);
        }
    }

    /**
     * Held while a viewer is seeded and while every delta stream is computed and sent, so a seed is always the base the
     * next delta was taken against.
     */
    Object streamLock() {
        return streamLock;
    }

    /** The bot as the last {@code bd} frame left it for that audience, or null when no delta base is kept for it. */
    @Nullable
    JsonNode sentBot(boolean authed, String username) {
        synchronized (streamLock) {
            return (authed ? sentAll : sentAnon).get(username);
        }
    }

    Map<String, Object> worldSeed(boolean authed) {
        return (authed ? worldAll : worldAnon).seed();
    }

    private void flushBatch() {
        roster.beforeFlush(store);
        if (!registry.hasSubscribers()) {
            store.drainDirty();
            sentAll.clear();
            sentAnon.clear();
            return;
        }
        List<BotLiveDto> changed = store.drainDirty();
        if (changed.isEmpty()) {
            return;
        }
        List<BotLiveDto> slim = new ArrayList<>(changed.size());
        for (BotLiveDto full : changed) {
            slim.add(shared(full));
        }
        Map<String, Object> all = botDelta(mapper, sentAll, slim);
        if (registry.hasAnonymousSubscribers()) {
            Map<String, Object> anon = botDelta(mapper, sentAnon, anonymousBatch(slim));
            if (all != null || anon != null) {
                registry.broadcastScoped("bd", all != null ? all : Map.of(), anon != null ? anon : Map.of());
            }
        } else {
            sentAnon.clear();
            if (all != null) {
                registry.broadcastAuthed("bd", all);
            }
        }
        detail.flush(changed);
    }

    private List<BotLiveDto> anonymousBatch(List<BotLiveDto> batch) {
        List<BotLiveDto> out = new ArrayList<>(batch.size());
        for (BotLiveDto d : batch) {
            if (anonymous.shows(d)) {
                out.add(d.withoutDescription());
            }
        }
        return out;
    }

    /**
     * {@code f}: bots new to this audience, whole; {@code d}: the rest as {@code username} plus each field that changed
     * since the last frame, null for a field that is gone. {@code serverTick} rides along with any other change but is
     * no change of its own; an event field goes out every tick it is present, as a repeat is a new event. Null when
     * nothing changed.
     */
    static @Nullable Map<String, Object> botDelta(
            ObjectMapper mapper, Map<String, ObjectNode> sent, List<BotLiveDto> bots) {
        List<JsonNode> full = new ArrayList<>();
        List<JsonNode> part = new ArrayList<>();
        for (BotLiveDto dto : bots) {
            ObjectNode cur = mapper.valueToTree(dto);
            ObjectNode prev = sent.put(dto.username(), cur);
            if (prev == null) {
                full.add(cur);
                continue;
            }
            ObjectNode d = null;
            for (Map.Entry<String, JsonNode> e : cur.properties()) {
                String key = e.getKey();
                if (!key.equals(TICK) && (EVENTS.contains(key) || !e.getValue().equals(prev.get(key)))) {
                    d = d != null ? d : mapper.createObjectNode().put("username", dto.username());
                    d.set(key, e.getValue());
                }
            }
            for (String key : prev.propertyNames()) {
                if (!cur.has(key)) {
                    d = d != null ? d : mapper.createObjectNode().put("username", dto.username());
                    d.putNull(key);
                }
            }
            if (d != null) {
                if (cur.has(TICK) && !cur.get(TICK).equals(prev.get(TICK))) {
                    d.set(TICK, cur.get(TICK));
                }
                part.add(d);
            }
        }
        if (full.isEmpty() && part.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!full.isEmpty()) {
            out.put("f", full);
        }
        if (!part.isEmpty()) {
            out.put("d", part);
        }
        return out;
    }

    private void flushWorld() {
        List<BotLiveDto> parked = store.all();
        Map<String, Object> delta = worldAll.update(parked);
        List<BotLiveDto> shown = new ArrayList<>(parked.size());
        for (BotLiveDto b : parked) {
            if (anonymous.shows(b)) {
                shown.add(b);
            }
        }
        Map<String, Object> anonDelta = worldAnon.update(shown);
        if (delta != null || anonDelta != null) {
            if (registry.hasAnonymousSubscribers()) {
                registry.broadcastScoped(
                        "world", delta != null ? delta : Map.of(), anonDelta != null ? anonDelta : Map.of());
            } else if (delta != null) {
                registry.broadcastAuthed("world", delta);
            }
        }
        long nowMs = System.currentTimeMillis();
        broadcastServerTime(nowMs);
        overlays.orderedStream().forEach(o -> o.flush(registry, nowMs));
    }

    private void broadcastServerTime(long nowMs) {
        if (++sinceServerTime < SERVER_TIME_EVERY) {
            return;
        }
        sinceServerTime = 0;
        registry.broadcast("serverTime", nowMs);
    }

    /** The bot as every viewer is sent it: without its entity lists, and without what {@link DetailFanout} sends. */
    public static BotLiveDto shared(BotLiveDto full) {
        return new BotLiveDto(
                full.username(),
                full.status(),
                full.scriptClass(),
                full.task(),
                null,
                full.serverTick(),
                full.position(),
                null,
                full.sleeping(),
                full.inCombat(),
                full.serverIndex(),
                null,
                null,
                null,
                null,
                null,
                full.appearance(),
                full.dir(),
                full.hits(),
                full.maxHits(),
                full.skulled(),
                full.dmg(),
                null,
                full.msg(),
                full.msgTick(),
                full.projectiles(),
                full.bubble(),
                full.bubbleTick(),
                null,
                full.combatLvl(),
                full.groups(),
                null);
    }

    /** The bot without the lists of what it sees, which go out through the {@link WorldPool}. */
    public static BotLiveDto stripEntityLists(BotLiveDto full) {
        if (full.npcs() == null
                && full.players() == null
                && full.objects() == null
                && full.wallObjects() == null
                && full.groundItems() == null) {
            return full;
        }
        return new BotLiveDto(
                full.username(),
                full.status(),
                full.scriptClass(),
                full.task(),
                full.description(),
                full.serverTick(),
                full.position(),
                full.fatiguePercent(),
                full.sleeping(),
                full.inCombat(),
                full.serverIndex(),
                null,
                null,
                null,
                null,
                full.sessionId(),
                full.appearance(),
                full.dir(),
                full.hits(),
                full.maxHits(),
                full.skulled(),
                full.dmg(),
                null,
                full.msg(),
                full.msgTick(),
                full.projectiles(),
                full.bubble(),
                full.bubbleTick(),
                full.dialog(),
                full.combatLvl(),
                full.groups(),
                full.windows());
    }
}
