package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import openrsc.gamedata.ServerData;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegistryAddressingTest {

    private static ServerData env;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
    }

    @Test
    @DisplayName("the web constant covers every arm, and each arm names its web's tile")
    void thePlacementIdCoversEveryArm() {
        List<Transport> declared = Webs.all(env);
        for (Transport t : declared) {
            assertEquals(
                    Webs.WEB.name(),
                    t.costOverrideKey(),
                    "an arm must be addressable by its constant, not only by its own name: " + t.name());
            String tile = t.boundary().x() + "_" + t.boundary().y();
            assertTrue(t.name().startsWith("WEB@" + tile + "#"), "named by its web's tile: " + t.name());
        }

        long arms = declared.stream()
                .filter(t -> t.name().startsWith("WEB@304_300#"))
                .count();
        assertEquals(
                4,
                arms,
                "the web at (304,300) is a diagonal web with walkable flanks on BOTH axes (every other diagonal"
                        + " web is walled on one), so it opens four crossings, not two");
    }

    @Test
    @DisplayName("webs and vines are the same mechanic, differing only in id")
    void websAndVinesShareTheMechanic() {
        assertEquals(
                Webs.WEB.spec().getClass(),
                Vines.VINE.spec().getClass(),
                "webs and vines must be one mechanic, not two parallel implementations");
    }

    @Test
    @DisplayName("a wall transport's placement id survives the post-assembly edge rebuild")
    void placementSurvivesTheEdgeRebuild() {
        // The registry rebuilds every wall transport after expansion; a rebuild that drops the
        // placement leaves the wall addressable only by its own name.
        for (Transport t : FlameWalls.all(env)) {
            WallObjectTransport w = (WallObjectTransport) t;
            String before = w.costOverrideKey();
            assertEquals(FlameWalls.FLAME_WALL.name(), before, "declared: " + before);
            assertEquals(
                    before,
                    w.withEdgeFrom(env.boundaryLocs()).costOverrideKey(),
                    "withEdgeFrom dropped the placement of " + w.name());
            assertEquals(
                    before,
                    w.withWalkableEntries(env.collisionMap()).costOverrideKey(),
                    "withWalkableEntries dropped the placement of " + w.name());
        }
    }

    @Test
    @DisplayName("the Al Kharid border constant prices all four of its arms")
    void theBorderIsOnePlacement() {
        long border = SpecialCrossings.all(env).stream()
                .filter(t -> t.costOverrideKey().equals(SpecialCrossings.AL_KHARID_BORDER.name()))
                .count();
        assertEquals(4, border, "the border constant must price all four of its arms, as its key always did");
    }

    @Test
    @DisplayName("every transport the engine loads is declared by a registry constant")
    void everyLoadedTransportIsDeclared() {
        Set<String> ids = new TreeSet<>();
        for (openrsc.nav.transports.spec.TransportDef d : AllTransports.ALL_DEFS) {
            ids.add(d.name());
        }
        List<Transport> loaded = AllTransports.defaultRegistry(env);
        List<String> orphans = new ArrayList<>();
        for (Transport t : loaded) {
            if (!ids.contains(t.costOverrideKey())) {
                orphans.add(t.name() + " -> " + t.costOverrideKey());
            }
        }
        System.out.println(
                "REGISTRY loaded=" + loaded.size() + " declaredIds=" + ids.size() + " orphans=" + orphans.size());
        assertTrue(orphans.isEmpty(), "transports no registry declares: " + orphans);
    }

    @Test
    void aWallTeleportFiresFromItsEntry() {
        for (Transport t : FlameWalls.all(env)) {
            assertTrue(t.landingDependsOnEntry(), t.name());
        }
    }
}
