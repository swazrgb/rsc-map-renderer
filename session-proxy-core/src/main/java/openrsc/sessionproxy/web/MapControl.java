package openrsc.sessionproxy.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import openrsc.bot.api.GameState;
import openrsc.bot.api.Packet;
import openrsc.bot.api.WindowMove;
import openrsc.bot.api.packets.ChatPacket;
import openrsc.bot.api.packets.CommandPacket;
import openrsc.bot.api.packets.PrivateMessagePacket;
import openrsc.gamedata.geometry.Point;
import openrsc.livemap.dto.WindowMoveDto;
import openrsc.livemap.stream.TopicException;
import openrsc.livemap.stream.TopicHub;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.planner.PathfinderPlans;
import openrsc.sessionproxy.ClientLogin;
import openrsc.sessionproxy.MapAction;
import openrsc.sessionproxy.Session;
import openrsc.sessionproxy.SessionListener;
import openrsc.sessionproxy.SessionProxy;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Playing the proxy's sessions from the live map: pausing every client but the map's own, walking the player to a tile,
 * taking an entry of the game's menus, banking, trading, duelling, chatting, and the {@code control} topic
 * ({@code user}) that shows the pause, the walk and the menu open.
 */
@RestController
final class MapControl implements SessionListener {

    static final String TOPIC = "control";

    private static final Pattern COMMAND = Pattern.compile("::\\S.*", Pattern.DOTALL);

    private final SessionProxy proxy;
    private final TopicHub topics;
    private final boolean browserClient;

    /** @param browserClient whether the map serves the browser game client */
    MapControl(SessionProxy proxy, TopicHub topics, boolean browserClient) {
        this.proxy = proxy;
        this.topics = topics;
        this.browserClient = browserClient;
        topics.polled(TOPIC, TopicHub.Access.ANYONE, TopicHub.BACKSTOP_MS, List.of("user"), q -> {
            Session s = session(q.require("user"));
            if (s == null) {
                throw new TopicException(404, null);
            }
            Session.Control c = s.control();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paused", c.pausedFromMap());
            if (c.walkingTo() != null) {
                out.put("walkingTo", tile(c.walkingTo()));
            }
            Session.WalkRoute route = c.walkRoute();
            if (route != null) {
                out.put(
                        "walkRoute",
                        Map.of(
                                "from",
                                tile(route.from()),
                                "plan",
                                route.plan(),
                                "path",
                                route.path().stream().map(MapControl::tile).toList()));
            }
            if (c.walkNote() != null) {
                out.put("walkNote", c.walkNote());
            }
            if (c.menu() != null) {
                out.put("menu", c.menu());
            }
            return out;
        });
    }

    /** What the map's page may offer: the browser game client. */
    @GetMapping("/api/proxy")
    Map<String, Boolean> features() {
        return Map.of("client", browserClient);
    }

    @PostMapping("/api/bots/{user}/pause")
    ResponseEntity<String> pause(@PathVariable String user) {
        return play(user, s -> {
            s.pauseFromMap(true);
            return null;
        });
    }

    @DeleteMapping("/api/bots/{user}/pause")
    ResponseEntity<String> resume(@PathVariable String user) {
        return play(user, s -> {
            s.pauseFromMap(false);
            return null;
        });
    }

    /** What a walk may carry: the policy it plans under, null for the account's default. */
    record WalkBody(@Nullable NavPolicy policy, PathfinderPlans.@Nullable LoadoutInput loadout) {}

    /**
     * Walks the player to tile ({@code x}, {@code z}), {@code z} counting the floors below it as the map does, under the
     * body's policy and to arrive with its loadout; without one, with whatever it carries. A policy it cannot walk
     * under is refused (400) with the reason.
     */
    @PostMapping("/api/bots/{user}/walk")
    ResponseEntity<String> walk(
            @PathVariable String user,
            @RequestParam int x,
            @RequestParam int z,
            @RequestBody(required = false) @Nullable WalkBody body) {
        NavPolicy policy = body == null ? null : body.policy();
        String refused = refusal(policy);
        if (refused != null) {
            return ResponseEntity.badRequest().body(refused);
        }
        LoadoutSpec loadout = PathfinderPlans.loadoutOf(body == null ? null : body.loadout());
        return play(user, s -> s.walkFromMap(Point.of(x, z), policy, loadout));
    }

