package openrsc.sessionproxy.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

class HostAndOriginTest {

    private static final String JSON = "application/json";

    @Test
    void readsAnswerLocalhostAndAddressesOnly() {
        assertNull(HostAndOrigin.refusal(Set.of(), "GET", "127.0.0.1:43602", null, null));
        assertNull(HostAndOrigin.refusal(Set.of(), "GET", "localhost:43602", null, null));
        assertNull(HostAndOrigin.refusal(Set.of(), "GET", "[::1]:43602", null, null));
        assertNull(
                HostAndOrigin.refusal(Set.of(), "GET", "192.168.1.5:43602", null, null),
                "another machine on the network");
        assertNotNull(HostAndOrigin.refusal(Set.of(), "GET", "evil.example:43602", null, null), "a rebound domain");
        assertNotNull(HostAndOrigin.refusal(Set.of(), "GET", "evil.localhost:43602", null, null));
        assertNotNull(HostAndOrigin.refusal(Set.of(), "GET", null, null, null));
    }

    @Test
    void writesComeFromTheMapsOwnPagesAsJson() {
        assertNull(HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", "http://127.0.0.1:43602", JSON));
        assertNull(HostAndOrigin.refusal(Set.of(), "DELETE", "localhost:43602", "http://localhost:43602", JSON));
        assertNull(HostAndOrigin.refusal(
                Set.of(), "POST", "127.0.0.1:43602", "http://127.0.0.1:43602", JSON + ";charset=UTF-8"));
        assertNotNull(
                HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", "https://evil.example", JSON),
                "another site's page");
        assertNotNull(
                HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", "http://127.0.0.1:43602", "text/plain"),
                "a body a page of any site may send");
        assertNotNull(HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", "http://127.0.0.1:43602", null));
        assertNotNull(
                HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", "http://127.0.0.1:43602", "not a type"));
    }

    @Test
    void aWriteFromNoPageCarriesJsonToo() {
        assertNull(HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", null, JSON));
        assertNotNull(
                HostAndOrigin.refusal(Set.of(), "POST", "127.0.0.1:43602", null, "application/x-www-form-urlencoded"),
                "a form a browser posts without an Origin");
        assertNotNull(HostAndOrigin.refusal(Set.of(), "POST", "evil.example:43602", null, JSON), "never to a domain");
    }

    @Test
    void aNameInHostsIsAnsweredAsTheMapsOwnSite() {
        Set<String> hosts = Set.of("map.example.com");
        assertNull(HostAndOrigin.refusal(hosts, "GET", "map.example.com", null, null), "behind a reverse proxy");
        assertNull(HostAndOrigin.refusal(hosts, "GET", "MAP.example.com:443", null, null));
        assertNull(HostAndOrigin.refusal(hosts, "POST", "map.example.com", "https://map.example.com", JSON));
        assertNotNull(HostAndOrigin.refusal(hosts, "GET", "evil.example", null, null), "a name not listed");
        assertNotNull(HostAndOrigin.refusal(hosts, "GET", "evil.map.example.com", null, null));
        assertNotNull(
                HostAndOrigin.refusal(hosts, "POST", "map.example.com", "https://evil.example", JSON),
                "another site's page");
    }
}
