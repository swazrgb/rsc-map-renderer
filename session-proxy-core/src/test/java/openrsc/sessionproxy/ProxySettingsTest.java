package openrsc.sessionproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxySettingsTest {

    @TempDir
    Path dir;

    @Test
    void aMissingFileIsWrittenAndReadsAsTheDefaults() throws Exception {
        Path file = dir.resolve("session-proxy.yaml");

        ProxySettings settings = ProxySettings.load(file);

        assertTrue(Files.exists(file), "written for the user to edit");
        assertEquals(ProxySettings.defaults(), settings);
        assertTrue(Files.readString(file).contains("# The world"), "with a comment on each setting");
    }

    @Test
    void theFileSetsWhatItNamesAndLeavesTheRestAtTheirDefaults() throws Exception {
        Path file = write("""
                world: Coleslaw
                web: 0.0.0.0:8080
                hold: 0
                """);

        ProxySettings settings = ProxySettings.load(file);

        assertEquals(World.COLESLAW, settings.requiredWorld());
        assertEquals(new InetSocketAddress("0.0.0.0", 8080), settings.web());
        assertEquals(Duration.ZERO, settings.hold());
        assertEquals(ProxySettings.defaults().listen(), settings.listen());
    }

    @Test
    void aWindowsPathReadsAsTyped() throws Exception {
        Path file = write("clientCache: C:\\Users\\me\\OpenRSC\\Cache\n");

        assertEquals(
                Path.of("C:\\Users\\me\\OpenRSC\\Cache"),
                ProxySettings.load(file).clientCache());
    }

    @Test
    void offTurnsTheStatusAndTheMapOff() throws Exception {
        Path file = write("status: off\nweb: off\n");

        ProxySettings settings = ProxySettings.load(file);

        assertNull(settings.status());
        assertNull(settings.web());
    }

    @Test
    void aBarePortListensOnThisMachineOnly() throws Exception {
        Path file = write("listen: 5000\n");

        assertEquals(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5000),
                ProxySettings.load(file).listen());
    }

    @Test
    void anOptionOverridesTheFile() throws Exception {
        Path file = write("web: 127.0.0.1:9000\nclientCache: cache\nclient: browser\n");

        ProxySettings settings = ProxySettings.load(file)
                .withOption("--web", "off")
                .withOption("--client-cache", "other")
                .withOption("--world", "uranium")
                .withOption("--client", "C:\\games\\client");

        assertNull(settings.web());
        assertEquals(Path.of("other"), settings.clientCache());
        assertEquals(World.URANIUM, settings.requiredWorld());
        assertEquals(Path.of("C:\\games\\client"), settings.client());
        assertNull(ProxySettings.defaults().client(), "no game client unless one is set");
    }

    @Test
    void hostsAreNamesAloneInLowerCase() throws Exception {
        ProxySettings settings = ProxySettings.load(write("hosts: Map.Example.com, other.example\n"));
        assertEquals(Set.of("map.example.com", "other.example"), settings.hosts());
        assertEquals(Set.of(), ProxySettings.defaults().hosts(), "none unless named");
        assertEquals(
                Set.of("map.example.com"),
                ProxySettings.defaults()
                        .withOption("--hosts", "map.example.com")
                        .hosts());

        IllegalArgumentException url = assertThrows(
                IllegalArgumentException.class, () -> ProxySettings.load(write("hosts: https://map.example.com\n")));
        assertTrue(url.getMessage().contains("write each name alone"), url.getMessage());
    }

    @Test
    void aMisspelledSettingIsRefusedByName() throws Exception {
        Path file = write("sever: game.example.org:43594\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProxySettings.load(file));

        assertTrue(e.getMessage().contains("sever"), e.getMessage());
    }

    @Test
    void noWorldSaysWhereToSetIt() throws Exception {
        ProxySettings settings = ProxySettings.load(dir.resolve("session-proxy.yaml"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, settings::requiredWorld);

        assertTrue(e.getMessage().contains("settings file"), e.getMessage());
    }

    @Test
    void onlyUraniumAndColeslawAreWorlds() throws Exception {
        IllegalArgumentException cabbage =
                assertThrows(IllegalArgumentException.class, () -> ProxySettings.load(write("world: cabbage\n")));
        assertTrue(cabbage.getMessage().contains("uranium or coleslaw"), cabbage.getMessage());

        IllegalArgumentException server = assertThrows(
                IllegalArgumentException.class, () -> ProxySettings.load(write("server: game.example.org:43594\n")));
        assertTrue(server.getMessage().contains("set world"), server.getMessage());

        IllegalArgumentException option = assertThrows(
                IllegalArgumentException.class,
                () -> ProxySettings.defaults().withOption("--server", "game.example.org:43594"));
        assertTrue(option.getMessage().contains("--world"), option.getMessage());
    }

    @Test
    void aFileOfCommentsOnlyReadsAsTheDefaults() throws Exception {
        assertEquals(ProxySettings.defaults(), ProxySettings.load(write("# world: uranium\n")));
        assertEquals(ProxySettings.defaults(), ProxySettings.load(write("")));
    }

    @Test
    void aQuotedWindowsPathSaysToLeaveTheQuotesOut() throws Exception {
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class,
                () -> ProxySettings.load(write("clientCache: \"C:\\Users\\me\\Cache\"\n")));
        assertTrue(refused.getMessage().contains("without quotes"), refused.getMessage());

        // In double quotes \n reads as a line break, and parses: the path itself is refused.
        IllegalArgumentException mangled = assertThrows(
                IllegalArgumentException.class, () -> ProxySettings.load(write("data: \"D:\\new\\bots\"\n")));
        assertTrue(mangled.getMessage().startsWith("data: write the path without quotes"), mangled.getMessage());
    }

    @Test
    void aValueOfTheWrongShapeNamesItsSettingAndLine() throws Exception {
        Path file = write("world: uranium\nlisten:\n  port: 5000\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProxySettings.load(file));

        assertTrue(e.getMessage().contains("line "), e.getMessage());
        assertTrue(e.getMessage().contains("listen needs one value"), e.getMessage());
    }

    @Test
    void aPasswordLeftInTheFileIsRefusedWithWhy() throws Exception {
        Path file = write("world: uranium\npassword: hunter2\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ProxySettings.load(file));

        assertTrue(e.getMessage().contains("the live map has no login"), e.getMessage());
    }

    @Test
    void anEmptyPasswordLineIsLetBe() throws Exception {
        assertEquals(
                World.URANIUM,
                ProxySettings.load(write("world: uranium\npassword:\n")).requiredWorld());
    }

    private Path write(String yaml) throws Exception {
        Path file = dir.resolve("session-proxy.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