    /**
     * Why a walk cannot plan under {@code policy}; null when it can. The planner must read it, or the walk fails on its
     * first step, and it must not open a gate the account cannot pass, which the walk would never get through.
     */
    private @Nullable String refusal(@Nullable NavPolicy policy) {
        if (policy == null) {
            return null;
        }
        if (policy.assumeAllEligible() || policy.costOverrides().containsValue(NavPolicy.LIFTED)) {
            return "a walk keeps the account's own gates";
        }
        try {
            proxy.pathfinder().nav().check(policy);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** What an action may carry: the policy the walk to its target plans under, null for the account's default. */
    record ActBody(@Nullable NavPolicy policy) {}

    /**
     * Takes an entry of the game's menus, {@code action} naming its verb in lower case; the other parameters are a
     * {@link MapAction}'s. An unknown verb, or a policy a walk cannot plan under, is refused (400).
     */
    @PostMapping("/api/bots/{user}/interact")
    ResponseEntity<String> interact(
            @PathVariable String user,
            @RequestParam String action,
            @RequestParam int x,
            @RequestParam int z,
            @RequestParam(defaultValue = "0") int id,
            @RequestParam(defaultValue = "0") int dir,
            @RequestParam(defaultValue = "0") int item,
            @RequestParam(defaultValue = "false") boolean noted,
            @RequestBody(required = false) @Nullable ActBody body) {
        MapAction.Verb verb = MapAction.Verb.named(action);
        if (verb == null) {
            return ResponseEntity.badRequest().body("no such action: " + action);
        }
        NavPolicy policy = body == null ? null : body.policy();
        String refused = refusal(policy);
        if (refused != null) {
            return ResponseEntity.badRequest().body(refused);
        }
        MapAction act = new MapAction(verb, x, z, id, dir, item, noted);
        return play(user, s -> {
            try {
                return s.actFromMap(act, policy);
            } catch (IOException e) {
                return "the server connection failed";
            }
        });
    }

    /** A line to say: in public chat, or privately to {@code to}. */
    record ChatBody(@Nullable String to, String message) {}

    /**
     * Says the body's line for the player, in public chat or privately; a public one of {@code ::} and a word is sent
     * as that command instead. An empty one is refused (400).
     */
    @PostMapping("/api/bots/{user}/chat")
    ResponseEntity<String> chat(@PathVariable String user, @RequestBody ChatBody body) {
        if (body.message() == null || body.message().isBlank()) {
            return ResponseEntity.badRequest().body("nothing to say");
        }
        String to = body.to() == null || body.to().isBlank() ? null : body.to().trim();
        String said = body.message().strip();
        Packet line = to != null
                ? new PrivateMessagePacket(to, body.message())
                : COMMAND.matcher(said).matches()
                        ? new CommandPacket(said.substring(2))
                        : new ChatPacket(body.message());
        return play(user, s -> {
            try {
                return s.sendFromMap(List.of(line));
            } catch (IOException e) {
                return "the server connection failed";
            }
        });
    }

    /**
     * Makes a move at the player's open bank ({@link WindowMove#bank}). With the bank shut it is refused (409), as is an
     * unequip of a slot nothing is worn in; an unknown action, or one the world lacks, such as a withdrawal as notes on a
     * world without them, is a 400.
     */
    @PostMapping("/api/bots/{user}/bank")
    ResponseEntity<String> bank(@PathVariable String user, @RequestBody WindowMoveDto.Bank body) {
        return move(user, body.move(proxy.serverData()), "bank");
    }

    /**
     * Offers, accepts the first screen of, confirms or declines the player's open trade. With none open it is refused
     * (409), as is an unknown action or an offer it cannot make (400).
     */
    @PostMapping("/api/bots/{user}/trade")
    ResponseEntity<String> trade(@PathVariable String user, @RequestBody WindowMoveDto.Trade body) {
        return move(user, body.move(proxy.serverData()), "trade");
    }

    /**
     * Stakes, sets the rules of, accepts the first screen of, confirms or declines the player's open duel. With none open
     * it is refused (409), as is an unknown action or a move it cannot make (400).
     */
    @PostMapping("/api/bots/{user}/duel")
    ResponseEntity<String> duel(@PathVariable String user, @RequestBody WindowMoveDto.Duel body) {
        return move(user, body.move(proxy.serverData()), "duel");
    }

    private ResponseEntity<String> move(String user, @Nullable WindowMove move, String screen) {
        if (move == null) {
            return ResponseEntity.badRequest().body("no such " + screen + " move");
        }
        return play(user, s -> {
            try {
                return s.moveFromMap(move);
            } catch (IOException e) {
                return "the server connection failed";
            }
        });
    }

    @DeleteMapping("/api/bots/{user}/walk")
    ResponseEntity<String> stopWalk(@PathVariable String user) {
        return play(user, s -> {
            try {
                s.stopWalkFromMap();
            } catch (IOException e) {
                return "the server connection failed";
            }
            return null;
        });
    }

    private ResponseEntity<String> play(String user, Function<Session, @Nullable String> change) {
        Session s = session(user);
        if (s == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(user + " is not online");
        }
        String refused = change.apply(s);
        return refused == null
                ? ResponseEntity.accepted().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).body(refused);
    }

    private static Map<String, Integer> tile(Point p) {
        return Map.of("x", p.x(), "z", p.y());
    }

    private @Nullable Session session(String user) {
        return proxy.session(ClientLogin.accountOf(user));
    }

    @Override
    public void ticked(Session session, GameState state) {
        touch(session);
    }

    @Override
    public void ended(Session session) {
        touch(session);
    }

    private void touch(Session session) {
        String account = ClientLogin.accountOf(session.username());
        topics.touch(TOPIC, q -> {
            String user = q.arg("user");
            return user != null && ClientLogin.accountOf(user).equals(account);
        });
    }
}
