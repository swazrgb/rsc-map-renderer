package openrsc.sessionproxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.sessionproxy.authentic.AuthenticClients;
import openrsc.sessionproxy.web.ProxyWeb;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the session proxy on Uranium or Coleslaw, dialling the world's server directly. Its settings are read from
 * {@code session-proxy.yaml} in the folder it runs in, which is written with every setting at its default when it
 * does not exist; {@code --settings file} reads another. A command-line option overrides the file's setting of the
 * same name: {@code --world}, {@code --listen}, {@code --status}, {@code --hold}, {@code --web},
 * {@code --client-cache}, {@code --data} and {@code --client} ({@link ProxySettings}).
 *
 * <p>The world's data comes from the OpenRSC checkout {@link ServerConf#resolve} finds.
 */
public final class SessionProxyMain {

    static {
        // Before the first logger: the console config this jar ships, unless the user names one.
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "openrsc/sessionproxy/logback.xml");
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SessionProxyMain.class);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final String DEFAULT_CLIENT = "client";

    private SessionProxyMain() {}

    public static void main(String[] args) throws Exception {
        ProxySettings settings;
        World world;
        try {
            settings = settings(args);
            world = settings.requiredWorld();
        } catch (IllegalArgumentException e) {
            LOG.error("{}", e.getMessage());
            System.exit(2);
            return;
        }

        ServerConf conf = ServerConf.resolve();
        ProxyKey key;
        try {
            key = ProxyKey.load(settings.data());
        } catch (IOException e) {
            LOG.error("cannot read or write the proxy's key: {}", e.getMessage());
            System.exit(2);
            return;
        }
        ProxyConfig config = new ProxyConfig(settings.listen(), world, settings.hold()).writingOwnLogins(key);
        LOG.info(
                "Clients log in with the proxy's key, in {}; APOS needs it built in: modulus {}, exponent {}",
                settings.data().resolve(ProxyKey.FILE).toAbsolutePath(),
                key.modulus(),
                key.exponent());
        SessionProxy proxy = new SessionProxy(
                config,
                loadServerData(conf, world),
                Upstream.direct(CONNECT_TIMEOUT_MS),
                List.of(new AuthenticClients(key), new RscClients()));
        InetSocketAddress status = settings.status();
        StatusEndpoint endpoint = status == null ? null : StatusEndpoint.start(status, proxy::sessions);
        CountDownLatch stop = new CountDownLatch(1);
        ProxyWeb[] map = new ProxyWeb[1];
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            LOG.info("Shutting down session proxy ({} live sessions)", proxy.sessionCount());
                            if (endpoint != null) {
                                endpoint.close();
                            }
                            ProxyWeb page = map[0];
                            if (page != null) {
                                page.close();
                            }
                            proxy.close();
                            stop.countDown();
                        },
                        "proxy-shutdown"));
        proxy.start();
        InetSocketAddress web = settings.web();
        if (web != null && !web.getAddress().isLoopbackAddress()) {
            LOG.warn("The live map on {} has no login: anyone who can reach it sees and plays every account.", web);
        }
        if (web != null && !settings.hosts().isEmpty()) {
            LOG.warn(
                    "The live map answers to {} too, with no login: anyone who reaches it there plays every account.",
                    String.join(", ", settings.hosts()));
        }
        Path client = settings.client() != null ? settings.client() : defaultClient();
        if (client != null && !Files.isRegularFile(client.resolve("index.html"))) {
            LOG.warn("client: no index.html in {}, so the live map cannot open the game client", client);
        }
        if (web != null) {
            Path clientCache = settings.clientCache();
            map[0] = ProxyWeb.start(
                    proxy,
                    new ProxyWeb.Config(
                            web,
                            conf,
                            clientCache != null ? clientCache : clientCache(),
                            settings.data(),
                            client,
                            settings.hosts()));
            LOG.info(
                    "Live map on http://{}/",
                    HostNames.authority(new InetSocketAddress(web.getAddress(), map[0].port())));
        }
        stop.await();
    }

    /** The settings file, written with the defaults when missing, with the command line's options over it. */
    private static ProxySettings settings(String[] args) {
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException(args[args.length - 1] + " needs a value");
        }
        Path file = ProxySettings.DEFAULT_FILE;
        ProxySettings checked = ProxySettings.defaults();
        for (int i = 0; i < args.length; i += 2) {
            if (args[i].equals("--settings")) {
                file = Path.of(args[i + 1]);
            } else {
                // Checked before the file is read, or written: a mistyped option must not leave a file behind.
                checked = checked.withOption(args[i], args[i + 1]);
            }
        }
        boolean fresh = !Files.exists(file);
        ProxySettings settings;
        try {
            settings = ProxySettings.load(file);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "cannot read or write the settings file " + file.toAbsolutePath() + " ("
                            + e.getClass().getSimpleName() + ")",
                    e);
        }
        if (fresh) {
            LOG.info("Wrote the settings to {}: edit them there.", file.toAbsolutePath());
        }
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].equals("--settings")) {
                settings = settings.withOption(args[i], args[i + 1]);
            }
        }
        return settings;
    }

    /** The {@code client} folder in the working directory, as the release unpacks it; null without one. */
    private static @Nullable Path defaultClient() {
        Path here = Path.of(DEFAULT_CLIENT);
        return Files.isRegularFile(here.resolve("index.html")) ? here : null;
    }

    /** The checkout's client cache; without one the 3D world is not baked, and the map shows only the 2D one. */
    private static Path clientCache() {
        try {
            return ServerConf.clientCache();
        } catch (IllegalStateException e) {
            LOG.warn("{} The live map has no 3D world.", e.getMessage());
            return Path.of("Client_Base", "Cache");
        }
    }

    private static ServerData loadServerData(ServerConf conf, World world) throws IOException {
        WorldProfile profile = WorldProfile.fromConf(conf, world.conf());
        try (LandscapeSource landscape = profile.openLandscape(conf)) {
            return ServerData.load(conf, profile, landscape);
        }
    }
}
