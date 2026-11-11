package openrsc.sessionproxy.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.GameState;
import openrsc.bot.api.Messages;
import openrsc.bot.api.PlayerChat;
import openrsc.bot.api.PrivateMessage;
import openrsc.bot.api.ServerMessage;
import openrsc.bot.api.TickEvents;
import openrsc.livemap.live.Frame;
import openrsc.livemap.live.SeedHistory;
import openrsc.livemap.stream.StreamClient;
import openrsc.livemap.stream.StreamRegistry;
import openrsc.sessionproxy.Session;
import openrsc.sessionproxy.SessionListener;
import org.jetbrains.annotations.Nullable;

/**
 * What the proxy's sessions hear, for the map's chat box: the players' chat, each line once however many sessions heard
 * it, the private messages each account sends and gets, and the server's lines to each account. A viewer is seeded with
 * what is kept ({@code chat-history}, {@code private-history}, a {@code msgs} per account), and sent each tick's new
 * lines as {@code chat}, {@code private} and {@code msgs}, in the shapes the runner's map sends them.
 */
final class ProxyChat implements SessionListener, SeedHistory {

    /** A line of chat; {@code heardBy} names the account that heard it first. */
    record ChatLine(
            long timestamp,
            String sender,
            int senderIndex,
            String text,
            List<String> heardBy,
            boolean outgoing,
            boolean global,
            @Nullable String kill) {}

    record PrivateLine(long seq, long tick, long timestamp, String bot, String peer, String text, boolean sent) {}

    record ServerLine(
            long seq,
            long tick,
            long timestamp,
            String text,
            int type,
            @Nullable String colour) {}

    private static final int CHAT_CAP = 500;
    private static final int PRIVATE_CAP = 200;
    private static final int SERVER_CAP = 200;

    /** How long a line another session heard first is taken for the same line, in milliseconds. */
    private static final long SAME_LINE_MS = 3_000;

    private final StreamRegistry registry;
    private final Object lock = new Object();
    private final Deque<ChatLine> chat = new ArrayDeque<>();
    private final Deque<PrivateLine> privates = new ArrayDeque<>();
    private final Map<String, Deque<ServerLine>> server = new LinkedHashMap<>();
    private final Map<String, Long> heard = new HashMap<>();
    private long seq;

    ProxyChat(StreamRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void ticked(Session session, GameState state) {
        String bot = session.username();
        long tick = session.serverTick();
        long now = System.currentTimeMillis();
        TickEvents events = state.tickEvents();
        synchronized (lock) {
            heard.values().removeIf(at -> now - at > SAME_LINE_MS);
            List<ChatLine> freshChat = new ArrayList<>();
            // Only public chat is the chat history's, as the stock client keeps it; a player's lines to an NPC show
            // over its head alone:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L736-L751
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/PacketHandler.java#L2617-L2670
            for (PlayerChat c : events.selfChat()) {
                if (c.spoken() && heard.putIfAbsent(c.sender() + "\0" + c.text(), now) == null) {
                    freshChat.add(
                            new ChatLine(now, c.sender(), c.senderIndex(), c.text(), List.of(bot), true, false, null));
                }
            }
            for (PlayerChat c : events.playerChat()) {
                if (c.spoken() && heard.putIfAbsent(c.sender() + "\0" + c.text(), now) == null) {
                    freshChat.add(
                            new ChatLine(now, c.sender(), c.senderIndex(), c.text(), List.of(bot), false, false, null));
                }
            }
            List<PrivateLine> freshPrivate = new ArrayList<>();
            for (PrivateMessage m : events.privateMessages()) {
                freshPrivate.add(new PrivateLine(++seq, tick, now, bot, m.name(), m.message(), m.sent()));
            }
            List<ServerLine> freshServer = new ArrayList<>();
            Messages messages = state.messages();
            for (ServerMessage m : messages.thisTick()) {
                String text = m.displayText();
                if (text != null && !Messages.fromAPlayer(m)) {
                    freshServer.add(new ServerLine(++seq, tick, now, text, m.type(), m.colour()));
                }
            }
            keep(chat, freshChat, CHAT_CAP);
            keep(privates, freshPrivate, PRIVATE_CAP);
            keep(server.computeIfAbsent(bot, b -> new ArrayDeque<>()), freshServer, SERVER_CAP);
            // Under the lock a viewer is seeded under, so it gets each line once: in its history or after it.
            if (!freshChat.isEmpty()) {
                registry.broadcast("chat", freshChat);
            }
            if (!freshPrivate.isEmpty()) {
                registry.broadcast("private", freshPrivate);
            }
            if (!freshServer.isEmpty()) {
                registry.broadcast("msgs", Map.of("bot", bot, "list", freshServer));
            }
        }
    }

    @Override
    public void ended(Session session) {}

    @Override
    public List<Frame> holdAndTake(StreamClient client, boolean authed) {
        synchronized (lock) {
            client.hold();
            List<Frame> out = new ArrayList<>();
            out.add(new Frame("chat-history", List.copyOf(chat)));
            out.add(new Frame("private-history", List.copyOf(privates)));
            server.forEach((bot, lines) -> out.add(new Frame("msgs", Map.of("bot", bot, "list", List.copyOf(lines)))));
            return out;
        }
    }

    private static <T> void keep(Deque<T> kept, List<T> fresh, int cap) {
        kept.addAll(fresh);
        while (kept.size() > cap) {
            kept.removeFirst();
        }
    }
}
