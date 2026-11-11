package openrsc.livemap.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.stream.StreamClient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bot fields sent only to the viewers that ask for the bot, as {@code bx} frames: every detail field to a viewer
 * watching it, the first line of its description ({@code doing}) to one listing it. A viewer gets a bot's fields whole,
 * absent ones as null, when it starts asking for them, then each change. Entries are {@code username} plus fields, a
 * null field is one that is gone. Anonymous viewers get neither description, dialog nor windows.
 */
@Component
public class DetailFanout {

    static final String TICK = "serverTick";
    static final String DESCRIPTION = "description";
    static final String DIALOG = "dialog";
    static final String WINDOWS = "windows";
    static final String FATIGUE = "fatiguePercent";
    static final String DOING = "doing";

    static final int MAX_WATCHED = 64;
    static final int MAX_LISTED = 512;

    private static final Set<String> AUTHED_ONLY = Set.of(DESCRIPTION, DIALOG, WINDOWS);

    private record Interest(Set<String> watched, Set<String> listed) {}

    private final ObjectMapper mapper;
    private final Visibility visible;
    private final Map<StreamClient, Interest> interests = new ConcurrentHashMap<>();
    private final Map<String, ObjectNode> details = new HashMap<>();
    private final Map<String, String> doing = new HashMap<>();

    @Autowired
    public DetailFanout(ObjectMapper mapper, AnonymousView anonymous) {
        this(mapper, anonymous::showsDetail);
    }

    DetailFanout(ObjectMapper mapper, Visibility visible) {
        this.mapper = mapper;
        this.visible = visible;
    }

    /**
     * Replaces the bots {@code client} asks detail for, sending the ones it did not have yet whole. A bot it may not
     * see is left out, and each set is cut to its cap.
     */
    synchronized void interest(StreamClient client, Set<String> watched, Set<String> listed) {
        Set<String> w = capped(watched, MAX_WATCHED, client);
        Set<String> l = capped(listed, MAX_LISTED, client);
        l.removeAll(w);
        Interest was = interests.getOrDefault(client, new Interest(Set.of(), Set.of()));
        interests.put(client, new Interest(w, l));
        send(client, w, l, was);
    }

    /** Sends {@code client} the fields of every bot it asks for whole, for a viewer whose bots were replaced. */
    public synchronized void resend(StreamClient client) {
        Interest in = interests.get(client);
        if (in != null) {
            send(client, in.watched(), in.listed(), new Interest(Set.of(), Set.of()));
        }
    }

    private void send(StreamClient client, Set<String> watched, Set<String> listed, Interest had) {
        List<ObjectNode> seed = new ArrayList<>();
        for (String bot : watched) {
            ObjectNode d = details.get(bot);
            if (!had.watched().contains(bot) && d != null) {
                ObjectNode whole = d.deepCopy();
                for (String field : List.of(DESCRIPTION, DIALOG, FATIGUE, WINDOWS)) {
                    if (!whole.has(field)) {
                        whole.putNull(field);
                    }
                }
                seed.add(scoped(whole, client.authed()));
            }
        }
        if (client.authed()) {
            for (String bot : listed) {
                if (!had.listed().contains(bot) && doing.containsKey(bot)) {
                    seed.add(entry(bot).put(DOING, doing.get(bot)));
                }
            }
        }
        if (!seed.isEmpty() && client.liveFrames()) {
            client.offer(frame(seed));
        }
    }

    void forget(StreamClient client) {
        interests.remove(client);
    }

