package openrsc.map.render;

import java.util.List;
import java.util.Locale;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.defs.NpcDefs;
import openrsc.gamedata.defs.NpcLocs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds a floor's door and NPC-spawn features as GeoJSON. Coordinates are tiles, not mirrored: the
 * first is the world x, the second the row within the floor, so a tile spans {@code [x, x + 1]} by
 * {@code [z, z + 1]} and its east edge is at {@code x}.
 */
public final class MapGeoJsonExporter {

    private static final Logger LOG = LoggerFactory.getLogger(MapGeoJsonExporter.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * The floor's {@code FeatureCollection}. A door is a {@code LineString} on its tile edge or diagonal
     * ({@code kind} {@code door}; {@code id}, {@code dir}, {@code openable}, and {@code source}
     * {@code boundary} or {@code scenery}); boundaries count when their name reads as a door, scenery
     * of type 2 or 3 when it reads as a door or gate. An NPC spawn ({@code kind} {@code npcSpawn}) is a
     * {@code Point} at its start tile's centre and, when it wanders, a {@code Polygon} over its whole
     * box; its {@code serverIndex} is its position in {@code npcLocs}. {@code npcLocs} and
     * {@code npcDefs} may be {@code null}.
     */
    public static JsonNode buildFloor(
            int floor,
            List<BoundaryLocs.Loc> boundaryLocs,
            DoorDefs doorDefs,
            List<SceneryLocs.Loc> sceneryLocs,
            ObjectDefs objectDefs,
            List<NpcLocs.Spawn> npcLocs,
            NpcDefs npcDefs) {
        long t0 = System.nanoTime();

        ArrayNode features = MAPPER.createArrayNode();
        int boundaryDoors = appendBoundaryDoors(features, boundaryLocs, doorDefs, floor);
        int sceneryDoors = appendSceneryDoors(features, sceneryLocs, objectDefs, floor);
        int npcSpawns = appendNpcSpawns(features, npcLocs, npcDefs, floor);

        ObjectNode fc = MAPPER.createObjectNode();
        fc.put("type", "FeatureCollection");
        fc.put("floor", floor);
        fc.set("features", features);

        long ms = (System.nanoTime() - t0) / 1_000_000;
        LOG.info(
                "GeoJSON floor {}: {} boundary doors, {} scenery doors, {} npc spawns ({} ms)",
                floor,
                boundaryDoors,
                sceneryDoors,
                npcSpawns,
                ms);
        return fc;
    }

    private static int appendNpcSpawns(ArrayNode features, List<NpcLocs.Spawn> npcLocs, NpcDefs npcDefs, int floor) {
        if (npcLocs == null || npcLocs.isEmpty()) {
            return 0;
        }
        int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
        int floorEnd = floorBase + CollisionMap.FLOOR_HEIGHT;
        int count = 0;
        for (int i = 0; i < npcLocs.size(); i++) {
            NpcLocs.Spawn s = npcLocs.get(i);
            if (s.start() == null) {
                continue;
            }
            int absZ = s.start().z();
            if (absZ < floorBase || absZ >= floorEnd) {
                continue;
            }
            int zLocal = absZ - floorBase;
            ObjectNode pointProps = npcSpawnProps(s, i, npcDefs);
            NpcLocs.Pos min = s.min();
            NpcLocs.Pos max = s.max();
            if (min != null && max != null && (max.x() > min.x() || max.z() > min.z())) {
                int z0 = min.z() - floorBase;
                int z1 = max.z() - floorBase;
                int x0 = min.x();
                int x1 = max.x() + 1;
                int za = z0;
                int zb = z1 + 1;
                double[][] ring = {{x0, za}, {x1, za}, {x1, zb}, {x0, zb}, {x0, za}};
                features.add(polygonFeature(ring, npcSpawnProps(s, i, npcDefs)));
                count++;
                ArrayNode box = MAPPER.createArrayNode();
                box.add(x0);
                box.add(za);
                box.add(x1);
                box.add(zb);
                pointProps.set("box", box);
            }
            features.add(pointFeature(s.start().x() + 0.5, zLocal + 0.5, pointProps));
            count++;
        }
        return count;
    }

    private static ObjectNode npcSpawnProps(NpcLocs.Spawn s, int serverIndex, NpcDefs npcDefs) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("kind", "npcSpawn");
        props.put("serverIndex", serverIndex);
        props.put("id", s.id());
        NpcDef d = npcDefs == null ? null : npcDefs.get(s.id());
        String name = d == null ? null : d.name();
        props.put("name", (name == null || name.isEmpty()) ? ("npc " + s.id()) : name);
        if (npcDefs != null) {
            props.put("aggressive", npcDefs.isAggressive(s.id()));
        }
        if (d != null) {
            props.put("combatLevel", d.combatLevel());
            props.put("hits", d.hits());
            props.put("attack", d.attack());
            props.put("strength", d.strength());
            props.put("defense", d.defense());
            props.put("attackable", d.attackable());
            props.put("ranged", d.ranged());
            props.put("members", d.members());
            props.put("respawnTime", d.respawnTime());
            if (d.description() != null && !d.description().isEmpty()) {
                props.put("description", d.description());
            }
        }
        return props;
    }

