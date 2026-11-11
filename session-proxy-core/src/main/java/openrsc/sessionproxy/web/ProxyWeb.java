package openrsc.sessionproxy.web;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.livemap.LiveMap;
import openrsc.livemap.SameOrigin;
import openrsc.livemap.assets.MapNav;
import openrsc.livemap.assets.WorldAssets;
import openrsc.livemap.auth.PasswordAuth;
import openrsc.livemap.live.BotLiveEncoder;
import openrsc.livemap.live.BotLiveStore;
import openrsc.livemap.panel.BotPanelTopic;
import openrsc.livemap.stream.StreamRegistry;
import openrsc.livemap.stream.TopicHub;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.transports.AllTransports;
import openrsc.sessionproxy.SessionProxy;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

/**
 * The live map of the proxy's sessions, served over HTTP: the 2D and 3D world, each session's bot and everything it
 * sees, the selected bot's panel, what the sessions hear ({@link ProxyChat}), and the items the bots online hold. It has no login: every viewer sees every session,
 * plays it ({@link MapControl}) and opens the browser game client on it. Only a page of the map's own may write to it
 * ({@link HostAndOrigin}).
 */
public final class ProxyWeb implements AutoCloseable {

    /**
     * @param listen where the page is served
     * @param conf the server data the world's landscape is read from
     * @param clientCache the OpenRSC client's cache, which the 3D world is baked from
     * @param bakeDir where the 3D world and the scenery atlas are baked, and kept across restarts
     * @param browserClient the browser game client's folder, served at {@code /client/}; null for none
     * @param hosts names the page also answers to besides {@code localhost} and IP addresses, in lower case
     */
    public record Config(
            InetSocketAddress listen,
            ServerConf conf,
            Path clientCache,
            Path bakeDir,
            @Nullable Path browserClient,
            Set<String> hosts) {}

    private final SessionProxy proxy;
    private final ProxyBots bots;
    private final MapControl control;
    private final ProxyChat chat;
    private final ConfigurableApplicationContext context;

    private ProxyWeb(
            SessionProxy proxy,
            ProxyBots bots,
            MapControl control,
            ProxyChat chat,
            ConfigurableApplicationContext context) {
        this.proxy = proxy;
        this.bots = bots;
        this.control = control;
        this.chat = chat;
        this.context = context;
    }

    /**
     * Starts serving the map of {@code proxy}'s sessions. It derives the world's transports, which the map draws, and
     * leaves the pathfinder unloaded until a walk, the route planner or the danger layer asks for it.
     */
    public static ProxyWeb start(SessionProxy proxy, Config config) {
        // The proxy configures its own logging; Spring Boot would reset it.
        System.setProperty("org.springframework.boot.logging.LoggingSystem", "none");
        SpringApplication app = new SpringApplication(App.class);
        app.setRegisterShutdownHook(false);
        app.addInitializers(ctx -> {
            var beans = ctx.getBeanFactory();
            beans.registerSingleton("serverData", proxy.serverData());
            beans.registerSingleton("mapNav", mapNav(proxy));
            beans.registerSingleton(
                    "worldAssets", new WorldAssets(config.clientCache(), config.bakeDir(), config.conf(), null));
            // The live map's login, with no password: every viewer is logged in.
            beans.registerSingleton("passwordAuth", new PasswordAuth("livemap_auth", "session-proxy", null));
            beans.registerSingleton("sessionProxy", proxy);
            beans.registerSingleton("proxyWebConfig", config);
        });
        ConfigurableApplicationContext context = app.run(
                "--server.address=" + config.listen().getAddress().getHostAddress(),
                "--server.port=" + config.listen().getPort(),
                "--spring.config.name=session-proxy",
                "--spring.main.banner-mode=off",
                "--spring.main.log-startup-info=false");
        ProxyBots bots = context.getBean(ProxyBots.class);
        MapControl control = context.getBean(MapControl.class);
        ProxyChat chat = context.getBean(ProxyChat.class);
        proxy.addListener(bots);
        proxy.addListener(control);
        proxy.addListener(chat);
        return new ProxyWeb(proxy, bots, control, chat, context);
    }

    private static MapNav mapNav(SessionProxy proxy) {
        List<Transport> transports = AllTransports.defaultRegistry(proxy.serverData());
        return new MapNav() {
            @Override
            public ServerData serverData() {
                return proxy.serverData();
            }

            @Override
            public List<Transport> transports() {
                return transports;
            }

            @Override
            public Pathfinder pathfinder() {
                return proxy.pathfinder();
            }
        };
    }

    public int port() {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @Override
    public void close() {
        proxy.removeListener(bots);
        proxy.removeListener(control);
        proxy.removeListener(chat);
        context.close();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LiveMap.class)
    static class App {

        @Bean
        ProxyBots proxyBots(
                ServerData data, BotLiveStore store, StreamRegistry registry, ObjectProvider<BotPanelTopic> panel) {
            return new ProxyBots(new BotLiveEncoder(data), store, registry, panel);
        }

        @Bean
        MapControl mapControl(SessionProxy proxy, TopicHub topics, Config config) {
            return new MapControl(proxy, topics, config.browserClient() != null);
        }

        @Bean
        ProxyChat proxyChat(StreamRegistry registry) {
            return new ProxyChat(registry);
        }

        @Bean
        HostAndOrigin hostAndOrigin(Config config) {
            return new HostAndOrigin(config.hosts());
        }

        @Bean
        WebSocketConfigurer browserClientSocket(SessionProxy proxy) {
            BrowserClients clients = new BrowserClients(proxy);
            return registry -> registry.addHandler(clients, "/api/client")
                    .setAllowedOriginPatterns("*")
                    .addInterceptors(new SameOrigin());
        }

        @Bean
        WebMvcConfigurer browserClientFiles(Config config) {
            return new WebMvcConfigurer() {
                @Override
                public void addResourceHandlers(ResourceHandlerRegistry registry) {
                    Path dir = config.browserClient();
                    if (dir != null) {
                        String uri = dir.toAbsolutePath().toUri().toString();
                        registry.addResourceHandler("/client/**")
                                .addResourceLocations(uri.endsWith("/") ? uri : uri + "/");
                    }
                }
            };
        }
    }
}
