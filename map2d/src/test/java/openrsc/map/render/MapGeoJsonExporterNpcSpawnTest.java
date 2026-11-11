package openrsc.map.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.defs.NpcLocs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MapGeoJsonExporterNpcSpawnTest {

    private static NpcLocs.Spawn spawn(int id, int x, int z, int minX, int minZ, int maxX, int maxZ) {
        return new NpcLocs.Spawn(id, new NpcLocs.Pos(x, z), new NpcLocs.Pos(minX, minZ), new NpcLocs.Pos(maxX, maxZ));
    }

    @Test
    void roamer_gets_point_plus_box_stationary_gets_point_only() {
        List<NpcLocs.Spawn> spawns = List.of(
                spawn(1, 100, 50, 98, 48, 102, 52),
                spawn(2, 200, 60, 200, 60, 200, 60),
                spawn(3, 300, 1000, 300, 1000, 300, 1000));

        JsonNode fc = MapGeoJsonExporter.buildFloor(0, List.of(), null, List.of(), null, List.of(), spawns, null, null);

        int points = 0;
        int polygons = 0;
        String firstPointName = null;
        for (JsonNode feat : fc.get("features")) {
            if (!"npcSpawn".equals(feat.get("properties").get("kind").asString())) {
                continue;
            }
            String geom = feat.get("geometry").get("type").asString();
            if ("Point".equals(geom)) {
                points++;
                if (firstPointName == null) {
                    firstPointName = feat.get("properties").get("name").asString();
                }
            } else if ("Polygon".equals(geom)) {
                polygons++;
            }
        }
        assertEquals(2, points, "one point per floor-0 spawn (spawn 3 is on floor 1)");
        assertEquals(1, polygons, "only the roaming spawn gets a wander box");
        assertEquals("npc 1", firstPointName, "null NpcDefs → name falls back to npc <id>");
    }

    @Test
    void serverIndex_is_the_full_list_position_not_a_per_floor_counter() {
        List<NpcLocs.Spawn> spawns =
                List.of(spawn(1, 300, 1000, 300, 1000, 300, 1000), spawn(2, 200, 60, 200, 60, 200, 60));

        JsonNode fc = MapGeoJsonExporter.buildFloor(0, List.of(), null, List.of(), null, List.of(), spawns, null, null);

        Integer found = null;
        for (JsonNode feat : fc.get("features")) {
            if ("npcSpawn".equals(feat.get("properties").get("kind").asString())
                    && "Point".equals(feat.get("geometry").get("type").asString())) {
                found = feat.get("properties").get("serverIndex").asInt();
            }
        }
        assertEquals(1, found, "floor-0 spawn keeps its full-list serverIndex despite an excluded earlier spawn");
    }

    @Test
    void wander_box_is_a_closed_tile_inclusive_ring() {
        JsonNode fc = MapGeoJsonExporter.buildFloor(
                0,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                List.of(spawn(1, 100, 50, 98, 48, 102, 52)),
                null,
                null);

        JsonNode ring = null;
        for (JsonNode feat : fc.get("features")) {
            if ("Polygon".equals(feat.get("geometry").get("type").asString())) {
                ring = feat.get("geometry").get("coordinates").get(0);
            }
        }
        assertTrue(ring != null && ring.size() == 5, "closed ring has 5 points (first == last)");
        assertEquals(98, ring.get(0).get(0).asInt());
        assertEquals(48, ring.get(0).get(1).asInt());
        assertEquals(103, ring.get(1).get(0).asInt());
        assertEquals(53, ring.get(2).get(1).asInt());
        assertEquals(ring.get(0).get(0).asInt(), ring.get(4).get(0).asInt());
        assertEquals(ring.get(0).get(1).asInt(), ring.get(4).get(1).asInt());
    }
}