    private static int appendBoundaryDoors(
            ArrayNode features, List<BoundaryLocs.Loc> boundaryLocs, DoorDefs defs, int floor) {
        int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
        int floorEnd = floorBase + CollisionMap.FLOOR_HEIGHT;
        int count = 0;
        for (BoundaryLocs.Loc loc : boundaryLocs) {
            int absZ = loc.pos().y();
            if (absZ < floorBase || absZ >= floorEnd) {
                continue;
            }
            String name = defs.name(loc.id());
            if (name == null) {
                continue;
            }
            String n = name.toLowerCase(Locale.ROOT);
            boolean isDoorLike = n.contains("door") || n.contains("doorframe") || n.equals("odd looking wall");
            if (!isDoorLike) {
                continue;
            }
            int x = loc.pos().x();
            int z = absZ - floorBase;
            int dir = loc.direction();
            ObjectNode props = doorProps(loc.id(), dir, defs.isOpenable(loc.id()), "boundary");
            double[][] line = boundaryDoorLine(x, z, dir);
            if (line == null) {
                continue;
            }
            features.add(lineFeature(line, props));
            count++;
        }
        return count;
    }

    private static int appendSceneryDoors(
            ArrayNode features, List<SceneryLocs.Loc> sceneryLocs, ObjectDefs defs, int floor) {
        int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
        int floorEnd = floorBase + CollisionMap.FLOOR_HEIGHT;
        int count = 0;
        for (SceneryLocs.Loc loc : sceneryLocs) {
            int absZ = loc.pos().y();
            if (absZ < floorBase || absZ >= floorEnd) {
                continue;
            }
            ObjectDef def = defs.get(loc.id());
            if (def == null) {
                continue;
            }
            if (def.typ() != 2 && def.typ() != 3) {
                continue;
            }
            String defName = (def.name() == null) ? "" : def.name().toLowerCase(Locale.ROOT);
            boolean isDoorLike = defName.contains("door") || defName.contains("gate");
            if (!isDoorLike) {
                continue;
            }
            int dir = loc.direction();
            int w = def.width();
            int h = def.height();
            // The footprint turns with dir, and a scenery door walls the same edge of each of its tiles.
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L511
            if (dir != 0 && dir != 4) {
                int t = w;
                w = h;
                h = t;
            }
            int dx = loc.pos().x();
            int dy = absZ;
            for (int fx = dx; fx < dx + w; fx++) {
                for (int fy = dy; fy < dy + h; fy++) {
                    if (fy < floorBase || fy >= floorEnd) {
                        continue;
                    }
                    if (fx < 0 || fx >= CollisionMap.WIDTH) {
                        continue;
                    }
                    int zLocal = fy - floorBase;
                    double[][] line = sceneryDoorLine(fx, zLocal, dir);
                    if (line == null) {
                        continue;
                    }
                    ObjectNode props = doorProps(loc.id(), dir, true, "scenery");
                    features.add(lineFeature(line, props));
                    count++;
                }
            }
        }
        return count;
    }

