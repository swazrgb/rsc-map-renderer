package openrsc.sessionproxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import openrsc.bot.api.Duel;
import openrsc.bot.api.GameState;
import openrsc.bot.api.MutableGameState;
import openrsc.bot.api.Packet;
import openrsc.bot.api.ServerMessage;
import openrsc.bot.api.SessionKey;
import openrsc.bot.api.WindowMove;
import openrsc.bot.api.codec.OpenRscCodec;
import openrsc.bot.api.packets.DuelOfferPacket;
import openrsc.bot.core.codec.RscPackets;
import openrsc.bot.core.liveview.LiveFrames;
import openrsc.bot.core.login.LoginResponse;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.bot.core.net.Connection;
import openrsc.bot.core.net.InboundEvent;
import openrsc.bot.core.net.TickArrival;
import openrsc.bot.core.session.LoginRejectedException;
import openrsc.bot.core.world.Opcode;
import openrsc.bot.core.world.PacketDispatcher;
import openrsc.bot.core.world.WorldSnapshotEncoder;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.WallOverlay;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.planner.PathfinderPlans;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One account's game session, held open upstream while any number of clients come and go.
 *
 * <p>Every packet the server sends goes to every attached client, and into the session's own copy of the game state. A
 * client that attaches to a running session gets that state as a snapshot at the next tick boundary, then the server's
 * packets from there on. What a client sends goes upstream, apart from what the proxy answers itself: heartbeats, a
 * logout while other clients stay attached, and {@code ::pause}, which blocks the other clients' input until the same
 * client sends it again or leaves.
 *
 * <p>The live map can pause every client but its own ({@link #pauseFromMap}), and walk the player to a tile
 * ({@link #walkFromMap}). {@code ::pause} in a client of the map's is the map's pause.
 *
 * <p>Thread-safe: one thread reads the server, each client has one reading it and one writing to it, and the live
 * map's walk has one of its own.
 */
public final class Session {

    private static final Logger LOG = LoggerFactory.getLogger(Session.class);

    private static final int SESSION_ID_READ_TIMEOUT_MS = 15_000;
    private static final int LOGIN_READ_TIMEOUT_MS = 45_000;
    private static final int STEADY_STATE_READ_TIMEOUT_MS = 15_000;
    private static final int SESSION_ID_BYTES = 4;
    private static final int LOGIN_RETRIES = 6;
    private static final int CODE_ALREADY_LOGGED_IN = 4;
    private static final long CLIENT_DRAIN_MILLIS = 2_000;

    private static final int TICK_BOUNDARY = Opcode.SEND_NPC_COORDS.wire();
    private static final int SERVER_CONFIGS = Opcode.SEND_SERVER_CONFIGS.wire();
    private static final int QUESTS = Opcode.SEND_QUESTS.wire();
    private static final int IGNORE_LIST = Opcode.SEND_IGNORE_LIST.wire();
    private static final int ONLINE_LIST = Opcode.SEND_ONLINE_LIST.wire();

    private static final int OPCODE_LOGIN = 0;
    private static final int OPCODE_CONFIGS_REQUEST = 19;
    private static final int OPCODE_CONFIRM_LOGOUT = 31;
    private static final int OPCODE_LOGOUT = Opcode.OPCODE_LOGOUT.wire();
    private static final int OPCODE_HEARTBEAT = Opcode.OPCODE_HEARTBEAT.wire();
    private static final int OPCODE_COMMAND = Opcode.OPCODE_COMMAND.wire();
    private static final String PAUSE_COMMAND = "pause";

    private static final String PAUSED_BY_CLIENT = "Your input is paused by another client.";
    private static final String PAUSED_FROM_MAP = "Your input is paused from the map.";

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1130-L1146
    private static final byte[] LOGOUT_CONFIRM = {(byte) Opcode.SEND_LOGOUT_REQUEST_CONFIRM.wire()};

    private final String username;
    private final BigInteger credential;
    private final int loginResponse;
    private final Connection upstream;
    private final @Nullable MutableGameState world;
    private final @Nullable Supplier<Pathfinder> pathfinder;
    private final @Nullable PacketDispatcher dispatcher;
    private final @Nullable WorldSnapshotEncoder encoder;
    /** The key the proxy hands clients when it writes its own logins, which every configs packet they get carries. */
    private final @Nullable ProxyKey ownLogins;

    private final List<SessionListener> listeners;
    private final WireTiming timing;
    private final AtomicBoolean dead = new AtomicBoolean();
    private volatile long lastProgressNanos = System.nanoTime();
    private volatile @Nullable Thread pump;
    private volatile @Nullable GameState lastTick;

    private final Object lock = new Object();
    // Taken around each packet that goes upstream for a client or the map's walk, so that one ending the walk is
    // never overtaken by the walk's last packet; taken before lock, never inside it.
    private final Object inputOrder = new Object();
    // The server drops an open menu on any packet but an answer, a chat or a setting, and says so only once the
    // plugin's menu loop next looks, a tick on; so a packet like that closes the session's own copy at once, before the
    // pump applies the next one it reads:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/PayloadProcessorManager.java#L175
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L364
    private final AtomicBoolean menuDropped = new AtomicBoolean();
    // The server never tells a player it accepted a trade's first screen, only that an accept was cancelled, so an
    // accept sent is the session's own to record, as the stock client does:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1745-L1754
    private final AtomicBoolean tradeAcceptSent = new AtomicBoolean();
    private final AtomicBoolean tradeOfferSent = new AtomicBoolean();
    // Nor does it send a player its own duel stake before the confirm screen, only the other's, so a stake sent is the
    // session's own to record too:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L345-L350
    private final AtomicReference<@Nullable List<Duel.StakeItem>> stakeSent = new AtomicReference<>();
    // The slot of a bank preset a save went out for, or -1: the server sends the presets only at login
    // (SentNotes#notePresetSaved):
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L2143-L2144
    private final AtomicInteger presetSaved = new AtomicInteger(-1);
    private final List<Client> clients = new ArrayList<>();
    private final HeldMessages heldMessages = new HeldMessages();
    private @Nullable Client pausedBy;
    private boolean pausedFromMap;
    private volatile @Nullable MapWalk walk;
    private @Nullable String walkNote;
    private long unattachedSinceNanos = System.nanoTime();
    private byte @Nullable [] serverConfigs;

    private Session(
            String username,
            BigInteger credential,
            int loginResponse,
            Connection upstream,
            @Nullable MutableGameState world,
            @Nullable Supplier<Pathfinder> pathfinder,
            @Nullable PacketDispatcher dispatcher,
            @Nullable WorldSnapshotEncoder encoder,
            List<SessionListener> listeners,
            @Nullable ProxyKey ownLogins) {
        this.username = username;
        this.credential = credential;
        this.loginResponse = loginResponse;
        this.upstream = upstream;
        this.world = world;
        this.pathfinder = pathfinder;
        this.dispatcher = dispatcher;
        this.encoder = encoder;
        this.listeners = listeners;
        this.ownLogins = ownLogins;
        this.timing = new WireTiming(username);
    }

    /**
     * Logs {@code login} in upstream and starts holding the session. {@code first}, when given, is started and attached
     * before the first packet arrives, so it sees the whole session from the login response on, as it would talking to
     * the server itself; when the login fails it is left unstarted. Logs in again while the server says the account is
     * still logged in, or while {@code upstream} asks to. {@code ownLogins}, when the proxy writes its own logins, is the
     * key its clients are handed, which every configs packet they get carries.
     *
     * @throws LoginRejectedException when the server, or {@code upstream}, refuses the login
     */
    static Session establish(
            ClientLogin login,
            Upstream upstream,
            String gameHost,
            int gamePort,
            PreLoginConfigs configs,
            ServerData serverData,
            Supplier<Pathfinder> pathfinder,
            ScheduledExecutorService heartbeats,
            List<SessionListener> listeners,
            @Nullable Client first,
            @Nullable ProxyKey ownLogins)
            throws IOException {
        String username = login.username();
        String account = login.account();
        Connection conn;
        int code;
        for (int attempt = 0; ; attempt++) {
            long dialStart = System.nanoTime();
            Socket socket = upstream.connect(account, gameHost, gamePort);
            long dialMs = (System.nanoTime() - dialStart) / 1_000_000;
            conn = null;
            try {
                conn = new Connection("proxy:" + username, socket);
                conn.setReadTimeout(SESSION_ID_READ_TIMEOUT_MS);
                long sessionIdStart = System.nanoTime();
                conn.readRawBytes(SESSION_ID_BYTES);
                long sessionIdMs = (System.nanoTime() - sessionIdStart) / 1_000_000;
                conn.send(Unpooled.wrappedBuffer(login.frame()));
                conn.setReadTimeout(LOGIN_READ_TIMEOUT_MS);
                long loginStart = System.nanoTime();
                code = conn.readRawByte();
                LOG.info(
                        "[{}] establish legs via {}: dial={}ms sessionId={}ms login={}ms → code={}",
                        username,
                        upstream.describe(account),
                        dialMs,
                        sessionIdMs,
                        (System.nanoTime() - loginStart) / 1_000_000,
                        code);
            } catch (IOException | RuntimeException e) {
                LOG.info(
                        "[{}] login via {} failed {}ms after the dial ({}ms): {}",
                        username,
                        upstream.describe(account),
                        (System.nanoTime() - dialStart) / 1_000_000 - dialMs,
                        dialMs,
                        e.toString());
                if (conn != null) {
                    conn.close("proxy login handshake failed", e);
                } else {
                    closeQuietly(socket);
                }
                upstream.answered(account, -1);
                throw e;
            }
            boolean again = upstream.answered(account, code);
            if (LoginResponse.of(code).isSuccess() || attempt + 1 >= LOGIN_RETRIES) {
                break;
            }
            if (code == CODE_ALREADY_LOGGED_IN) {
                // The server may still be logging out the session this one replaces.
                conn.close("proxy login retry on already-logged-in", null);
                sleep(Math.min(1000L, attempt == 0 ? 0L : 100L << Math.min(attempt - 1, 3)));
            } else if (again) {
                conn.close("proxy login retry on code " + code, null);
            } else {
                break;
            }
        }
        LoginResponse response = LoginResponse.of(code);
        if (!response.isSuccess()) {
            conn.close("proxy login failed code=" + code, null);
            throw new LoginRejectedException(username, code, response);
        }
        conn.setReadTimeout(STEADY_STATE_READ_TIMEOUT_MS);

        MutableGameState world =
                new MutableGameState(serverData, SessionKey.ofAccount(username), new WallOverlay(), null);
        world.observeServerConfigs(configs);
        PacketDispatcher dispatcher = new PacketDispatcher(
                "proxy:" + username, world, serverData.itemDefs(), serverData.objectDefs(), serverData.doorDefs());
        Session s = new Session(
                username,
                login.credential(configs.rsaExponent(), configs.rsaModulus()),
                code,
                conn,
                world,
                pathfinder,
                dispatcher,
                new WorldSnapshotEncoder(serverData.itemDefs()),
                listeners,
                ownLogins);
        if (first != null) {
            first.start();
            first.sendRaw(new byte[] {(byte) code});
            s.clients.add(first);
        }
        conn.startPacketReader();
        conn.startHeartbeat(heartbeats);
        s.pump = Thread.ofVirtual().name("proxy-pump-" + username).start(s::pump);
        LOG.info("[{}] established held session (login code={}) localPort={}", username, code, conn.localPort());
        return s;
    }

    static Session forTest(String username, Connection upstream) {
        return new Session(username, BigInteger.ONE, 0x40, upstream, null, null, null, null, List.of(), null);
    }

    /** Starts reading the server, as {@link #establish} does once the login is through. */
    void startPumpForTest() {
        upstream.startPacketReader();
        pump = Thread.ofVirtual().name("proxy-pump-" + username).start(this::pump);
    }

    public String username() {
        return username;
    }

    public boolean isDead() {
        return dead.get();
    }

    public long serverTick() {
        return world != null ? world.serverTick() : 0;
    }

    /** The response byte the server gave this session's login, which every client joining it is given too. */
    public int loginResponse() {
        return loginResponse;
    }

    /** Whether a login with this credential ({@link ClientLogin#credential}) carries the session's password. */
    public boolean accepts(BigInteger loginCredential) {
        return credential.equals(loginCredential);
    }

    /** A session in {@code /sessions}: who it is, whether clients are attached, and how its server link is doing. */
    public record Status(
            String username,
            long serverTick,
            boolean alive,
            int clients,
            boolean attached,
            long attachedMs,
            long unattachedMs,
            long sinceProgressMs,
            int localPort,
            TickArrival.Summary tickArrival) {}

    /**
     * The session's state now. {@code attachedMs} is the time since the newest client attached, and
     * {@code unattachedMs} the time since the last one left; whichever does not apply is {@code -1}.
     */
    public Status status() {
        long now = System.nanoTime();
        int count;
        long attachedMs;
        long unattachedMs;
        synchronized (lock) {
            count = clients.size();
            long newest =
                    clients.stream().mapToLong(Client::attachedNanos).max().orElse(0);
            attachedMs = count > 0 ? (now - newest) / 1_000_000 : -1;
            unattachedMs = count > 0 ? -1 : (now - unattachedSinceNanos) / 1_000_000;
        }
        return new Status(
                username,
                serverTick(),
                !dead.get(),
                count,
                count > 0,
                attachedMs,
                unattachedMs,
                (now - lastProgressNanos) / 1_000_000,
                upstream.localPort(),
                upstream.tickArrival());
    }

    /**
     * Attaches a client and relays what it sends until it leaves. {@code greeting} is written to it first, as it is;
     * the snapshot follows at the next tick boundary.
     *
     * @return false, without attaching, when the session has already died
     */
    public boolean serve(Socket socket, ClientWire wire, String label, byte[] greeting) throws IOException {
        return serve(ClientLink.of(socket), wire, label, greeting, false);
    }

    /** {@link #serve} for a client of the live map, whose input a pause from the map lets through. */
    public boolean serveFromMap(ClientLink link, ClientWire wire, String label, byte[] greeting) throws IOException {
        return serve(link, wire, label, greeting, true);
    }

    private boolean serve(ClientLink link, ClientWire wire, String label, byte[] greeting, boolean fromMap)
            throws IOException {
        Client client = attach(link, wire, label, greeting, fromMap);
        if (client == null) {
            return false;
        }
        relay(client);
        return true;
    }

    @Nullable
    Client attach(Socket socket, ClientWire wire, String label, byte[] greeting) {
        return attach(ClientLink.of(socket), wire, label, greeting, false);
    }

    private @Nullable Client attach(ClientLink link, ClientWire wire, String label, byte[] greeting, boolean fromMap) {
        synchronized (lock) {
            if (dead.get()) {
                return null;
            }
            Client client = new Client(link, wire, label, fromMap, true);
            client.start();
            client.sendRaw(greeting);
            if (pausedFromMap && !fromMap) {
                tellLocked(client, PAUSED_FROM_MAP);
            } else if (pausedBy != null) {
                tellLocked(client, PAUSED_BY_CLIENT);
            }
            clients.add(client);
            LOG.info("[{}] {} attached ({} client(s))", username, label, clients.size());
            return client;
        }
    }

    /** Disconnects every client attached under {@code label}. */
    public void evict(String label) {
        List<Client> evicted = new ArrayList<>();
        synchronized (lock) {
            for (Client c : List.copyOf(clients)) {
                if (c.label().equals(label)) {
                    clients.remove(c);
                    releasePauseLocked(c);
                    evicted.add(c);
                }
            }
            if (!evicted.isEmpty() && clients.isEmpty()) {
                unattachedSinceNanos = System.nanoTime();
            }
        }
        for (Client c : evicted) {
            LOG.info("[{}] {} evicted", username, c.label());
            c.close();
        }
    }

    /** Relays what {@code client} sends until it leaves, then detaches it. */
    void relay(Client client) throws IOException {
        try {
            byte[] body;
            while (!dead.get() && (body = client.wire().read()) != null) {
                if (!handleInput(client, body)) {
                    return;
                }
            }
        } finally {
            detach(client);
        }
    }

    private boolean handleInput(Client client, byte[] body) throws IOException {
        int opcode = body[0] & 0xFF;
        if (opcode == OPCODE_LOGIN || opcode == OPCODE_CONFIGS_REQUEST || opcode == OPCODE_HEARTBEAT) {
            return true;
        }
        if (opcode == OPCODE_LOGOUT || opcode == OPCODE_CONFIRM_LOGOUT) {
            return logout(client, body);
        }
        if (opcode == OPCODE_COMMAND && PAUSE_COMMAND.equalsIgnoreCase(command(body))) {
            togglePause(client);
            return true;
        }
        if (inputBlocked(client)) {
            return true;
        }
        synchronized (inputOrder) {
            endWalk(walk, "a client's input took over");
            forward(body);
        }
        return true;
    }

    // The last client's logout is the account's, so the server decides it, refusing it in combat; any other client
    // only leaves, given the confirmation the server sends a client it logs out.
    private boolean logout(Client client, byte[] body) throws IOException {
        synchronized (lock) {
            if (clients.size() > 1) {
                LOG.info("[{}] {} logged out; the session stays with the other clients", username, client.label());
                clients.remove(client);
                releasePauseLocked(client);
                client.send(LOGOUT_CONFIRM);
                client.finish();
                return false;
            }
        }
        forward(body);
        return true;
    }

    private void togglePause(Client client) {
        synchronized (lock) {
            if (client.fromMap()) {
                pauseFromMapLocked(!pausedFromMap);
            } else if (pausedFromMap) {
                tellLocked(client, PAUSED_FROM_MAP);
            } else if (pausedBy == null) {
                pausedBy = client;
                LOG.info("[{}] {} paused the other clients' input", username, client.label());
                endWalkLocked(walk, "a client paused input");
                tellLocked(client, "Other clients' input is paused. Type ::pause again to resume it.");
                for (Client other : clients) {
                    if (other != client) {
                        tellLocked(other, PAUSED_BY_CLIENT);
                    }
                }
            } else if (pausedBy == client) {
                releasePauseLocked(client);
            } else {
                tellLocked(client, PAUSED_BY_CLIENT);
            }
        }
    }

    private void releasePauseLocked(Client client) {
        if (pausedBy != client) {
            return;
        }
        pausedBy = null;
        LOG.info("[{}] input resumed", username);
        tellResumedLocked();
    }

    private void tellResumedLocked() {
        for (Client c : clients) {
            if (!inputBlockedLocked(c)) {
                tellLocked(c, "Input resumed.");
            }
        }
    }

    /**
     * What the live map has set on the session, and the menu it may answer.
     *
     * @param walkingTo the tile the map's walk is heading for; null when there is no walk
     * @param walkRoute the route the map's walk last planned; null when there is no walk, or before its first plan
     * @param walkNote how the last walk ended, until the next one starts; null while one runs, or before the first
     * @param menu the options of the conversation's open menu; null when none is open
     */
    public record Control(
            boolean pausedFromMap,
            @Nullable Point walkingTo,
            @Nullable WalkRoute walkRoute,
            @Nullable String walkNote,
            @Nullable List<String> menu) {}

    /**
     * The route the map's walk last planned: from the tile it planned on, the engine's own route, and the tiles of the
     * path it is walking.
     */
    public record WalkRoute(Point from, List<PathfinderPlans.PlanStep> plan, List<Point> path) {}

    public Control control() {
        synchronized (lock) {
            MapWalk w = walk;
            GameState now = lastTick;
            List<String> menu = now != null && now.dialog().menuOpen()
                    ? List.copyOf(now.dialog().options())
                    : null;
            return new Control(
                    pausedFromMap, w == null ? null : w.goal(), w == null ? null : w.planned(), walkNote, menu);
        }
    }

    /** Pauses the input of every client but the live map's, or resumes it. A client's {@code ::pause} is let go. */
    public void pauseFromMap(boolean on) {
        synchronized (lock) {
            pauseFromMapLocked(on);
        }
    }

    private void pauseFromMapLocked(boolean on) {
        if (pausedFromMap == on || dead.get()) {
            return;
        }
        pausedFromMap = on;
        if (on) {
            pausedBy = null;
        }
        LOG.info("[{}] the map {} the clients' input", username, on ? "paused" : "resumed");
        if (!on) {
            tellResumedLocked();
            return;
        }
        for (Client c : clients) {
            tellLocked(
                    c,
                    c.fromMap()
                            ? "Other clients' input is paused from the map. Type ::pause again to resume it."
                            : PAUSED_FROM_MAP);
        }
    }

    /**
     * Walks the player to {@code goal} for the live map, in place of any walk it is on, planning under {@code policy}
     * (null for the account's default) to arrive with {@code loadout}. A client's input ends the walk, as does a
     * {@code ::pause} from a client not of the map's; the walk's end is in {@link #control}. The first walk loads the
     * pathfinder.
     *
     * @return null when the walk starts; else why it cannot
     */
    public @Nullable String walkFromMap(Point goal, @Nullable NavPolicy policy, LoadoutSpec loadout) {
        return startWalk(goal, policy, loadout, null);
    }

    /**
     * Takes an entry of the game's menus for the live map: one {@link MapAction#inHand} at once, any other once a walk
     * under {@code policy} has brought its target within reach, in place of any walk the player is on.
     *
     * @return null when it is sent or under way; else why it cannot be
     */
    public @Nullable String actFromMap(MapAction action, @Nullable NavPolicy policy) throws IOException {
        if (!action.inHand()) {
            return startWalk(action.tile(), policy, PathfinderPlans.loadoutOf(null), action);
        }
        GameState now = lastTick;
        if (now == null) {
            return "the player is not in the world yet";
        }
        MapAction.Click click = action.click(now);
        if (click.packets() == null) {
            return click.missing();
        }
        String refused = sendFromMap(click.packets());
        if (refused == null) {
            LOG.info("[{}] the map sent {}", username, action);
        }
        return refused;
    }

    /**
     * Makes {@code move} for the live map at once, as {@link #sendFromMap} does, unless {@link WindowMove#refusal}
     * refuses it.
     *
     * @return null when it is sent; else why it cannot be
     */
    public @Nullable String moveFromMap(WindowMove move) throws IOException {
        GameState now = lastTick;
        String refused = now == null ? null : move.refusal(now);
        if (refused != null) {
            return refused;
        }
        return sendFromMap(List.of(move.packet()));
    }

    /**
     * Sends {@code packets} for the live map at once, as one of its clients would, leaving any walk the player is on.
     *
     * @return null when they are sent; else why they cannot be
     */
    public @Nullable String sendFromMap(List<? extends Packet> packets) throws IOException {
        if (world == null || lastTick == null) {
            return "the player is not in the world yet";
        }
        OpenRscCodec codec = new OpenRscCodec(world::wireLayout);
        synchronized (inputOrder) {
            synchronized (lock) {
                if (dead.get()) {
                    return "the session has ended";
                }
                if (pausedBy != null) {
                    return "a client has paused input";
                }
            }
            for (Packet p : packets) {
                send(codec.frame(p));
                if (p instanceof DuelOfferPacket stake) {
                    stakeSent.set(stake.offers().stream()
                            .map(o -> new Duel.StakeItem(o.itemId(), o.noted(), o.amount()))
                            .toList());
                }
            }
        }
        return null;
    }

    private @Nullable String startWalk(
            Point goal, @Nullable NavPolicy policy, LoadoutSpec loadout, @Nullable MapAction action) {
        GameState now = lastTick;
        if (pathfinder == null || world == null || now == null) {
            return "the player is not in the world yet";
        }
        MapWalk w = new MapWalk(pathfinder, goal, policy, loadout, action, world::wireLayout);
        w.offer(now);
        synchronized (lock) {
            if (dead.get()) {
                return "the session has ended";
            }
            if (pausedBy != null) {
                return "a client has paused input";
            }
            endWalkLocked(walk, null);
            walk = w;
            walkNote = null;
        }
        LOG.info(
                "[{}] the map walks the player from {} to {}{}",
                username,
                now.pos(),
                goal,
                action == null ? "" : " for " + action);
        Thread.ofVirtual().name("proxy-walk-" + username).start(() -> runWalk(w));
        return null;
    }

    /** Ends the live map's walk, if the player is on one, and stops the player where it stands. */
    public void stopWalkFromMap() throws IOException {
        synchronized (inputOrder) {
            MapWalk w = walk;
            GameState now = lastTick;
            if (w == null) {
                return;
            }
            endWalk(w, "stopped from the map");
            if (now != null) {
                for (ByteBuf frame : w.stay(now.pos())) {
                    send(frame);
                }
            }
        }
    }

    private void runWalk(MapWalk w) {
        try {
            GameState state;
            while ((state = w.await()) != null) {
                MapWalk.Step step = w.step(state);
                synchronized (inputOrder) {
                    if (walk != w) {
                        return;
                    }
                    for (ByteBuf frame : step.frames()) {
                        send(frame);
                    }
                }
                if (step.end() != null) {
                    LOG.info("[{}] the map's walk to {} ended: {}", username, w.goal(), step.end());
                    endWalk(w, step.end());
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            endWalk(w, "interrupted");
        } catch (IOException e) {
            endWalk(w, "the server connection failed");
        } catch (RuntimeException e) {
            LOG.warn("[{}] the map's walk to {} failed", username, w.goal(), e);
            endWalk(w, "the walk failed: " + e);
        }
    }

    private void endWalk(@Nullable MapWalk w, @Nullable String note) {
        if (w == null) {
            return;
        }
        synchronized (lock) {
            endWalkLocked(w, note);
        }
    }

    private void endWalkLocked(@Nullable MapWalk w, @Nullable String note) {
        if (w == null) {
            return;
        }
        w.stop();
        if (walk == w) {
            walk = null;
            walkNote = note;
        }
    }

    private void tellLocked(Client client, String text) {
        client.send(WorldSnapshotEncoder.serverMessageFrame(ServerMessage.plain(text)));
    }

    private boolean inputBlocked(Client client) {
        synchronized (lock) {
            return inputBlockedLocked(client);
        }
    }

    private boolean inputBlockedLocked(Client client) {
        return (pausedFromMap && !client.fromMap()) || (pausedBy != null && pausedBy != client);
    }

    private void forward(byte[] body) throws IOException {
        long sentNanos = System.nanoTime();
        upstream.send(Unpooled.wrappedBuffer(LiveFrames.clientFrame(body)));
        sent(body[0] & 0xFF);
        if (Opcode.outbound(body[0] & 0xFF) == Opcode.OPCODE_DUEL_OFFER) {
            stakeSent.set(stakeOf(body));
        }
        if (Opcode.outbound(body[0] & 0xFF) == Opcode.OPCODE_BANK_SAVE_PRESET && body.length >= 3) {
            presetSaved.set(((body[1] & 0xFF) << 8) | (body[2] & 0xFF));
        }
        timing.outbound(body[0] & 0xFF, body.length, sentNanos);
    }

    /** Sends one of the map's frames upstream. */
    private void send(ByteBuf frame) throws IOException {
        int opcode = RscPackets.opcodeOf(frame);
        if (Opcode.outbound(opcode) == Opcode.OPCODE_BANK_SAVE_PRESET && frame.writerIndex() >= 5) {
            presetSaved.set(frame.getUnsignedShort(3));
        }
        upstream.send(frame);
        sent(opcode);
    }

    private void sent(int opcode) {
        if (Opcode.cancelsOptionMenu(opcode)) {
            menuDropped.set(true);
        }
        if (Opcode.outbound(opcode) == Opcode.OPCODE_TRADE_ACCEPT_FIRST) {
            tradeAcceptSent.set(true);
        }
        if (Opcode.outbound(opcode) == Opcode.OPCODE_TRADE_OFFER) {
            tradeOfferSent.set(true);
        }
    }

    // A client's duel stake as the server reads it: a count, then each item's id, amount and whether it is noted:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/parsers/impl/PayloadCustomParser.java#L756-L769
    private static List<Duel.StakeItem> stakeOf(byte[] body) {
        ByteBuf in = Unpooled.wrappedBuffer(body, 1, body.length - 1);
        int count = in.isReadable() ? in.readByte() : 0;
        List<Duel.StakeItem> out = new ArrayList<>();
        for (int i = 0; i < count && in.readableBytes() >= 8; i++) {
            int id = in.readUnsignedShort();
            int amount = in.readInt();
            out.add(new Duel.StakeItem(id, in.readShort() == 1, amount));
        }
        return out;
    }

    // A client this session already let go of (logged out, or the session died) has its last bytes queued: they are
    // written before its connection closes.
    void detach(Client client) {
        boolean attached;
        synchronized (lock) {
            attached = clients.remove(client);
            if (attached) {
                LOG.info("[{}] {} detached ({} client(s) left)", username, client.label(), clients.size());
                releasePauseLocked(client);
                if (clients.isEmpty()) {
                    unattachedSinceNanos = System.nanoTime();
                }
            }
        }
        if (attached) {
            client.close();
        } else {
            client.awaitFinished(CLIENT_DRAIN_MILLIS);
        }
    }

    private void pump() {
        try {
            while (!dead.get()) {
                InboundEvent ev = upstream.inbound().take();
                lastProgressNanos = System.nanoTime();
                if (ev instanceof InboundEvent.Disconnected d) {
                    markDead(d.reason());
                    return;
                }
                InboundEvent.Packet p = (InboundEvent.Packet) ev;
                if (world != null && menuDropped.getAndSet(false)) {
                    world.dialog().menuClosed();
                }
                // An offer sent before an accept: the stock client clears both accepts as it sends an offer.
                if (world != null && tradeOfferSent.getAndSet(false)) {
                    world.noteTradeOfferSent();
                }
                if (world != null && tradeAcceptSent.getAndSet(false)) {
                    world.setTradeAccepted(true);
                }
                int saved = presetSaved.getAndSet(-1);
                if (world != null && saved >= 0) {
                    world.notePresetSaved(saved);
                }
                List<Duel.StakeItem> stake = stakeSent.getAndSet(null);
                if (world != null && stake != null) {
                    world.duel().myStake(stake);
                }
                byte[] body = bodyOf(p);
                timing.inbound(p.opcode());
                fanOut(p.opcode(), body);
                if (dispatcher != null) {
                    dispatcher.apply(p);
                }
                lastProgressNanos = System.nanoTime();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markDead("pump interrupted");
        } catch (Throwable t) {
            LOG.warn("[{}] pump threw", username, t);
            markDead("pump exception: " + t);
        } finally {
            markDead("pump exited");
        }
    }

    void fanOut(int opcode, byte[] body) {
        if (opcode == SERVER_CONFIGS) {
            // The stock client takes its login key from this one as well, for the next time it logs in.
            body = forClients(body);
            serverConfigs = body;
        }
        boolean boundary = opcode == TICK_BOUNDARY;
        GameState frozen = null;
        synchronized (lock) {
            if (clients.stream().allMatch(c -> c.joining)) {
                heldMessages.capture(opcode, body);
            }
            List<byte[]> joinFrames = null;
            for (Client c : clients) {
                if (!c.joining) {
                    c.send(body);
                    continue;
                }
                if (!boundary) {
                    continue;
                }
                if (joinFrames == null) {
                    frozen = freeze();
                    joinFrames = joinFrames(frozen);
                }
                for (byte[] f : joinFrames) {
                    c.send(f);
                }
                c.send(body);
                c.joining = false;
            }
            if (joinFrames != null) {
                List<byte[]> held = heldMessages.drain();
                for (Client c : clients) {
                    if (!c.joining) {
                        for (byte[] line : held) {
                            c.send(line);
                        }
                    }
                }
                if (!held.isEmpty()) {
                    LOG.info(
                            "[{}] replayed {} server line(s) heard while no client was watching",
                            username,
                            held.size());
                }
            }
        }
        if (boundary && (!listeners.isEmpty() || walk != null)) {
            if (frozen == null) {
                frozen = freeze();
            }
            if (frozen != null) {
                lastTick = frozen;
                MapWalk w = walk;
                if (w != null) {
                    w.offer(frozen);
                }
                for (SessionListener l : listeners) {
                    try {
                        l.ticked(this, frozen);
                    } catch (RuntimeException e) {
                        LOG.warn("[{}] a tick listener threw", username, e);
                    }
                }
            }
        }
    }

    /** A configs packet as the session's clients get it: with the proxy's key, when it writes its own logins. */
    private byte[] forClients(byte[] configs) {
        if (ownLogins == null) {
            return configs;
        }
        try {
            return OwnLogin.configsWithKey(configs, ownLogins);
        } catch (IOException e) {
            LOG.warn(
                    "[{}] a configs packet without a key goes to the clients as it came: {}", username, e.getMessage());
            return configs;
        }
    }

    /** The world as the tick just ended left it, once per tick; null before the server has placed the player. */
    private @Nullable GameState freeze() {
        return world == null || !world.hasPosition() ? null : world.freezeTick();
    }

    private List<byte[]> joinFrames(@Nullable GameState frozen) {
        if (world == null || encoder == null || frozen == null) {
            return List.of();
        }
        List<byte[]> out = new ArrayList<>();
        for (byte[] f : encoder.snapshotFrames(frozen)) {
            int opcode = f.length > 0 ? f[0] & 0xFF : -1;
            // A login sends no online list.
            if (opcode == IGNORE_LIST || opcode == ONLINE_LIST) {
                continue;
            }
            // The server's own configs packet over the re-encoded one, which keeps only the flags the state reads,
            // and the quests with their names, as a login lists them.
            byte[] frame = opcode == SERVER_CONFIGS
                    ? forClients(Objects.requireNonNullElse(serverConfigs, f))
                    : opcode == QUESTS ? Objects.requireNonNullElse(encoder.quests(frozen), f) : f;
            out.add(frame);
        }
        // A login ends with the ignore list, and Plutonium starts its script on it.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L2160-L2161
        out.add(encoder.ignoreList(frozen));
        LOG.info("[{}] {}-frame snapshot for joining client(s) at tick {}", username, out.size(), world.serverTick());
        return out;
    }

    /** Ends the session when the server has sent nothing for {@code maxStallNanos}. */
    boolean reapIfStalled(long maxStallNanos) {
        if (dead.get()) {
            return false;
        }
        long idle = System.nanoTime() - lastProgressNanos;
        if (idle < maxStallNanos) {
            return false;
        }
        long idleMs = TimeUnit.NANOSECONDS.toMillis(idle);
        LOG.warn("[{}] pump stalled {}ms; reaping wedged session", username, idleMs);
        markDead("pump stalled " + idleMs + "ms");
        return true;
    }

    /** Ends the session when no client has been attached for {@code maxIdleNanos}. */
    boolean reapIfUnattached(long maxIdleNanos) {
        synchronized (lock) {
            if (dead.get() || !clients.isEmpty()) {
                return false;
            }
            long idle = System.nanoTime() - unattachedSinceNanos;
            if (idle < maxIdleNanos) {
                return false;
            }
            long idleMs = TimeUnit.NANOSECONDS.toMillis(idle);
            LOG.info("[{}] no client attached for {}ms; releasing held session", username, idleMs);
            markDead("unattached " + idleMs + "ms");
        }
        return true;
    }

    void setLastProgressNanosForTest(long nanos) {
        this.lastProgressNanos = nanos;
    }

    void setUnattachedSinceNanosForTest(long nanos) {
        synchronized (lock) {
            this.unattachedSinceNanos = nanos;
        }
    }

    private void markDead(String reason) {
        List<Client> gone;
        // Dying and letting the clients go are one step under the lock: a relay that sees the session dead and detaches
        // its client must find it let go, or it closes the client and drops what is still queued for it.
        synchronized (lock) {
            if (!dead.compareAndSet(false, true)) {
                return;
            }
            gone = List.copyOf(clients);
            clients.clear();
            // What is queued still goes out, unless the client has stopped reading.
            gone.forEach(Client::finish);
            pausedBy = null;
            endWalkLocked(walk, "the session ended");
        }
        LOG.info("[{}] held session dead: {}", username, reason);
        for (Client c : gone) {
            Thread.ofVirtual().start(() -> c.awaitFinished(CLIENT_DRAIN_MILLIS));
        }
        upstream.close("held session dead: " + reason, null);
        for (SessionListener l : listeners) {
            try {
                l.ended(this);
            } catch (RuntimeException e) {
                LOG.warn("[{}] a session listener threw", username, e);
            }
        }
    }

    public void close() {
        markDead("closed");
        Thread t = pump;
        if (t != null) {
            t.interrupt();
        }
    }

    private static String command(byte[] body) {
        int end = body.length;
        if (end > 1 && body[end - 1] == '\n') {
            end--;
        }
        return new String(body, 1, end - 1, StandardCharsets.US_ASCII).trim();
    }

    /** Reads one frame a client sent: its opcode and payload, or null at the end of the stream. */
    public static byte @Nullable [] readFrame(DataInputStream in) throws IOException {
        int hi = in.read();
        if (hi < 0) {
            return null;
        }
        int lo = in.read();
        if (lo < 0) {
            return null;
        }
        int length = (hi << 8) | lo;
        if (length == 0) {
            throw new IOException("bad client frame length " + length);
        }
        byte[] body = new byte[length];
        in.readFully(body);
        return body;
    }

    private static byte[] bodyOf(InboundEvent.Packet p) {
        ByteBuf payload = p.payload();
        int n = payload.readableBytes();
        byte[] body = new byte[1 + n];
        body[0] = (byte) p.opcode();
        payload.getBytes(payload.readerIndex(), body, 1, n);
        return body;
    }

    private static void sleep(long ms) throws IOException {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during proxy login retry", ie);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The login failed already; that is what the caller hears.
        }
    }
}
