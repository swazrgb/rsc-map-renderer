package openrsc.livemap.auth;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class AuthFilterServletTest {

    private static final String PASSWORD = "hunter2";
    private static final String COOKIE = "map_auth";
    private static final String RAN = "HANDLER-RAN:";

    private static ConfigurableApplicationContext context;
    private static int port;
    private static String token;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AuthFilter.class, Handlers.class})
    static class App {
        @Bean
        PasswordAuth passwordAuth() {
            return new PasswordAuth(COOKIE, "map-auth-v1", PASSWORD);
        }

        @Bean
        AuthPolicy authPolicy() {
            return new AuthPolicy(Set.of("/api/kv", "/api/playback"));
        }
    }

    @RestController
    static class Handlers {
        @GetMapping("/api/kv/namespaces")
        String kvNamespaces() {
            return RAN + "kv";
        }

        @GetMapping("/api/playback/{sessionId}/trail")
        String playbackTrail(@PathVariable String sessionId) {
            return RAN + "trail " + sessionId;
        }

        @PostMapping("/api/bots/{user}/walk")
        String walk(@PathVariable String user) {
            return RAN + "walk " + user;
        }

        @GetMapping("/api/bots/{user}")
        String bot(@PathVariable String user) {
            return RAN + "bot " + user;
        }

        @GetMapping("/api/items/defs")
        String itemDefs() {
            return RAN + "defs";
        }

        @PostMapping("/api/auth/login")
        String login() {
            return RAN + "login";
        }

        @RequestMapping("/api/any")
        String anyMethod() {
            return RAN + "any";
        }
    }

    @BeforeAll
    static void boot() {
        context = start();
        port = port(context);
        token = context.getBean(PasswordAuth.class).sessionToken();
    }

    private static ConfigurableApplicationContext start(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.name=auth-filter-servlet-test",
                "--server.address=" + InetAddress.getLoopbackAddress().getHostAddress(),
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.main.log-startup-info=false"));
        args.addAll(List.of(extraArgs));
        return new SpringApplication(App.class).run(args.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
    }

    @AfterAll
    static void shutdown() {
        if (context != null) {
            context.close();
        }
    }

    enum Kind {
        CANONICAL,
        PARAMS,
        ENCODED_LETTER,
        DOUBLE_SLASH,
        DOT_SEGMENT,
        CASE,
        ENCODED_SLASH,
        TRAILING_SLASH
    }

    record Variant(Kind kind, String target) {}

    static List<Variant> variants(String path) {
        List<String> seg = List.of(path.substring(1).split("/"));
        List<Variant> out = new ArrayList<>();
        out.add(new Variant(Kind.CANONICAL, path));
        for (int i = 0; i < seg.size(); i++) {
            out.add(new Variant(Kind.PARAMS, with(seg, i, seg.get(i) + ";x")));
            String s = seg.get(i);
            String encoded = "%" + HexFormat.of().withUpperCase().toHexDigits((byte) s.charAt(0)) + s.substring(1);
            out.add(new Variant(Kind.ENCODED_LETTER, with(seg, i, encoded)));
            String upper = s.toUpperCase(Locale.ROOT);
            if (!upper.equals(s)) {
                out.add(new Variant(Kind.CASE, with(seg, i, upper)));
            }
        }
        out.add(new Variant(Kind.CASE, path.toUpperCase(Locale.ROOT)));
        for (int i = 0; i < seg.size(); i++) {
            out.add(new Variant(Kind.DOUBLE_SLASH, before(seg, i, "/")));
            out.add(new Variant(Kind.DOT_SEGMENT, before(seg, i, "./")));
            out.add(new Variant(Kind.DOT_SEGMENT, before(seg, i, "x/../")));
            out.add(new Variant(Kind.DOT_SEGMENT, before(seg, i, "%2e/")));
            out.add(new Variant(Kind.DOT_SEGMENT, before(seg, i, "x/%2E%2E/")));
            out.add(new Variant(Kind.DOT_SEGMENT, before(seg, i, "x/..;y/")));
            if (i > 0) {
                out.add(new Variant(
                        Kind.ENCODED_SLASH,
                        "/" + String.join("/", seg.subList(0, i)) + "%2F"
                                + String.join("/", seg.subList(i, seg.size()))));
            }
        }
        out.add(new Variant(Kind.DOT_SEGMENT, path + "/."));
        out.add(new Variant(Kind.DOT_SEGMENT, path + "/x/.."));
        out.add(new Variant(Kind.TRAILING_SLASH, path + "/"));
        return out;
    }

    private static String with(List<String> seg, int i, String replacement) {
        List<String> copy = new ArrayList<>(seg);
        copy.set(i, replacement);
        return "/" + String.join("/", copy);
    }

    private static String before(List<String> seg, int i, String insert) {
        return "/" + String.join("/", seg.subList(0, i)) + (i > 0 ? "/" : "") + insert
                + String.join("/", seg.subList(i, seg.size()));
    }

    record Response(String method, String target, int status, boolean ran) {
        @Override
        public String toString() {
            return method + " " + target + " -> " + status + (ran ? " (handler ran)" : "");
        }
    }

    static Response send(String method, String target, boolean authed) {
        return send(port, method, target, authed);
    }

    static Response send(int port, String method, String target, boolean authed) {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10_000);
            String request = method + " " + target + " HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: 0\r\n"
                    + (authed ? "Cookie: " + COOKIE + "=" + token + "\r\n" : "")
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(ISO_8859_1));
            String response = new String(socket.getInputStream().readAllBytes(), ISO_8859_1);
            Response r =
                    new Response(method, target, Integer.parseInt(response.substring(9, 12)), response.contains(RAN));
            System.out.println((authed ? "authed " : "anon   ") + r);
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void anonymous(String method, String path, Expectation expect) {
        List<Executable> checks = new ArrayList<>();
        for (Variant v : variants(path)) {
            Response r = send(method, v.target(), false);
            checks.add(() -> expect.check(v.kind(), r));
        }
        assertAll(method + " " + path, checks);
    }

    interface Expectation {
        void check(Kind kind, Response r);
    }

    private static final Expectation REFUSED = (kind, r) -> {
        assertFalse(r.ran(), () -> "anonymous request reached the handler: " + r);
        switch (kind) {
            case CANONICAL, PARAMS, ENCODED_LETTER, TRAILING_SLASH -> assertEquals(401, r.status(), r::toString);
            case DOUBLE_SLASH, DOT_SEGMENT -> assertEquals(400, r.status(), r::toString);
            case ENCODED_SLASH -> assertEquals(400, r.status(), r::toString);
            case CASE -> assertTrue(r.status() == 401 || r.status() == 404, r::toString);
        }
    };

    private static final String VARIABLE_ONLY_CASE = "/api/bots/ALICE";

    private static final Expectation PUBLIC = (kind, r) -> {
        switch (kind) {
            case CANONICAL, PARAMS, ENCODED_LETTER -> assertTrue(r.status() == 200 && r.ran(), r::toString);
            case DOUBLE_SLASH, DOT_SEGMENT -> assertEquals(400, r.status(), r::toString);
            case ENCODED_SLASH -> assertEquals(400, r.status(), r::toString);
            case CASE, TRAILING_SLASH -> assertEquals(VARIABLE_ONLY_CASE.equals(r.target()), r.ran(), r::toString);
        }
    };

    @Test
    void anAuthedReadIsRefusedHoweverItIsSpelled() {
        anonymous("GET", "/api/kv/namespaces", REFUSED);
        anonymous("GET", "/api/playback/s1/trail", REFUSED);
    }

    @Test
    void aWriteIsRefusedHoweverItIsSpelled() {
        anonymous("POST", "/api/bots/alice/walk", REFUSED);
    }

    @Test
    void aPublicEndpointStaysReachable() {
        anonymous("GET", "/api/items/defs", PUBLIC);
        anonymous("GET", "/api/bots/alice", PUBLIC);
        anonymous("POST", "/api/auth/login", PUBLIC);
    }

    @Test
    void aDotSegmentSpringBindsAsAVariableCannotEscapeAPrivilegedPrefix() {
        for (String target : List.of("/api/playback/../trail", "/api/playback/%2e%2e/trail")) {
            Response r = send("GET", target, false);
            assertFalse(r.ran(), r::toString);
            assertEquals(400, r.status(), r::toString);
        }
    }

    @Test
    void aNonCanonicalRequestOutsideApiIsNotSkipped() {
        Response r = send("GET", "/api/../kv", false);
        assertEquals(400, r.status(), r::toString);
    }

    @Test
    void anAuthedSessionIsServedOnEverySpellingSpringRoutes() {
        for (String target : List.of("/api/kv/namespaces", "/api/kv;x/namespaces", "/%61pi/%6Bv/namespaces")) {
            Response r = send("GET", target, true);
            assertTrue(r.status() == 200 && r.ran(), r::toString);
        }
        Response write = send("POST", "/%61pi/bots/alice;x/walk", true);
        assertTrue(write.status() == 200 && write.ran(), write::toString);
    }

    @Test
    void onlyGetHeadAndOptionsAreReads() {
        for (String method : List.of("POST", "PUT", "post", "Get", "PROPFIND", "FOO")) {
            Response r = send(method, "/api/any", false);
            assertFalse(r.ran(), r::toString);
            assertEquals(401, r.status(), r::toString);
        }
        Response get = send("GET", "/api/any", false);
        assertTrue(get.status() == 200 && get.ran(), get::toString);
        for (String method : List.of("HEAD", "OPTIONS")) {
            Response r = send(method, "/api/any", false);
            assertEquals(200, r.status(), r::toString);
        }
        Response authed = send("PROPFIND", "/api/any", true);
        assertTrue(authed.status() == 200 && authed.ran(), authed::toString);
    }

    @Test
    void aServletPathPrefixIsStrippedBeforeTheRulesApply() {
        try (ConfigurableApplicationContext prefixed = start("--spring.mvc.servlet.path=/x")) {
            int p = port(prefixed);
            for (String target : List.of("/x/api/kv/namespaces", "/x/%61pi/kv/namespaces", "/x/api/kv;y/namespaces")) {
                Response r = send(p, "GET", target, false);
                assertFalse(r.ran(), r::toString);
                assertEquals(401, r.status(), r::toString);
            }
            Response write = send(p, "POST", "/x/api/bots/alice/walk", false);
            assertFalse(write.ran(), write::toString);
            assertEquals(401, write.status(), write::toString);
            for (String target :
                    List.of("/%78/api/kv/namespaces", "/x/../x/api/kv/namespaces", "/x/api/../api/kv/namespaces")) {
                Response r = send(p, "GET", target, false);
                assertFalse(r.ran(), r::toString);
                assertEquals(400, r.status(), r::toString);
            }
            Response open = send(p, "GET", "/x/api/items/defs", false);
            assertTrue(open.status() == 200 && open.ran(), open::toString);
            Response authed = send(p, "GET", "/x/api/kv/namespaces", true);
            assertTrue(authed.status() == 200 && authed.ran(), authed::toString);
        }
    }
}
