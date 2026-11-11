package openrsc.livemap.live;

import java.util.ArrayList;
import java.util.List;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.stream.StreamClient;
import openrsc.livemap.stream.StreamRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;

/**
 * Sends a viewer everything the live stream's deltas build on, in this order: the bots ({@code snapshot}), the
 * {@link SeedHistory}'s events, the server's clock ({@code serverTime}), each {@link LiveOverlay}'s events, and the
 * merged world ({@code world}).
 */
@Component
public class LiveSeeder {

    private final LiveFeed feed;
    private final LiveRoster roster;
    private final BotLiveStore store;
    private final StreamRegistry registry;
    private final ObjectProvider<SeedHistory> history;
    private final ObjectProvider<LiveOverlay> overlays;

    public LiveSeeder(
            LiveFeed feed,
            LiveRoster roster,
            BotLiveStore store,
            StreamRegistry registry,
            ObjectProvider<SeedHistory> history,
            ObjectProvider<LiveOverlay> overlays) {
        this.feed = feed;
        this.roster = roster;
        this.store = store;
        this.registry = registry;
        this.history = history;
        this.overlays = overlays;
    }

    /**
     * Seeds {@code client}. The bases are taken under the stream lock with the viewer's frames held back, and
     * serialized after it is let go, so a seed costs other viewers no flush time.
     */
    public void seed(StreamClient client, boolean authed) {
        List<String> usernames = roster.usernames(authed);
        List<JsonNode> sent = new ArrayList<>(usernames.size());
        SeedHistory past = history.getIfAvailable();
        List<Frame> before;
        if (past != null) {
            before = past.holdAndTake(client, authed);
        } else {
            client.hold();
            before = List.of();
        }
        List<TextMessage> seed = List.of();
        try {
            List<Frame> after = new ArrayList<>();
            Object world;
            synchronized (feed.streamLock()) {
                for (String username : usernames) {
                    sent.add(feed.sentBot(authed, username));
                }
                overlays.orderedStream().forEach(o -> after.addAll(o.seed(authed)));
                world = feed.worldSeed(authed);
            }
            List<Object> all = new ArrayList<>(usernames.size());
            for (int i = 0; i < usernames.size(); i++) {
                JsonNode base = sent.get(i);
                if (base != null) {
                    all.add(base);
                    continue;
                }
                BotLiveDto parked = store.get(usernames.get(i));
                BotLiveDto bot = parked != null ? parked : roster.unparked(usernames.get(i));
                if (bot != null) {
                    all.add(LiveFeed.shared(bot));
                }
            }
            List<TextMessage> out = new ArrayList<>();
            out.add(registry.envelope("snapshot", all));
            for (Frame f : before) {
                out.add(registry.envelope(f.event(), f.data()));
            }
            out.add(registry.envelope("serverTime", System.currentTimeMillis()));
            for (Frame f : after) {
                out.add(registry.envelope(f.event(), f.data()));
            }
            out.add(registry.envelope("world", world));
            seed = out;
        } finally {
            client.release(seed);
        }
    }
}
