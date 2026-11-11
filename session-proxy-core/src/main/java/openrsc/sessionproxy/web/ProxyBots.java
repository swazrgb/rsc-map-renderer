package openrsc.sessionproxy.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import openrsc.bot.api.GameState;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.eligibility.Holding;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.items.ItemHoldings;
import openrsc.livemap.live.AnonymousView;
import openrsc.livemap.live.BotLiveEncoder;
import openrsc.livemap.live.BotLiveStore;
import openrsc.livemap.live.LiveRoster;
import openrsc.livemap.panel.BotPanelDto;
import openrsc.livemap.panel.BotPanelEncoder;
import openrsc.livemap.panel.BotPanelTopic;
import openrsc.livemap.panel.BotPanels;
import openrsc.livemap.planner.PlannerAccounts;
import openrsc.livemap.stream.StreamRegistry;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.sessionproxy.ProxyRouting;
import openrsc.sessionproxy.Session;
import openrsc.sessionproxy.SessionListener;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The proxy's sessions on the live map: every account that has held a session since the proxy started, online or not,
 * its record parked as each of its ticks completes, and its last state for the panel. The items tab counts the
 * accounts online.
 */
public final class ProxyBots
        implements SessionListener, LiveRoster, AnonymousView, BotPanels, ItemHoldings, PlannerAccounts {

    static final String ONLINE = "ONLINE";
    static final String OFFLINE = "OFFLINE";

    private record Seen(Session session, GameState state, boolean online) {}

    private final BotLiveEncoder encoder;
    private final BotLiveStore store;
    private final StreamRegistry registry;
    private final ObjectProvider<BotPanelTopic> panel;
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    public ProxyBots(
            BotLiveEncoder encoder, BotLiveStore store, StreamRegistry registry, ObjectProvider<BotPanelTopic> panel) {
        this.encoder = encoder;
        this.store = store;
        this.registry = registry;
        this.panel = panel;
    }

    @Override
    public void ticked(Session session, GameState state) {
        String username = session.username();
        // The watchdog can end the session while this tick is on its way; ended() runs after it is marked dead.
        Seen now = seen.compute(username, (name, was) -> {
            if (session.isDead()) {
                return was;
            }
            if (registry.hasSubscribers()) {
                store.park(online(name, state));
            }
            return new Seen(session, state, true);
        });
        if (now == null || !now.online()) {
            return;
        }
        BotPanelTopic topic = panel.getIfAvailable();
        if (topic != null) {
            topic.touch(username);
        }
    }

    @Override
    public @Nullable AccountFacts account(String name) {
        Seen s = seen.get(name);
        return s == null ? null : s.state();
    }

    @Override
    public NavPolicy routing(Pathfinder pf, String name, NavPolicy asked) {
        return ProxyRouting.limit(pf, asked);
    }

    @Override
    public void ended(Session session) {
        seen.computeIfPresent(session.username(), (name, was) -> {
            if (was.session() != session) {
                return was;
            }
            store.park(offline(name, was.state()));
            return new Seen(session, was.state(), false);
        });
    }

    private BotLiveDto online(String username, GameState state) {
        return encoder.encode(state, new BotLiveEncoder.Identity(username, ONLINE, null, null, null, null, null));
    }

    /** Where the bot was last seen, and how it looked. */
    private static BotLiveDto offline(String username, GameState state) {
        return new BotLiveDto(
                        username,
                        OFFLINE,
                        null,
                        null,
                        null,
                        null,
                        BotLiveEncoder.position(state),
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
                        null)
                .withAppearance(state.selfPanel().appearance().token());
    }

    @Override
    public List<String> usernames(boolean authed) {
        if (!authed) {
            return List.of();
        }
        List<String> out = new ArrayList<>(seen.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    @Override
    public @Nullable BotLiveDto unparked(String username) {
        Seen s = seen.get(username);
        if (s == null) {
            return null;
        }
        return s.online() ? online(username, s.state()) : offline(username, s.state());
    }

    @Override
    public boolean shows(BotLiveDto bot) {
        return false;
    }

    @Override
    public boolean showsDetail(String username) {
        return false;
    }

    @Override
    public @Nullable BotPanelDto panel(String username, boolean authed) {
        Seen s = authed ? seen.get(username) : null;
        return s == null
                ? null
                : BotPanelEncoder.panel(
                        s.state(), s.online() ? ONLINE : OFFLINE, s.state().bankObserved());
    }

    @Override
    public Map<Integer, List<Holding>> byItem() {
        Map<Integer, List<Holding>> out = new TreeMap<>();
        for (Seen s : seen.values()) {
            if (s.online()) {
                for (Holding h : Holding.rowsOf(s.state())) {
                    out.computeIfAbsent(h.itemId(), k -> new ArrayList<>()).add(h);
                }
            }
        }
        out.values().forEach(list -> list.sort(Holding.MOST_FIRST));
        return out;
    }

    @Override
    public List<Holding> holdersOf(int itemId) {
        List<Holding> out = new ArrayList<>();
        for (Seen s : seen.values()) {
            if (s.online()) {
                Holding h = Holding.of(s.state(), itemId);
                if (h.inventory() > 0 || h.bank() > 0) {
                    out.add(h);
                }
            }
        }
        out.sort(Holding.MOST_FIRST);
        return out;
    }

    @Override
    public Predicate<String> visible(boolean authed) {
        return u -> authed;
    }
}
