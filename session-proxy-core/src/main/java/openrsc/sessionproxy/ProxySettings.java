package openrsc.sessionproxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The proxy's settings, as its settings file holds them, each overridable by the command-line option of the same
 * name ({@code --client-cache} for {@code clientCache}).
 *
 * @param world the world the proxy holds sessions on; null until set
 * @param listen where clients connect
 * @param status where {@code GET /sessions} is served; null for nowhere
 * @param hold how long a session stays logged in after its last client leaves; zero keeps it until the server ends it
 * @param web where the live map is served; null for nowhere
 * @param hosts names the live map also answers to besides {@code localhost} and IP addresses, in lower case: those a
 *     reverse proxy in front serves it under
 * @param clientCache the OpenRSC client's cache, which the 3D world is baked from; null for the checkout's
 * @param data where the 3D world is baked to, and kept across restarts
 * @param client the browser game client's folder, which the live map opens on a bot; null for the {@code client}
 *     folder in the working directory, if there is one
 */
public record ProxySettings(
        @Nullable World world,
        InetSocketAddress listen,
        @Nullable InetSocketAddress status,
        Duration hold,
        @Nullable InetSocketAddress web,
        Set<String> hosts,
        @Nullable Path clientCache,
        Path data,
        @Nullable Path client) {

    public ProxySettings {
        hosts = Set.copyOf(hosts);
    }

    /** The settings file in the folder the proxy runs in. */
    public static final Path DEFAULT_FILE = Path.of("session-proxy.yaml");

    private static final String TEMPLATE = "session-proxy.yaml";

    private static final Map<String, String> OPTIONS = Map.of(
            "--world", "world",
            "--listen", "listen",
            "--status", "status",
            "--hold", "hold",
            "--web", "web",
            "--hosts", "hosts",
            "--client-cache", "clientCache",
            "--data", "data",
            "--client", "client");

    private static final Pattern HOST_NAME =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** The file as YAML gives it: every value a string, so that a port, a word and a path all read the same. */
    private record Raw(
            @Nullable String world,
            @Nullable String listen,
            @Nullable String status,
            @Nullable String hold,
            @Nullable String web,
            @Nullable String hosts,
            // Read only to refuse a password that is set: the live map has no login.
            @Nullable String password,
            @Nullable String clientCache,
            @Nullable String data,
            @Nullable String client) {}

    /**
     * The settings in {@code file}; a missing file is first written with every setting at its default and a comment
     * on each, for the user to edit. A setting left out of the file keeps its default.
     *
     * @throws IllegalArgumentException when the file names a setting there is none of, or a value does not parse
     */
    public static ProxySettings load(Path file) throws IOException {
        if (!Files.exists(file)) {
            try (InputStream template = ProxySettings.class.getResourceAsStream(TEMPLATE)) {
                Files.copy(Objects.requireNonNull(template, TEMPLATE), file);
            }
        }
        String text = Files.readString(file);
        // YAML reads a file of comments only as no settings at all, which Jackson refuses to map.
        if (text.lines().map(String::strip).allMatch(l -> l.isEmpty() || l.startsWith("#") || l.equals("---"))) {
            return defaults();
        }
        Raw raw;
        try {
            raw = YAML.readValue(text, Raw.class);
        } catch (UnrecognizedPropertyException e) {
            String hint =
                    switch (e.getPropertyName()) {
                        case "server" ->
                            ": the proxy runs only on Uranium and Coleslaw, so set world (uranium or coleslaw) instead";
                        default -> "";
                    };
            throw new IllegalArgumentException(where(file, e) + "there is no setting " + e.getPropertyName() + hint, e);
        } catch (MismatchedInputException e) {
            String setting =
                    e.getPath().isEmpty() ? null : e.getPath().getLast().getPropertyName();
            throw new IllegalArgumentException(
                    where(file, e)
                            + (setting == null
                                    ? "write each setting on a line of its own, as name: value"
                                    : setting + " needs one value, on its own line"),
                    e);
        } catch (JacksonException e) {
            // In double quotes YAML reads a backslash as an escape, so a Windows path pasted with them breaks.
            String hint = e.getOriginalMessage().contains("escape") ? " (write a Windows path without quotes)" : "";
            throw new IllegalArgumentException(where(file, e) + e.getOriginalMessage() + hint, e);
        }
        if (raw != null && given(raw.password())) {
            throw new IllegalArgumentException(file + ": the live map has no login, so remove password");
        }
        return raw == null ? defaults() : defaults().with(raw);
    }

    /** Every setting at its default; the world unset. */
    public static ProxySettings defaults() {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        return new ProxySettings(
                null,
                new InetSocketAddress(loopback, 43600),
                new InetSocketAddress(loopback, 43601),
                Duration.ofSeconds(30),
                new InetSocketAddress(loopback, 43602),
                Set.of(),
                null,
                Path.of("data"),
                null);
    }

    /**
     * These settings with one command-line option applied: {@code --world}, {@code --listen}, {@code --status},
     * {@code --hold}, {@code --web}, {@code --hosts}, {@code --client-cache}, {@code --data} or {@code --client}.
     *
     * @throws IllegalArgumentException for any other option, or a value that does not parse
     */
    public ProxySettings withOption(String option, String value) {
        String key = OPTIONS.get(option);
        if (option.equals("--server")) {
            throw new IllegalArgumentException(
                    "the proxy runs only on Uranium and Coleslaw: give --world uranium or --world coleslaw instead");
        }
        if (key == null) {
            throw new IllegalArgumentException("unknown option " + option);
        }
        return with(YAML.convertValue(Map.of(key, value), Raw.class));
    }

    private static String where(Path file, JacksonException e) {
        TokenStreamLocation at = e.getLocation();
        return at == null || at.getLineNr() < 1 ? file + ": " : file + " line " + at.getLineNr() + ": ";
    }

    /** These settings with every value {@code raw} gives over them. */
    private ProxySettings with(Raw raw) {
        return new ProxySettings(
                given(raw.world()) ? World.named(raw.world()) : world,
                given(raw.listen()) ? address("listen", raw.listen()) : listen,
                given(raw.status()) ? addressOrOff("status", raw.status()) : status,
                given(raw.hold()) ? Duration.ofSeconds(seconds(raw.hold())) : hold,
                given(raw.web()) ? addressOrOff("web", raw.web()) : web,
                given(raw.hosts()) ? hostNames(raw.hosts()) : hosts,
                given(raw.clientCache()) ? path("clientCache", raw.clientCache()) : clientCache,
                given(raw.data()) ? path("data", raw.data()) : data,
                given(raw.client()) ? path("client", raw.client()) : client);
    }

    /**
     * The world the proxy holds sessions on.
     *
     * @throws IllegalArgumentException when none is set
     */
    public World requiredWorld() {
        if (world == null) {
            throw new IllegalArgumentException("no world: set world (uranium or coleslaw) in the settings file");
        }
        return world;
    }

    private static boolean given(@Nullable String value) {
        return value != null && !value.isBlank();
    }

    private static @Nullable InetSocketAddress addressOrOff(String setting, String spec) {
        String word = spec.strip().toLowerCase(Locale.ROOT);
        return word.equals("off") || word.equals("false") ? null : address(setting, spec);
    }

    private static InetSocketAddress address(String setting, String spec) {
        String s = spec.strip();
        int colon = s.lastIndexOf(':');
        return colon < 0
                ? new InetSocketAddress(InetAddress.getLoopbackAddress(), port(setting, s, -1))
                : new InetSocketAddress(s.substring(0, colon), port(setting, s, colon));
    }

    private static int port(String setting, String spec, int colon) {
        long port = number(setting, spec.substring(colon + 1));
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException(setting + ": no port " + port + " in " + spec);
        }
        return (int) port;
    }

    // Names alone, as a Host header gives them: a scheme, a port or a path never matches one.
    private static Set<String> hostNames(String value) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : value.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            if (name.isEmpty()) {
                continue;
            }
            if (!HOST_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("hosts: write each name alone, as map.example.com, not " + name);
            }
            out.add(name);
        }
        return out;
    }

    private static Path path(String setting, String value) {
        String s = value.strip();
        // A path in double quotes has had its backslashes read as escapes, \n a line break among them.
        if (s.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(setting + ": write the path without quotes");
        }
        try {
            return Path.of(s);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(setting + ": " + e.getMessage(), e);
        }
    }

    private static long seconds(String value) {
        long s = number("hold", value);
        if (s < 0) {
            throw new IllegalArgumentException("hold needs 0 or more seconds, got " + value);
        }
        return s;
    }

    private static long number(String setting, String value) {
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(setting + " needs a number, got " + value, e);
        }
    }
}
