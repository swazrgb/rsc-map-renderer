package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.zip.GZIPOutputStream;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerDataSnapshot;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.Test;

class ServerDataSnapshotTest {

    @Test
    void aSnapshotReadsBackAsTheSameWorld() throws Exception {
        ServerData loaded = RealDefs.serverData();
        byte[] bytes = ServerDataSnapshot.write(ServerConf.resolve(), loaded);
        long t0 = System.nanoTime();
        ServerData read = ServerDataSnapshot.read(bytes);
        long readMs = (System.nanoTime() - t0) / 1_000_000;
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(bytes);
        }
        System.out.printf(
                "snapshot: %d KB, %d KB gzipped, read in %d ms%n", bytes.length >> 10, gz.size() >> 10, readMs);

        CollisionMap.Planes a = loaded.collisionMap().planes();
        CollisionMap.Planes b = read.collisionMap().planes();
        assertArrayEquals(a.flags(), b.flags());
        assertArrayEquals(a.objectFlags(), b.objectFlags());
        assertArrayEquals(a.projectileAllowed(), b.projectileAllowed());
        assertArrayEquals(a.components(), b.components());
        assertEquals(a.componentCount(), b.componentCount());
        assertArrayEquals(a.staticSteps(), b.staticSteps());
        assertArrayEquals(a.groundTexture(), b.groundTexture());
        assertArrayEquals(a.groundOverlay(), b.groundOverlay());
        assertArrayEquals(a.roofTexture(), b.roofTexture());
        assertEquals(
                loaded.collisionMap().staticLocIndex().sceneryCount(),
                read.collisionMap().staticLocIndex().sceneryCount());

        assertEquals(loaded.world().name(), read.world().name());
        assertEquals(loaded.world().dbName(), read.world().dbName());
        assertEquals(loaded.world().features(), read.world().features());
        assertEquals(loaded.world().rules(), read.world().rules());
        assertEquals(loaded.itemDefs().size(), read.itemDefs().size());
        for (int id = 0; id < loaded.itemDefs().size(); id++) {
            assertEquals(loaded.item(id), read.item(id), "item " + id);
            assertEquals(loaded.healFor(id), read.healFor(id), "heal " + id);
            assertEquals(loaded.cooking(id), read.cooking(id), "cooking " + id);
            assertEquals(loaded.smelting(id), read.smelting(id), "smelting " + id);
            assertEquals(loaded.smithing(id), read.smithing(id), "smithing " + id);
        }
        assertEquals(loaded.npcDefs().size(), read.npcDefs().size());
        for (int id = 0; id < loaded.npcDefs().size(); id++) {
            assertEquals(loaded.npc(id), read.npc(id), "npc " + id);
            assertEquals(loaded.npcDefs().isAggressive(id), read.npcDefs().isAggressive(id), "npc " + id);
        }
        for (int id = 0; id < 1300; id++) {
            assertEquals(loaded.object(id), read.object(id), "object " + id);
            assertEquals(loaded.doorDefs().get(id), read.doorDefs().get(id), "door " + id);
            assertEquals(loaded.mining(id), read.mining(id), "mining " + id);
            assertEquals(woodcutting(loaded, id), woodcutting(read, id), "woodcutting " + id);
            assertEquals(loaded.fishing(id, 0), read.fishing(id, 0), "fishing " + id);
            assertEquals(loaded.fishing(id, 1), read.fishing(id, 1), "fishing " + id);
            assertEquals(loaded.runecraft(id), read.runecraft(id), "runecraft " + id);
        }
        assertEquals(loaded.sceneryLocs(), read.sceneryLocs());
        assertEquals(loaded.boundaryLocs(), read.boundaryLocs());
        assertEquals(loaded.npcLocs(), read.npcLocs());
        assertEquals(loaded.groundItemLocs(), read.groundItemLocs());
        assertEquals(loaded.npcSpawns(), read.npcSpawns());
        assertEquals(loaded.objectTelePoints(), read.objectTelePoints());

        Pathfinder fromLoad = Pathfinder.load(loaded, NavCaches.none());
        Pathfinder fromSnapshot = Pathfinder.load(read, NavCaches.none());
        assertEquals(fromLoad.transports().size(), fromSnapshot.transports().size());
        for (int i = 0; i < fromLoad.transports().size(); i++) {
            assertEquals(
                    fromLoad.transports().get(i).name(),
                    fromSnapshot.transports().get(i).name());
        }
        SplittableRandom rnd = new SplittableRandom(20260917L);
        CollisionMap map = loaded.collisionMap();
        int main = map.componentId(120, 648); // Lumbridge, so every sampled tile is on the mainland
        List<String> diffs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Point from = tile(rnd, map, main);
            Point to = tile(rnd, map, main);
            AccountFacts a1 = i % 2 == 0
                    ? NavProbe.onFootFacts(loaded, from.x(), from.y())
                    : NavProbe.allEligibleFacts(fromLoad, from.x(), from.y());
            AccountFacts a2 = i % 2 == 0
                    ? NavProbe.onFootFacts(read, from.x(), from.y())
                    : NavProbe.allEligibleFacts(fromSnapshot, from.x(), from.y());
            TravelTime e1 = fromLoad.nav().estimate(a1, from, to, NavPolicy.DEFAULT);
            TravelTime e2 = fromSnapshot.nav().estimate(a2, from, to, NavPolicy.DEFAULT);
            if (!e1.equals(e2)) {
                diffs.add("estimate " + from + "->" + to + ": " + e1 + " vs " + e2);
            }
            PlanResult p1 = fromLoad.nav().plan(new PlanRequest(a1, from, to, LoadoutSpec.any(), NavPolicy.DEFAULT));
            PlanResult p2 =
                    fromSnapshot.nav().plan(new PlanRequest(a2, from, to, LoadoutSpec.any(), NavPolicy.DEFAULT));
            if (!summary(p1).equals(summary(p2))) {
                diffs.add("plan " + from + "->" + to + ": " + summary(p1) + " vs " + summary(p2));
            }
        }
        assertTrue(diffs.isEmpty(), "answers differ: " + diffs);
    }

    private static String summary(PlanResult r) {
        if (!(r instanceof PlanResult.Planned p)) {
            return "none";
        }
        StringBuilder sb = new StringBuilder().append(p.route().totalCostDeci());
        for (Route.Step s : p.route().steps()) {
            sb.append(' ')
                    .append(
                            switch (s) {
                                case Route.Cross c -> "X" + c.span();
                                case Route.Hop h -> "H" + h.transport();
                                case Route.BankStop b -> "B" + b.bank();
                                case Route.Arrive ar -> "A";
                            });
        }
        return sb.toString();
    }

    private static Point tile(SplittableRandom rnd, CollisionMap map, int component) {
        while (true) {
            int x = rnd.nextInt(CollisionMap.WIDTH);
            int y = rnd.nextInt(CollisionMap.FLOOR_HEIGHT);
            if (map.componentId(x, y) == component) {
                return Point.of(x, y);
            }
        }
    }

    // A record with int[] components has no value equality, so a tree def compares as text.
    private static String woodcutting(ServerData data, int sceneryId) {
        var d = data.woodcutting(sceneryId);
        return d == null
                ? "none"
                : d.requiredLevel() + "/" + d.logId() + "/" + d.exp() + "/" + d.fell() + "/" + d.respawnTime() + "/"
                        + Arrays.toString(d.low()) + "/" + Arrays.toString(d.high());
    }
}
