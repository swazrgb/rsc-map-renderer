package openrsc.livemap.live;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import openrsc.livemap.stream.StreamClient;
import openrsc.livemap.stream.StreamRegistry;
import openrsc.livemap.stream.TopicHub;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The map's stream socket. A viewer is seeded when it connects, then sends ops as {@code {"op": …}}: {@code watch}
 * (the bot it follows and the bots it lists, for their detail), and {@code sub}, {@code unsub} and {@code refresh} for
 * the {@link TopicHub}'s topics; any other op goes to the application's {@link StreamOps}. The handshake puts whether
 * the viewer is logged in under {@link #AUTHED_ATTR} and its address under {@link #PEER_ATTR}; an address gets a few
 * anonymous sockets at once.
 */
@Component
public class StreamHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(StreamHandler.class);

    public static final String AUTHED_ATTR = "livemap.ws.authed";
    public static final String PEER_ATTR = "livemap.ws.peer";

    private static final int MAX_ANONYMOUS_PER_PEER = 8;
    private static final CloseStatus TOO_MANY = CloseStatus.POLICY_VIOLATION.withReason("too many connections");

    private final StreamRegistry registry;
    private final LiveSeeder seeder;
    private final TopicHub topics;
    private final DetailFanout detail;
    private final ObjectProvider<StreamOps> ops;
    private final ObjectMapper mapper;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();

    private static final class Conn {
        final StreamClient client;
        volatile @Nullable String watchBot;

        Conn(StreamClient client) {
            this.client = client;
        }
    }

    public StreamHandler(
            StreamRegistry registry,
            LiveSeeder seeder,
            TopicHub topics,
            DetailFanout detail,
            ObjectProvider<StreamOps> ops,
            ObjectMapper mapper) {
        this.registry = registry;
        this.seeder = seeder;
        this.topics = topics;
        this.detail = detail;
        this.ops = ops;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        boolean authed = Boolean.TRUE.equals(session.getAttributes().get(AUTHED_ATTR));
        String peer = String.valueOf(session.getAttributes().get(PEER_ATTR));
        if (!authed && anonymousFrom(peer) >= MAX_ANONYMOUS_PER_PEER) {
            LOG.info(
                    "map ws REFUSED {}: {} anonymous connections from {}",
                    session.getId(),
                    MAX_ANONYMOUS_PER_PEER,
                    peer);
            session.close(TOO_MANY);
            return;
        }
        StreamClient client = new StreamClient(session, authed, peer);
        conns.put(session.getId(), new Conn(client));
        registry.register(client);
        seeder.seed(client, authed);
        LOG.info("map ws OPEN {} ({})", session.getId(), authed ? "authed" : "anonymous");
    }

    private int anonymousFrom(String peer) {
        int n = 0;
        for (Conn c : conns.values()) {
            if (!c.client.authed() && c.client.peer().equals(peer)) {
                n++;
            }
        }
        return n;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Conn conn = conns.get(session.getId());
        if (conn == null) {
            return;
        }
        try {
            JsonNode msg = mapper.readTree(message.getPayload());
            String op = msg.path("op").asString("");
            switch (op) {
                case "watch" -> {
                    String previous = conn.watchBot;
                    conn.watchBot = msg.hasNonNull("bot") ? msg.get("bot").asString() : null;
                    StreamOps app = ops.getIfAvailable();
                    if (app != null) {
                        app.watching(conn.client, previous, conn.watchBot);
                    }
                    Set<String> watched = new LinkedHashSet<>();
                    if (conn.watchBot != null) {
                        watched.add(conn.watchBot);
                    }
                    watched.addAll(names(msg.path("bots"), DetailFanout.MAX_WATCHED));
                    detail.interest(conn.client, watched, names(msg.path("lines"), DetailFanout.MAX_LISTED));
                }
                case "sub" ->
                    topics.subscribe(
                            conn.client,
                            msg.path("id").asInt(),
                            msg.path("topic").asString(""),
                            args(msg.path("args")));
                case "unsub" -> topics.unsubscribe(conn.client, msg.path("id").asInt());
                case "refresh" -> topics.refresh(conn.client, msg.path("id").asInt());
                default -> {
                    StreamOps app = ops.getIfAvailable();
                    if (app == null || !app.handle(conn.client, op, msg)) {
                        LOG.debug("map ws {}: unknown op '{}'", session.getId(), op);
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("map ws {}: bad message: {}", session.getId(), e.toString());
        }
    }

    private static Set<String> names(JsonNode node, int cap) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode n : node) {
            if (out.size() >= cap) {
                break;
            }
            if (n.isString()) {
                out.add(n.asString());
            }
        }
        return out;
    }

    private static Map<String, String> args(JsonNode node) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getValue().isValueNode() && !e.getValue().isNull()) {
                out.put(e.getKey(), e.getValue().asString());
            }
        }
        return out;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Conn conn = conns.remove(session.getId());
        if (conn != null) {
            conn.client.close();
            LOG.info("map ws CLOSED {} ({})", session.getId(), status.getCode());
            StreamOps app = ops.getIfAvailable();
            if (app != null) {
                app.closed(conn.client, conn.watchBot);
            }
            detail.forget(conn.client);
            topics.forget(conn.client);
            registry.forget(conn.client);
        }
    }
}
