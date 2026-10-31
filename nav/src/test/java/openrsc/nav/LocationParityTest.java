package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.data.Location;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LocationParityTest {

    private record Spawn(int id, int minX, int minZ, int maxX, int maxZ) {

        boolean roamsWithin(int x, int z, int pad) {
            return x >= minX - pad && x <= maxX + pad && z >= minZ - pad && z <= maxZ + pad;
        }
    }

    private static List<Spawn> loadSpawns() throws Exception {
        Path locs;
        try {
            locs = ServerConf.resolve().locs().resolve("NpcLocs.json");
        } catch (IllegalStateException e) {
            return null;
        }
        if (!Files.exists(locs)) {
            return null;
        }
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(locs));
        List<Spawn> out = new ArrayList<>();
        for (JsonNode n : root.path("npclocs")) {
            out.add(new Spawn(
                    n.path("id").asInt(),
                    n.path("min").path("X").asInt(),
                    n.path("min").path("Y").asInt(),
                    n.path("max").path("X").asInt(),
                    n.path("max").path("Y").asInt()));
        }
        return out;
    }

    // NPCs wander a tile or two past their roam rectangle, and an approach tile can sit on the far
    // side of a counter.
    private static final int APPROACH_PAD = 5;

    private static final int PORTAL_PAD = 2;

    @Test
    void everyLocationHasItsNpcsNearby() throws Exception {
        List<Spawn> spawns = loadSpawns();
        assumeTrue(spawns != null, "server conf tree not present");
        StringBuilder failures = new StringBuilder();
        for (Location loc : Location.values()) {
            if (!loc.existsIn(RealDefs.serverData())) {
                continue;
            }
            if (loc.npcIndexes().length > 0) {
                failures.append(staffFailures(loc, spawns, APPROACH_PAD));
                continue;
            }
            if (loc.npcIds().length == 0) {
                continue;
            }
            boolean found = false;
            for (Spawn s : spawns) {
                for (int id : loc.npcIds()) {
                    if (s.id() == id && s.roamsWithin(loc.x, loc.z, APPROACH_PAD)) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
            if (!found) {
                failures.append("\n  ")
                        .append(loc.name())
                        .append(" — no spawn of npc ids near (")
                        .append(loc.x)
                        .append(',')
                        .append(loc.z)
                        .append(')');
            }
        }
        assertTrue(failures.isEmpty(), "Locations without a matching NpcLocs spawn:" + failures);
    }

    @Test
    void bankTilesSitNextToBankerSpawns() throws Exception {
        List<Spawn> spawns = loadSpawns();
        assumeTrue(spawns != null, "server conf tree not present");
        StringBuilder failures = new StringBuilder();
        for (Location loc : Location.ofKind(Location.Kind.BANK)) {
            if (loc.npcIndexes().length == 0 || !loc.existsIn(RealDefs.serverData())) {
                continue;
            }
            failures.append(staffFailures(loc, spawns, PORTAL_PAD));
        }
        assertTrue(failures.isEmpty(), "Bank tiles not beside a banker spawn:" + failures);
    }

    private static String staffFailures(Location loc, List<Spawn> spawns, int pad) {
        StringBuilder out = new StringBuilder();
        boolean anyBeside = false;
        for (int index : loc.npcIndexes()) {
            if (index < 0 || index >= spawns.size()) {
                out.append("\n  ")
                        .append(loc.name())
                        .append(" — staff index ")
                        .append(index)
                        .append(" is past the end of NpcLocs (")
                        .append(spawns.size())
                        .append(" spawns)");
                continue;
            }
            // One of the staff beside the tile is enough: a second may work the far end of the room.
            anyBeside |= spawns.get(index).roamsWithin(loc.x, loc.z, pad);
        }
        if (!anyBeside) {
            out.append("\n  ")
                    .append(loc.name())
                    .append(" — none of its declared staff roams within ")
                    .append(pad)
                    .append(" of (")
                    .append(loc.x)
                    .append(',')
                    .append(loc.z)
                    .append(')');
        }
        return out.toString();
    }

    @Test
    void mageArenaIsABankButNeverARendezvous() {
        assertTrue(
                Location.BANKS.contains(Location.BANK_MAGE_ARENA),
                "the Mage Arena stays a usable bank for a bot already in-region");
        assertTrue(
                Location.RENDEZVOUS_BANKS.stream().noneMatch(b -> b == Location.BANK_MAGE_ARENA),
                "the Mage Arena must be excluded from the rendezvous banks");
        assertEquals(
                java.util.Set.of(
                        Location.BANK_MAGE_ARENA,
                        Location.BANK_FISHING_GUILD,
                        Location.BANK_CRAFTING_GUILD,
                        Location.BANK_WOODCUTTING_GUILD,
                        Location.BANK_FALADOR_WEST),
                Location.BANKS.stream()
                        .filter(b -> !Location.RENDEZVOUS_BANKS.contains(b))
                        .collect(java.util.stream.Collectors.toSet()),
                "the Mage Arena and the guild chests (both bots must pass the door) are never a rendezvous");
        for (Location b : Location.RENDEZVOUS_BANKS) {
            assertTrue(Location.BANKS.contains(b), "every rendezvous bank is a real bank: " + b);
        }
    }
}