    /** Takes in a flush's changed bots and sends each viewer what changed of the bots it asks for. */
    synchronized void flush(List<BotLiveDto> changed) {
        Map<String, ObjectNode> authedDelta = new HashMap<>();
        Map<String, ObjectNode> doingDelta = new HashMap<>();
        for (BotLiveDto b : changed) {
            ObjectNode cur = detail(b);
            ObjectNode prev = details.put(b.username(), cur);
            ObjectNode d = delta(b.username(), prev, cur);
            if (d != null) {
                authedDelta.put(b.username(), d);
            }
            String line = firstLine(b.description());
            if (!Objects.equals(doing.put(b.username(), line), line)) {
                doingDelta.put(b.username(), entry(b.username()).put(DOING, line));
            }
        }
        if (authedDelta.isEmpty() && doingDelta.isEmpty()) {
            return;
        }
        Map<String, ObjectNode> anonDelta = new HashMap<>();
        for (Map.Entry<String, ObjectNode> e : authedDelta.entrySet()) {
            ObjectNode a = scoped(e.getValue().deepCopy(), false);
            if (a.size() > 1) {
                anonDelta.put(e.getKey(), a);
            }
        }
        interests.forEach((client, in) -> {
            if (!client.liveFrames()) {
                return;
            }
            Map<String, ObjectNode> watchedDelta = client.authed() ? authedDelta : anonDelta;
            List<ObjectNode> out = new ArrayList<>();
            for (String bot : in.watched()) {
                ObjectNode d = watchedDelta.get(bot);
                if (d != null) {
                    out.add(d);
                }
            }
            if (client.authed()) {
                for (String bot : in.listed()) {
                    ObjectNode d = doingDelta.get(bot);
                    if (d != null) {
                        out.add(d);
                    }
                }
            }
            if (!out.isEmpty()) {
                client.offer(frame(out));
            }
        });
    }

    private ObjectNode detail(BotLiveDto b) {
        ObjectNode n = entry(b.username());
        if (b.serverTick() != null) {
            n.put(TICK, b.serverTick());
        }
        if (b.description() != null) {
            n.put(DESCRIPTION, b.description());
        }
        if (b.dialog() != null && !b.dialog().isEmpty()) {
            n.set(DIALOG, mapper.valueToTree(b.dialog()));
        }
        if (b.fatiguePercent() != null) {
            n.put(FATIGUE, b.fatiguePercent());
        }
        if (b.windows() != null) {
            n.set(WINDOWS, mapper.valueToTree(b.windows()));
        }
        return n;
    }

    private @Nullable ObjectNode delta(String bot, @Nullable ObjectNode prev, ObjectNode cur) {
        if (prev == null) {
            return cur.size() > 1 ? cur.deepCopy() : null;
        }
        ObjectNode d = null;
        for (Map.Entry<String, JsonNode> e : cur.properties()) {
            if (!e.getValue().equals(prev.get(e.getKey()))) {
                d = d != null ? d : entry(bot);
                d.set(e.getKey(), e.getValue());
            }
        }
        for (String key : prev.propertyNames()) {
            if (!cur.has(key)) {
                d = d != null ? d : entry(bot);
                d.putNull(key);
            }
        }
        return d;
    }

    private static ObjectNode scoped(ObjectNode entry, boolean authed) {
        if (!authed) {
            entry.remove(AUTHED_ONLY);
        }
        return entry;
    }

    private ObjectNode entry(String bot) {
        return mapper.createObjectNode().put("username", bot);
    }

    private TextMessage frame(List<ObjectNode> entries) {
        return new TextMessage(mapper.writeValueAsString(Map.of("e", "bx", "d", entries)));
    }

    private Set<String> capped(Set<String> bots, int cap, StreamClient client) {
        Set<String> out = new HashSet<>();
        for (String bot : bots) {
            if (out.size() >= cap) {
                break;
            }
            if (client.authed() || visible.anonymous(bot)) {
                out.add(bot);
            }
        }
        return out;
    }

    static @Nullable String firstLine(@Nullable String description) {
        if (description == null) {
            return null;
        }
        int nl = description.indexOf('\n');
        return nl < 0 ? description : description.substring(0, nl);
    }

    /** Whether an anonymous viewer may see the bot named. */
    @FunctionalInterface
    interface Visibility {
        boolean anonymous(String bot);
    }
}
