package openrsc.map.render;

import java.util.List;
import java.util.Set;
import openrsc.gamedata.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.NpcDefs;
import openrsc.gamedata.NpcLocs;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.SceneryLocs;
import openrsc.gamedata.world.CollisionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Per-floor dynamic-overlay GeoJSON — currently doors only.
 *
 * <p>Static topology (terrain colours, walls, diagonals, blocked tiles) now
 * lives in two server-side rasters: {@link TerrainRenderer} (biome/overlay colours) and
 * {@link WallsRenderer} (walls + diagonals + impassable-tile dim). They render once at startup and
 * ship to the browser as PNG, sparing Leaflet from drawing tens of thousands of SVG features.
 *
 * <p>Doors stay vector because they're <i>dynamic</i>: the bot tracks
 * runtime open/close events (opcode 48 in {@code PacketDispatcher}) and we want to flip a door's
 * visual state without re-baking the PNG. Initial door positions ship here as a GeoJSON
 * {@code FeatureCollection}; live state updates will arrive over SSE in M4.
 */
public final class MapGeoJsonExporter {

  private static final Logger LOG = LoggerFactory.getLogger(MapGeoJsonExporter.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private MapGeoJsonExporter() {
  }

  public static JsonNode buildFloor(int floor,
      List<BoundaryLocs.Loc> boundaryLocs,
      DoorDefs doorDefs,
      List<SceneryLocs.Loc> sceneryLocs,
      ObjectDefs objectDefs,
      List<NpcLocs.Spawn> npcLocs,
      NpcDefs npcDefs) {
    long t0 = System.nanoTime();

    ArrayNode features = MAPPER.createArrayNode();
    // The Transports layer (and its door-suppression) is omitted in this
    // standalone renderer — no path planner is loaded. Doors render
    // unconditionally; an empty "covered tiles" set leaves the suppression
    // helpers dormant.
    Set<Long> transportTiles = Set.of();
    int boundaryDoors = appendBoundaryDoors(features, boundaryLocs, doorDefs, floor,
        transportTiles);
    int sceneryDoors = appendSceneryDoors(features, sceneryLocs, objectDefs, floor, transportTiles);
    int npcSpawns = appendNpcSpawns(features, npcLocs, npcDefs, floor);

    ObjectNode fc = MAPPER.createObjectNode();
    fc.put("type", "FeatureCollection");
    fc.put("floor", floor);
    fc.set("features", features);

    long ms = (System.nanoTime() - t0) / 1_000_000;
    LOG.info("GeoJSON floor {}: {} boundary doors, {} scenery doors, {} npc spawns ({} ms)",
        floor, boundaryDoors, sceneryDoors, npcSpawns, ms);
    return fc;
  }

  /**
   * NPC spawns: a Point at each spawn's start tile (labelled with the NPC name) plus, when the NPC
   * roams, a Polygon covering its {@code min}..{@code max} wander rectangle. {@code npclocs} carry an
   * absolute Y (floor-baked), so they filter onto floors exactly like transports/doors. Every Feature
   * carries the NPC's def id, name, combat level and aggressive flag so the UI can label and colour
   * them (aggressive NPCs stand out) and so the box matches its marker.
   */
  private static int appendNpcSpawns(ArrayNode features,
      List<NpcLocs.Spawn> npcLocs, NpcDefs npcDefs, int floor) {
    if (npcLocs == null || npcLocs.isEmpty()) {
      return 0;
    }
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int floorEnd = floorBase + CollisionMap.FLOOR_HEIGHT;
    int count = 0;
    // i is the spawn's position in the full load-order list == the server's boot entity index
    // (serverIndex). Use the full-list index, NOT a per-floor counter, so it survives floor
    // filtering. See ServerData.NpcSpawn for the determinism reasoning.
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
      // The wander box, when the NPC actually roams (min..max spans more than the start tile).
      NpcLocs.Pos min = s.min();
      NpcLocs.Pos max = s.max();
      if (min != null && max != null && (max.x() > min.x() || max.z() > min.z())) {
        int z0 = min.z() - floorBase;
        int z1 = max.z() - floorBase;
        // Tile-inclusive rect: cover whole cells, so +1 on the far edges.
        int x0 = min.x();
        int x1 = max.x() + 1;
        int za = z0;
        int zb = z1 + 1;
        double[][] ring = {{x0, za}, {x1, za}, {x1, zb}, {x0, zb}, {x0, za}};
        features.add(polygonFeature(ring, npcSpawnProps(s, i, npcDefs)));
        count++;
        // Carry the box bounds on the point too, so hovering its dot can pop a highlight of this
        // exact spawn's zone without having to correlate point↔polygon features.
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
    // Predicted boot entity index = this spawn's load-order position; matches the live wire
    // serverIndex for static spawns on this members deployment (see ServerData.NpcSpawn).
    props.put("serverIndex", serverIndex);
    props.put("id", s.id());
    NpcDefs.Def d = npcDefs == null ? null : npcDefs.get(s.id());
    String name = d == null ? null : d.name();
    props.put("name", (name == null || name.isEmpty()) ? ("npc " + s.id()) : name);
    // isAggressive() is the server's behavioural flag (attackable && aggressive), what actually
    // governs whether it attacks you — distinct from the raw def aggressive bit below.
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

  /**
   * Boundary-loc doors. {@code dir} convention mirrors {@code CollisionMap.applyBoundaries}:
   * <ul>
   *   <li>0 → WALL_NORTH on (x, z) — line on the north edge.</li>
   *   <li>1 → WALL_EAST on (x, z)  — line on the east edge.</li>
   *   <li>2 → diagonal "\" — NW corner to SE corner of the tile.</li>
   *   <li>3 → diagonal "/" — NE corner to SW corner.</li>
   * </ul>
   */
  private static int appendBoundaryDoors(ArrayNode features,
      List<BoundaryLocs.Loc> boundaryLocs,
      DoorDefs defs, int floor,
      Set<Long> transportTiles) {
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int floorEnd = floorBase + CollisionMap.FLOOR_HEIGHT;
    int count = 0;
    for (BoundaryLocs.Loc loc : boundaryLocs) {
      int absZ = loc.pos().y();
      if (absZ < floorBase || absZ >= floorEnd) {
        continue;
      }
      // Include any boundary whose name reads as a door — both
      // closed-state DOORs (doorType=1) and open-state DOORFRAMEs
      // (doorType=0, Cmd1="WalkTo"). Without the open-state inclusion
      // the world map shows only locked-by-default doors and misses
      // every door that happens to be open at world load. We use a
      // name-pattern check rather than the old isOpenable() because
      // jungle/flamewall/odd-looking-wall etc. now have richer
      // classifications in SpecialDoor that don't map cleanly to a
      // single boolean.
      String name = defs.name(loc.id());
      if (name == null) {
        continue;
      }
      String n = name.toLowerCase();
      boolean isDoorLike = n.contains("door") || n.contains("doorframe")
                           || n.equals("odd looking wall");
      if (!isDoorLike) {
        continue;
      }
      int x = loc.pos().x();
      int z = absZ - floorBase;
      int dir = loc.direction();
      // Skip boundaries whose flanking rect overlaps a transport entry
      // tile — a bespoke Transport handles traversal and the door
      // visual would suggest the bot opens it directly.
      if (boundaryCoveredByTransport(x, absZ, dir, transportTiles)) {
        continue;
      }
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

  /**
   * Scenery-typ-2 doors. These are openable scenery objects that {@code CollisionMap.applyScenery}
   * skips for the same runtime-tracked reason as boundary doors. {@code dir} convention is the
   * SCENERY one (0/2/4/6, axis-aligned only — no diagonals for scenery walls):
   * <ul>
   *   <li>0 → WALL_EAST  on (x, z) — east edge.</li>
   *   <li>2 → WALL_SOUTH on (x, z) — south edge.</li>
   *   <li>4 → WALL_WEST  on (x, z) — west edge.</li>
   *   <li>6 → WALL_NORTH on (x, z) — north edge.</li>
   * </ul>
   */
  private static int appendSceneryDoors(ArrayNode features,
      List<SceneryLocs.Loc> sceneryLocs,
      ObjectDefs defs, int floor,
      Set<Long> transportTiles) {
    int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
    int floorEnd = floorBase + CollisionMap.FLOOR_HEIGHT;
    int count = 0;
    for (SceneryLocs.Loc loc : sceneryLocs) {
      int absZ = loc.pos().y();
      if (absZ < floorBase || absZ >= floorEnd) {
        continue;
      }
      ObjectDefs.Entry def = defs.get(loc.id());
      if (def == null) {
        continue;
      }
      // Include typ=2 (closed-state) AND typ=3 (open-state) doors/gates
      // so the world map renders default-open doubledoors etc. Other
      // typ=3 scenery (decoration) is excluded via a name check.
      if (def.typ() != 2 && def.typ() != 3) {
        continue;
      }
      String defName = (def.name() == null) ? "" : def.name().toLowerCase();
      boolean isDoorLike = defName.contains("door") || defName.contains("gate")
                           || defName.contains("doors");
      if (!isDoorLike) {
        continue;
      }
      int dir = loc.direction();
      // Scenery footprint: dir 0/4 keeps (w, h); the others rotate 90°
      // so width and height swap. Mirrors CollisionMap.applyScenery:
      // 296-303.
      int w = def.width();
      int h = def.height();
      if (dir != 0 && dir != 4) {
        int t = w;
        w = h;
        h = t;
      }
      int dx = loc.pos().x();
      int dy = absZ;
      // Skip this scenery entirely if a transport covers it — the
      // transport marker is the canonical "you traverse here" signal
      // and a door line on the same tile is misleading. Covered iff
      // the scenery's atObject-engagement rect (typ=2/3 bbox per
      // server's GameObject.getObjectBoundary) contains any
      // transport entry tile.
      if (sceneryCoveredByTransport(dx, dy, dir, w, h, def.typ(), transportTiles)) {
        continue;
      }
      // Each tile in the footprint stamps the wall on the same edge,
      // so each gets its own door LineString — otherwise a width-2
      // gate renders as a single tile.
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

  /**
   * Transports as clickable Points at each entry tile. A transport with multiple entry tiles (toll
   * gates: east + west; agility shortcuts that fire from either side) emits one Feature per entry.
   * Transports with no enumerable entry (self-targeted teleport spells,
   * {@code entryTiles().isEmpty()}) are skipped — they fire from anywhere, so a map marker doesn't
   * make sense.
   *
   * <p>Each Feature carries the destination as plain ints in its props so
   * the UI can pan/floor-switch on click without a round-trip: {@code dx}, {@code dz},
   * {@code dfloor}. {@code dz} is destination Y modulo {@link CollisionMap#FLOOR_HEIGHT} for
   * consistency with the Leaflet per-floor coord system.
   */
  /**
   * WALL_NORTH at lat=z, WALL_EAST at lng=x, diagonals corner-to-corner — same convention as
   * {@link WallsRenderer} so doors visually overlap walls when the door isn't openable (gates) and
   * slot into the wall outline when it is (regular doors).
   */
  private static double[][] boundaryDoorLine(int x, int z, int dir) {
    return switch (dir) {
      case 0 -> new double[][]{{x, z}, {x + 1, z}};         // N edge
      case 1 -> new double[][]{{x, z}, {x, z + 1}};         // E edge
      case 2 -> new double[][]{{x + 1, z}, {x, z + 1}};         // "\" diagonal
      case 3 -> new double[][]{{x + 1, z + 1}, {x, z}};         // "/" diagonal
      default -> null;
    };
  }

  private static double[][] sceneryDoorLine(int x, int z, int dir) {
    return switch (dir) {
      case 0 -> new double[][]{{x, z}, {x, z + 1}};         // E edge
      case 2 -> new double[][]{{x, z + 1}, {x + 1, z + 1}};     // S edge
      case 4 -> new double[][]{{x + 1, z}, {x + 1, z + 1}};     // W edge
      case 6 -> new double[][]{{x, z}, {x + 1, z}};         // N edge
      default -> null;
    };
  }

  /**
   * Pack a (x, y) tile into a single long for set membership. y is the absolute (floor-baked)
   * coord, so two tiles at the same x but on different floors don't collide.
   */
  private static long packTile(int x, int absY) {
    return ((long) x << 32) | (absY & 0xFFFFFFFFL);
  }

  /**
   * True if either flanking tile of a boundary wall at {@code (x, absY, dir)} hosts a transport
   * entry. Mirrors the rect from {@code GameObject.getObjectBoundary()}'s {@code isBoundary()}
   * branch (server GameObject.java:85-109):
   * <ul>
   *   <li>dir 0 → flanks {@code (x, absY-1)} and {@code (x, absY)}.</li>
   *   <li>dir 1 → flanks {@code (x-1, absY)} and {@code (x, absY)}.</li>
   *   <li>dir 2/3 → 3×3 box around {@code (x, absY)}.</li>
   * </ul>
   */
  private static boolean boundaryCoveredByTransport(int x, int absY, int dir,
      Set<Long> transportTiles) {
    if (transportTiles.isEmpty()) {
      return false;
    }
    return switch (dir) {
      case 0 -> transportTiles.contains(packTile(x, absY - 1))
                || transportTiles.contains(packTile(x, absY));
      case 1 -> transportTiles.contains(packTile(x - 1, absY))
                || transportTiles.contains(packTile(x, absY));
      case 2, 3 -> {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            if (transportTiles.contains(packTile(x + dx, absY + dy))) {
              yield true;
            }
          }
        }
        yield false;
      }
      default -> transportTiles.contains(packTile(x, absY));
    };
  }

  /**
   * True if any tile inside the scenery's engagement rect (per server's
   * {@code GameObject.getObjectBoundary} typ=2/3 branch) hosts a transport entry. (w, h) are
   * already rotated by the caller for dir not in {0, 4}.
   */
  private static boolean sceneryCoveredByTransport(int anchorX, int anchorAbsY, int dir,
      int w, int h, int typ,
      Set<Long> transportTiles) {
    if (transportTiles.isEmpty()) {
      return false;
    }
    int minX = anchorX, minY = anchorAbsY;
    int worldWidth = w, worldHeight = h;
    if (typ == 2 || typ == 3) {
      switch (dir) {
        case 0 -> {
          worldWidth++;
          minX--;
        }
        case 2 -> {
          worldHeight++;
        }
        case 4 -> {
          worldWidth++;
        }
        case 6 -> {
          worldHeight++;
          minY--;
        }
        default -> { /* defensive */ }
      }
    }
    int maxX = worldWidth + anchorX - 1;
    int maxY = worldHeight + anchorAbsY - 1;
    for (int y = minY; y <= maxY; y++) {
      for (int x = minX; x <= maxX; x++) {
        if (transportTiles.contains(packTile(x, y))) {
          return true;
        }
      }
    }
    return false;
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
}
