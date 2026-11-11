package openrsc.sessionproxy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import openrsc.bot.core.login.PreLoginConfigs;
import openrsc.bot.core.net.PreLoginConfigsClient;
import openrsc.bot.core.session.LoginRejectedException;
import openrsc.gamedata.ServerData;
import openrsc.nav.Pathfinder;
import openrsc.nav.cache.NavCaches;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds game sessions open on one server and lets clients attach to them: several at once, and again after a
 * disconnect, without the server seeing a new login.
 *
 * <p>A session is one account's connection to the server, opened by the first client that logs in to it and kept for
 * {@link ProxyConfig#idleHold} after its last client leaves. Every connection is handed to the first of the
 * {@link ClientProtocol}s that recognizes how it starts.
 */
public final class SessionProxy implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SessionProxy.class);

    private static final long PUMP_STALL_LIMIT_MS = 15_000;
    private static final long WATCHDOG_INTERVAL_MS = 500;
    private static final int HEARTBEAT_THREADS = 4;
    private static final int CONFIG_FETCH_TIMEOUT_MS = 15_000;
    private static final int LOGIN_CODE_CLIENT_UPDATED = 5;
    private static final int GREETING_TIMEOUT_MS = 30_000;
    private static final int CODE_INVALID_CREDENTIALS = 3;
    private static final int CODE_LOGIN_ATTEMPTS_EXCEEDED = 7;

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L437
    private static final int SESSION_ID_DELAY_MS = 640;

    private final ProxyConfig config;
    private final ServerData serverData;
    private final Upstream upstream;
    private final List<ClientProtocol> protocols;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<Session>> opening = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(
            HEARTBEAT_THREADS, Thread.ofVirtual().name("proxy-heartbeat-", 0).factory());
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("proxy-watchdog").factory());
    private final SecureRandom sessionIds = new SecureRandom();
    private final PasswordGuesses guesses = new PasswordGuesses();
    private final Object configLock = new Object();

    private volatile PreLoginConfigsClient.@Nullable Result serverConfigs;
    private volatile @Nullable Pathfinder pathfinder;
    private volatile @Nullable ServerSocket serverSocket;
    private volatile @Nullable ScheduledFuture<?> watchdogTask;
    private volatile boolean stopped;

    /**
     * @param upstream opens every connection to the game server
     * @param protocols tried in order on each new connection
     */
    public SessionProxy(ProxyConfig config, ServerData serverData, Upstream upstream, List<ClientProtocol> protocols) {
        this.config = config;
        this.serverData = serverData;
        this.upstream = upstream;
        this.protocols = List.copyOf(protocols);
    }

    public void start() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(config.listen());
        this.serverSocket = ss;
        Thread.ofVirtual().name("proxy-accept").start(() -> acceptLoop(ss));
        this.watchdogTask = watchdog.scheduleAtFixedRate(
                this::runWatchdog, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info(
                "Session proxy listening on {} → game {}:{} (idle hold {})",
                ss.getLocalSocketAddress(),
                config.server().host(),
                config.server().port(),
                config.idleHold().isZero() ? "forever" : config.idleHold().toSeconds() + "s");
    }

    /** Has {@code listener} hear every session's ticks from now on, those already held included. */
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    /** Stops {@code listener} hearing the sessions' ticks; one already being delivered may still reach it. */
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public int boundPort() {
        ServerSocket ss = serverSocket;
        return ss != null ? ss.getLocalPort() : config.listen().getPort();
    }

    public ServerData serverData() {
        return serverData;
    }

    public List<Session.Status> sessions() {
        List<Session.Status> out = new ArrayList<>(sessions.size());
        sessions.values().forEach(s -> out.add(s.status()));
        return out;
    }

    public int sessionCount() {
        return (int) sessions.values().stream().filter(s -> !s.isDead()).count();
    }

    /** The live session of {@code account} ({@link ClientLogin#account}), if there is one. */
    public @Nullable Session session(String account) {
        Session s = sessions.get(account);
        return s == null || s.isDead() ? null : s;
    }

    /**
     * Attaches the client that sent {@code login} to its account's session, opening the session with that login when
     * there is none, and relays what the client sends until it leaves. A client joining a session must carry the
     * session's password; one that does not is sent the code the server would send it, and let go.
     *
     * @param label what the log names the client by
     */
    public void admit(ClientLogin login, Socket socket, ClientWire wire, String label) throws IOException {
        PreLoginConfigs keys = serverConfigs().configs();
        BigInteger credential = login.credential(keys.rsaExponent(), keys.rsaModulus());
        while (true) {
            Session live = session(login.account());
            if (live != null) {
                if (guesses.exhausted(socket.getInetAddress())) {
                    LOG.info("[{}] refused: too many wrong passwords from its address", label);
                    writeRaw(socket, CODE_LOGIN_ATTEMPTS_EXCEEDED);
                    return;
                }
                if (!live.accepts(credential)) {
                    LOG.info("[{}] refused: its password is not the session's", label);
                    guesses.wrong(socket.getInetAddress());
                    writeRaw(socket, CODE_INVALID_CREDENTIALS);
                    return;
                }
                if (live.serve(socket, wire, label, new byte[] {(byte) live.loginResponse()})) {
                    return;
                }
                continue;
            }
            if (awaitOpening(login.account())) {
                continue;
            }
            Client first = new Client(ClientLink.of(socket), wire, label, false, false);
            Session opened;
            try {
                opened = open(login, first);
            } catch (LoginRejectedException e) {
                LOG.info("[{}] the server refused the login: code {}", label, e.code());
                writeRaw(socket, e.code());
                return;
            }
            if (opened != null) {
                opened.relay(first);
                return;
            }
        }
    }

    /**
     * Joins the client that sent {@code login} to its account's held session, whatever password it carries: the live
     * map let its viewer in. With no session held, the client is sent the code for a wrong password, and let go.
     *
     * @param label what the log names the client by
     */
    public void admitFromMap(ClientLogin login, ClientLink link, ClientWire wire, String label) throws IOException {
        while (true) {
            Session live = session(login.account());
            if (live == null) {
                if (awaitOpening(login.account())) {
                    continue;
                }
                LOG.info("[{}] refused: no session to join", label);
                OutputStream out = link.output();
                out.write(CODE_INVALID_CREDENTIALS);
                out.flush();
                return;
            }
            if (live.serveFromMap(link, wire, label, new byte[] {(byte) live.loginResponse()})) {
                return;
            }
        }
    }

    private static void writeRaw(Socket socket, int code) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(code);
        out.flush();
    }

    /**
     * Opens a session for {@code login}, with no client attached yet.
     *
     * @return null when the account already has a session, or one is being opened
     * @throws LoginRejectedException when the server, or the upstream, refuses the login
     */
    public @Nullable Session open(ClientLogin login) throws IOException {
        return open(login, null);
    }

    @Nullable
    Session open(ClientLogin login, @Nullable Client first) throws IOException {
        String account = login.account();
        CompletableFuture<Session> mine = new CompletableFuture<>();
        if (opening.putIfAbsent(account, mine) != null) {
            return null;
        }
        try {
            if (session(account) != null) {
                mine.cancel(false);
                return null;
            }
            Session stale = sessions.remove(account);
            if (stale != null) {
                stale.close();
            }
            Session fresh = Session.establish(
                    login,
                    upstream,
                    config.server().host(),
                    config.server().port(),
                    serverConfigs().configs(),
                    serverData,
                    this::pathfinder,
                    heartbeats,
                    listeners,
                    first,
                    config.ownLogins());
            sessions.put(account, fresh);
            mine.complete(fresh);
            LOG.info("[{}] opened by {} via {}", login.username(), login.describe(), upstream.describe(account));
            return fresh;
        } catch (LoginRejectedException e) {
            if (e.code() == LOGIN_CODE_CLIENT_UPDATED) {
                serverConfigs = null;
                LOG.info("[{}] CLIENT_UPDATED: dropped the cached server configs", login.username());
            }
            mine.completeExceptionally(e);
            throw e;
        } catch (IOException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            if (!mine.isDone()) {
                mine.completeExceptionally(new IllegalStateException("opening " + account + " failed"));
            }
            opening.remove(account, mine);
        }
    }

    /**
     * Waits while a session for {@code account} is being opened.
     *
     * @return whether one was being opened, whatever came of it
     */
    public boolean awaitOpening(String account) {
        CompletableFuture<Session> pending = opening.get(account);
        if (pending == null) {
            return false;
        }
        try {
            pending.join();
        } catch (CompletionException | CancellationException ignored) {
            // The opener reports its own failure; the caller looks again either way.
        }
        return true;
    }

    /**
     * The configs packet the proxy answers a client's configs request with, its opcode and payload: the server's, with
     * the proxy's key in it when the proxy writes its own logins ({@link ProxyConfig#writingOwnLogins}).
     *
     * @throws IOException as {@link #serverConfigs} does
     */
    public byte[] clientConfigs() throws IOException {
        ProxyKey key = config.ownLogins();
        byte[] server = serverConfigs().body();
        return key == null ? server : OwnLogin.configsWithKey(server, key);
    }

    /**
     * The login the proxy sends upstream for {@code client}: its own when it writes its own ({@link OwnLogin}), else
     * the client's. Null when the client did not encrypt its password with the key it was handed.
     *
     * @throws IOException as {@link #serverConfigs} does
     */
    public @Nullable ClientLogin upstreamLogin(ClientLogin client) throws IOException {
        ProxyKey key = config.ownLogins();
        return key == null ? client : OwnLogin.of(client, key, serverConfigs().configs());
    }

    /**
     * The configs packet the server answers a client with before it logs in, fetched once and kept until the server
     * reports the client out of date.
     *
     * @throws IOException when the server cannot be reached, or is not the world's: its name or login key differs
     */
    public PreLoginConfigsClient.Result serverConfigs() throws IOException {
        PreLoginConfigsClient.Result cached = serverConfigs;
        if (cached != null) {
            return cached;
        }
        synchronized (configLock) {
            cached = serverConfigs;
            if (cached == null) {
                cached = PreLoginConfigsClient.fetchTimed(
                        (host, port) -> upstream.connect(null, host, port),
                        config.server().host(),
                        config.server().port(),
                        CONFIG_FETCH_TIMEOUT_MS);
                try {
                    config.server().check(cached.configs());
                } catch (IOException e) {
                    LOG.error("refusing the game server: {}", e.getMessage());
                    throw e;
                }
                serverConfigs = cached;
                LOG.info(
                        "cached pre-login configs from {}:{} (server \"{}\")",
                        config.server().host(),
                        config.server().port(),
                        cached.configs().serverName());
            }
            return cached;
        }
    }

    /**
     * The world's pathfinder, loaded on first use: the live map's first walk or plan, or its danger layer. It keeps no
     * caches, so every plan runs in full.
     */
    public Pathfinder pathfinder() {
        Pathfinder p = pathfinder;
        if (p != null) {
            return p;
        }
        synchronized (this) {
            p = pathfinder;
            if (p == null) {
                p = Pathfinder.load(serverData, NavCaches.none());
                pathfinder = p;
            }
            return p;
        }
    }

    boolean pathfinderLoaded() {
        return pathfinder != null;
    }

    private void runWatchdog() {
        long stallNanos = TimeUnit.MILLISECONDS.toNanos(PUMP_STALL_LIMIT_MS);
        long holdNanos = config.idleHold().toNanos();
        sessions.forEach((account, session) -> {
            try {
                if (!session.reapIfStalled(stallNanos) && holdNanos > 0) {
                    session.reapIfUnattached(holdNanos);
                }
            } catch (RuntimeException e) {
                LOG.warn("[{}] watchdog reap failed", account, e);
            }
            if (session.isDead()) {
                sessions.remove(account, session);
            }
        });
    }

    private void acceptLoop(ServerSocket ss) {
        while (!stopped) {
            Socket socket;
            try {
                socket = ss.accept();
            } catch (IOException e) {
                if (!stopped) {
                    LOG.warn("session proxy accept failed", e);
                }
                return;
            }
            sockets.add(socket);
            Thread.ofVirtual()
                    .name("proxy-conn-" + socket.getRemoteSocketAddress())
                    .start(() -> {
                        try {
                            socket.setTcpNoDelay(true);
                            handle(socket);
                        } catch (IOException e) {
                            LOG.debug("session proxy connection IO: {}", e.toString());
                        } catch (RuntimeException e) {
                            LOG.warn("session proxy connection threw", e);
                        } finally {
                            sockets.remove(socket);
                            closeQuietly(socket);
                        }
                    });
        }
    }

    private void handle(Socket socket) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(socket.getInputStream(), ClientProtocol.HEAD_BYTES);
        byte[] head = readHead(socket, pushback);
        if (head.length == 0) {
            return;
        }
        DataInputStream in = new DataInputStream(pushback);
        for (ClientProtocol protocol : protocols) {
            if (protocol.recognizes(head)) {
                protocol.serve(socket, in, this);
                return;
            }
        }
        LOG.info(
                "refused {}: no client protocol starts with {}",
                socket.getRemoteSocketAddress(),
                HexFormat.of().formatHex(head));
    }

    // The server sends a 4-byte session id to a client that has not spoken first within its timer: Plutonium and
    // PreLoginConfigsClient wait for it, the stock client sends without waiting and never gets one.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/RSCSessionIdSender.java#L26-L39
    private byte[] readHead(Socket socket, PushbackInputStream in) throws IOException {
        socket.setSoTimeout(SESSION_ID_DELAY_MS);
        int first;
        try {
            first = in.read();
        } catch (SocketTimeoutException e) {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(sessionIds.nextInt(Integer.MAX_VALUE));
            out.flush();
            socket.setSoTimeout(GREETING_TIMEOUT_MS);
            first = in.read();
        }
        if (first < 0) {
            return new byte[0];
        }
        socket.setSoTimeout(GREETING_TIMEOUT_MS);
        byte[] head = new byte[ClientProtocol.HEAD_BYTES];
        head[0] = (byte) first;
        int n = 1;
        while (n < head.length) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head[n++] = (byte) b;
        }
        in.unread(head, 0, n);
        socket.setSoTimeout(0);
        return Arrays.copyOf(head, n);
    }

    @Override
    public void close() {
        stopped = true;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            closeQuietly(ss);
        }
        for (Socket s : sockets) {
            closeQuietly(s);
        }
        sockets.clear();
        for (Session s : sessions.values()) {
            s.close();
        }
        sessions.clear();
        ScheduledFuture<?> wd = watchdogTask;
        if (wd != null) {
            wd.cancel(false);
        }
        watchdog.shutdownNow();
        heartbeats.shutdownNow();
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // Closing on the way out: nothing left to tell anyone.
        }
    }
}
