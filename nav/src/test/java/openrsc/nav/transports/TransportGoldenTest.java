package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.planner.InputsProbeBot;
import openrsc.nav.transports.spec.TransportDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every transport of each world, frozen as one line per entry tile: constant, entry, landing, cost,
 * gate, spend and engagement, plus a line for each constant that declares nothing there. Rewrite the
 * golden files with {@code -Dtransports.golden.update=true} and review their diff.
 */
class TransportGoldenTest {

    private static final Path GOLDEN = Path.of("src/test/resources/openrsc/nav/transports/transports.golden");

    private static final Path COLESLAW_GOLDEN =
            Path.of("src/test/resources/openrsc/nav/transports/transports.rsccoleslaw.golden");

    static Set<String> snapshot(ServerData env) {
        // A few transports read the account to decide what they spend: a fixed, empty-handed one keeps
        // the snapshot reproducible.
        AccountFacts reference =
                new InputsProbeBot(env, Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), CacheValues.EMPTY);
        Set<String> out = new TreeSet<>();
        Set<String> resolved = new HashSet<>();
        for (Transport t : AllTransports.defaultRegistry(env)) {
            resolved.add(t.costOverrideKey());
            String tail = " | cost=" + t.cost() + " | gate=" + t.prereq().description() + " | spends="
                    + t.consumes(reference) + " | engage=" + engage(t);
            List<Point> entries = t.entries();
            if (entries.isEmpty()) {
                out.add(t.costOverrideKey() + " | (region: wilderness depth < "
                        + t.region().wildernessLimit() + ", no blocked zone) -> " + t.destinationFrom(Point.of(0, 0))
                        + tail);
            }
            for (Point e : entries) {
                out.add(t.costOverrideKey() + " | " + e + " -> " + t.destinationFrom(e) + tail);
            }
        }
        // Frozen too, so a constant whose placement the world lacks cannot go unnoticed.
        for (TransportDef d : AllTransports.ALL_DEFS) {
            if (!resolved.contains(d.name())) {
                out.add(d.name() + " | (declares nothing)");
            }
        }
        return out;
    }

    private static String engage(Transport t) {
        return t instanceof DeclarativeTransport d ? d.engageSpec().describe() : "(not declarative)";
    }

    @Test
    @DisplayName("the loaded transport set is exactly the frozen one")
    void theTransportSetIsUnchanged() throws IOException {
        assertFrozen(RealDefs.serverData(), GOLDEN);
    }

    @Test
    @DisplayName("Coleslaw's loaded transport set is exactly its frozen one")
    void theColeslawTransportSetIsUnchanged() throws IOException {
        assertFrozen(RealDefs.serverData(RealDefs.COLESLAW), COLESLAW_GOLDEN);
    }

    private static void assertFrozen(ServerData env, Path golden) throws IOException {
        Set<String> now = snapshot(env);
        if (Boolean.getBoolean("transports.golden.update")) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, new ArrayList<>(now), StandardCharsets.UTF_8);
            System.out.println("GOLDEN written: " + golden + ", " + now.size() + " lines");
            return;
        }
        assertTrue(Files.exists(golden), "no golden file — generate it with -Dtransports.golden.update=true");
        Set<String> frozen = new TreeSet<>(Files.readAllLines(golden, StandardCharsets.UTF_8));
        Set<String> gained = new TreeSet<>(now);
        gained.removeAll(frozen);
        Set<String> lost = new TreeSet<>(frozen);
        lost.removeAll(now);
        assertTrue(
                gained.isEmpty() && lost.isEmpty(),
                "the transport set of " + env.world().name() + " changed (" + lost.size() + " lost, " + gained.size()
                        + " gained).\n" + "  lost:   " + first(lost) + "\n  gained: " + first(gained));
    }

    private static String first(Set<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (out.size() == 12) {
                out.add("… " + (lines.size() - 12) + " more");
                break;
            }
            out.add(l);
        }
        return String.join("\n          ", out);
    }
}
