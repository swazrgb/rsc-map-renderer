package openrsc.gamedata.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.geometry.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Compiles the server's own {@code Point.isInBank} from its source and runs it against {@link
 * BankArea}. Skipped when the server tree is not there.
 */
class BankAreaParityTest {

    private static final int MARGIN = 4;

    private static final List<String> BOTTED_WORLDS = List.of("uranium", "rsccoleslaw");

    private static final Pattern IN_BOUNDS =
            Pattern.compile("inBounds\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    private static Path pointSource() {
        try {
            Path p = ServerConf.resolve().serverSrc().resolve("model/Point.java");
            return Files.isRegularFile(p) ? p : null;
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "server Point.java has no `" + signature + "`");
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after `" + signature + "`");
    }

    private static URLClassLoader compileServerIsInBank(String isInBank, String inBounds, Path dir) throws Exception {
        String probe = "public final class ServerBankProbe {\n"
                + "    private final int x;\n"
                + "    private final int y;\n"
                + "    private ServerBankProbe(int x, int y) { this.x = x; this.y = y; }\n"
                + "    public static boolean test(int x, int y, int mapData) {\n"
                + "        return new ServerBankProbe(x, y).isInBank(mapData);\n"
                + "    }\n"
                + inBounds + "\n"
                + isInBank + "\n"
                + "}\n";
        Path src = dir.resolve("ServerBankProbe.java");
        Files.writeString(src, probe, StandardCharsets.UTF_8);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "the test JVM has no Java compiler");
        StringWriter out = new StringWriter();
        boolean ok = javac.getTask(
                        out,
                        null,
                        null,
                        List.of("-d", dir.toString(), "-proc:none"),
                        null,
                        javac.getStandardFileManager(null, null, StandardCharsets.UTF_8)
                                .getJavaFileObjects(src))
                .call();
        assertTrue(ok, "the server's isInBank no longer compiles on its own (it calls more of Point?):\n" + out);
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
    }

    private static Set<Integer> mapDataProbes() {
        Set<Integer> out = new TreeSet<>(List.of(0, 64, 100));
        for (BankArea b : BankArea.values()) {
            out.add(b.minMapData());
            out.add(Math.max(0, b.minMapData() - 1));
        }
        return out;
    }

    @Test
    void theServersRectanglesAreOursInOrder() throws Exception {
        Path point = pointSource();
        assumeTrue(point != null, "server source tree not present");
        String body = method(Files.readString(point), "public boolean isInBank(int mapData)");
        List<Rect> server = new ArrayList<>();
        Matcher m = IN_BOUNDS.matcher(body.replaceAll("//[^\n]*", ""));
        while (m.find()) {
            server.add(Rect.of(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4))));
        }
        assertEquals(22, server.size(), "the server's bank rectangles: 22 when this was written");
        assertEquals(
                server,
                Arrays.stream(BankArea.values())
                        .flatMap(b -> b.boxes().stream())
                        .toList(),
                "BankArea must list the server's rectangles verbatim");
    }

    @Test
    void theServersIsInBankAgreesOnEveryTileAroundEveryBank(@TempDir Path dir) throws Exception {
        Path point = pointSource();
        assumeTrue(point != null, "server source tree not present");
        String source = Files.readString(point);
        List<String> mismatches = new ArrayList<>();
        int inside = 0;
        try (URLClassLoader loader = compileServerIsInBank(
                method(source, "public boolean isInBank(int mapData)"),
                method(source, "public boolean inBounds(int x1, int y1, int x2, int y2)"),
                dir)) {
            Method server = loader.loadClass("ServerBankProbe").getMethod("test", int.class, int.class, int.class);
            inside = sweep(server, mismatches);
        }
        assertTrue(inside > 0, "the sweep never found a tile inside a bank — the probe is broken");
        assertTrue(mismatches.isEmpty(), "BankArea disagrees with the server's Point.isInBank:\n" + mismatches);
    }

    private static Rect bounds(BankArea bank) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Rect r : bank.boxes()) {
            minX = Math.min(minX, r.minX());
            minY = Math.min(minY, r.minY());
            maxX = Math.max(maxX, r.maxX());
            maxY = Math.max(maxY, r.maxY());
        }
        return new Rect(minX, minY, maxX, maxY);
    }

    private static int sweep(Method server, List<String> mismatches) throws Exception {
        int inside = 0;
        for (int mapData : mapDataProbes()) {
            for (BankArea bank : BankArea.values()) {
                Rect box = bounds(bank).grow(MARGIN);
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    for (int x = box.minX(); x <= box.maxX(); x++) {
                        boolean theirs = (boolean) server.invoke(null, x, y, mapData);
                        boolean ours = BankArea.isInBank(x, y, mapData);
                        inside += theirs ? 1 : 0;
                        if (theirs != ours && mismatches.size() < 20) {
                            mismatches.add("(" + x + "," + y + ") mapData " + mapData + ": server " + theirs + ", ours "
                                    + ours);
                        }
                    }
                }
            }
        }
        return inside;
    }

    @Test
    void everyBankStandTileIsInsideItsArea() {
        List<String> outside = new ArrayList<>();
        for (Location bank : Location.BANKS) {
            BankArea area = bank.bankArea();
            if (area != null
                    && !bank.inBank(bank.x, bank.z, WorldProfile.authentic().basedMapData())) {
                outside.add(bank.name() + " (" + bank.x + "," + bank.z + ") area " + area);
            }
        }
        assertTrue(outside.isEmpty(), "bank stand tiles outside their bank's server area: " + outside);
    }

    @Test
    void everyServerBankIsARoutableBankButTutorialIsland() {
        Set<BankArea> used = EnumSet.noneOf(BankArea.class);
        for (Location bank : Location.BANKS) {
            if (bank.bankArea() == null) {
                assertNotNull(bank.chest(), bank + " is in no server bank area, so it must be a chest");
                continue;
            }
            assertTrue(used.add(bank.bankArea()), bank + " shares its area with another bank row");
        }
        assertEquals(
                EnumSet.complementOf(EnumSet.of(BankArea.TUTORIAL_ISLAND)),
                used,
                "every server bank has a Location row except Tutorial Island");
        for (Location l : Location.values()) {
            if (l.kind != Location.Kind.BANK) {
                assertNull(l.bankArea(), l + " is no bank but names a bank area");
            }
        }
    }

    @Test
    void everyDeclaredBankExistsOnTheWorldsWeBotOn() throws Exception {
        ServerConf conf;
        try {
            conf = ServerConf.resolve();
        } catch (IllegalStateException e) {
            conf = null;
        }
        assumeTrue(conf != null, "server conf tree not present");
        for (String world : BOTTED_WORLDS) {
            int mapData = WorldProfile.fromConf(conf, world).basedMapData();
            for (Location bank : Location.BANKS) {
                if (bank.bankArea() == null) {
                    continue;
                }
                assertTrue(
                        bank.bankArea().existsAt(mapData),
                        bank + " is gated at mapData " + bank.bankArea().minMapData() + "; " + world + " runs "
                                + mapData);
            }
        }
    }
}