    // Dir 0 walls the north edge, 1 the east, 2 FULL_BLOCK_A and 3 FULL_BLOCK_B, drawn as WallsRenderer
    // draws them.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L554
    private static double[][] boundaryDoorLine(int x, int z, int dir) {
        return switch (dir) {
            case 0 -> new double[][] {{x, z}, {x + 1, z}};
            case 1 -> new double[][] {{x, z}, {x, z + 1}};
            case 2 -> new double[][] {{x + 1, z}, {x, z + 1}};
            case 3 -> new double[][] {{x + 1, z + 1}, {x, z}};
            default -> null;
        };
    }

    // Dir 0 walls the east edge, 2 the south, 4 the west and 6 the north.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L525
    private static double[][] sceneryDoorLine(int x, int z, int dir) {
        return switch (dir) {
            case 0 -> new double[][] {{x, z}, {x, z + 1}};
            case 2 -> new double[][] {{x, z + 1}, {x + 1, z + 1}};
            case 4 -> new double[][] {{x + 1, z}, {x + 1, z + 1}};
            case 6 -> new double[][] {{x, z}, {x + 1, z}};
            default -> null;
        };
    }

    private static ObjectNode doorProps(int id, int dir, boolean openable, String source) {
        ObjectNode props = MAPPER.createObjectNode();
        props.put("kind", "door");
        props.put("id", id);
        props.put("dir", dir);
        props.put("openable", openable);
        props.put("source", source);
        return props;
    }

    private static ObjectNode pointFeature(double x, double z, ObjectNode props) {
        ObjectNode geom = MAPPER.createObjectNode();
        geom.put("type", "Point");
        ArrayNode coords = MAPPER.createArrayNode();
        coords.add(x);
        coords.add(z);
        geom.set("coordinates", coords);
        ObjectNode feat = MAPPER.createObjectNode();
        feat.put("type", "Feature");
        feat.set("geometry", geom);
        feat.set("properties", props);
        return feat;
    }

    private static ObjectNode polygonFeature(double[][] ring, ObjectNode props) {
        ObjectNode geom = MAPPER.createObjectNode();
        geom.put("type", "Polygon");
        ArrayNode rings = MAPPER.createArrayNode();
        ArrayNode coords = MAPPER.createArrayNode();
        for (double[] p : ring) {
            ArrayNode pt = MAPPER.createArrayNode();
            pt.add(p[0]);
            pt.add(p[1]);
            coords.add(pt);
        }
        rings.add(coords);
        geom.set("coordinates", rings);

        ObjectNode feat = MAPPER.createObjectNode();
        feat.put("type", "Feature");
        feat.set("geometry", geom);
        feat.set("properties", props);
        return feat;
    }

    private static ObjectNode lineFeature(double[][] line, ObjectNode props) {
        ObjectNode geom = MAPPER.createObjectNode();
        geom.put("type", "LineString");
        ArrayNode coords = MAPPER.createArrayNode();
        for (double[] p : line) {
            ArrayNode pt = MAPPER.createArrayNode();
            pt.add(p[0]);
            pt.add(p[1]);
            coords.add(pt);
        }
        geom.set("coordinates", coords);

        ObjectNode feat = MAPPER.createObjectNode();
        feat.put("type", "Feature");
        feat.set("geometry", geom);
        feat.set("properties", props);
        return feat;
    }

    private MapGeoJsonExporter() {}
}
