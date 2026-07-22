package openrsc.maprender.bake;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.BoundaryLocs;
import openrsc.gamedata.SceneryLocs;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.jag.JagLandscape;
import openrsc.bot.render.WorldRenderer;
import orsc.graphics.three.MeshExporter;
import orsc.graphics.three.RSModel;
import orsc.graphics.three.RegionExporter;

/**
 * Phase-1 world bake: every landscape region × plane, exported as binary mesh
 * cells for the in-app three.js viewer.
 *
 * <p>Cell = one {@code loadRegion} window (2×2 sectors = 96×96 tiles), indexed
 * by the region parameters (a, b) with {@code a,b} stepping by 2 so windows
 * tile the world exactly (sector space x 48..68, y 37..57). Per cell and plane
 * up to four kind files are written — terrain, walls, roofs, scenery — so the
 * viewer can switch floors and toggle roofs by choosing which files to draw.
 *
 * <p>Cell binary (little-endian is NOT used — DataOutputStream big-endian,
 * decoded with DataView in JS):
 * <pre>
 * u32 magic 'RSC3'; u16 version=1; u8 plane; u8 kind;
 * i32 originWorldTileX; i32 originWorldTileZ; u16 groupCount;
 * group: i16 texId; i32 vertCount;
 *   vertCount × (i16 x, i16 y, i16 z)   engine units (128/tile), cell-local
 *   vertCount × u8 shade                engine shade 0..255 (side sign baked)
 *   vertCount × (u8 r, u8 g, u8 b)      base colour (white for textured)
 *   texId >= 0: vertCount × (i16 u×512, i16 v×512)
 * </pre>
 *
 * <p>The viewer's shader applies the verified ramps per fragment: flat
 * colour × ((255−shade)²/65536); textured
 * {1,.875,.75,.625}[(shade>>4)&3] / 2^(shade>>6).
 */
public final class WorldMeshExporter {

  /**
   * Bake FORMAT version, written into manifest.json. Bump when the cell/
   * library format or kind layout changes incompatibly — the controller
   * rebakes automatically when the on-disk version differs.
   * v2: scenery removed from cells; objlib.bin/json added.
   * v3: static boundary (door) edges removed from baked walls; boundaries.json
   *     + doorlib.json added (client-side door assembly + live state).
   * v4: npc-atlas.png/json added (billboard character sprites).
   * v5: npc atlas rebaked with alpha recovery (black clothing was holes;
   *     ghosts now carry true translucency).
   * v6: npc atlas gains combat-stance frames (orders 8/9 = COMBAT_A/COMBAT_B).
   * v7: npc atlas meta carries cs (combatModel) for authentic per-npc combat
   *     animation speed.
   * v8: item-atlas.png/json added (ground-item billboards) + npc atlas meta ws.
   * v9: splat-red/blue.png added (stock hit-splat bubbles) — v8 existed briefly
   *     without them, so a v8 bake can be missing the splat files.
   * v10: font-h12b.png/json added (bold 12px game font glyph atlas for stock
   *     overhead chat bubbles).
   * v11: objlib gains animation frame models + anims index (fires, torches…).
   * v12: npc atlas meta gains atk/lvl/cmd1/cmd2 and doorlib gains cmd1/cmd2
   *      (remote-control context menus).
   * v13: per-layer player-sprite atlas (player-layers/) baked into the tree —
   *      the viewer composites appearances client-side; the old on-demand
   *      server strip service is gone.
   */
  public static final int FORMAT_VERSION = 13;

  /**
   * Kinds 0-3 exist for every plane (that plane's own session, base heights —
   * the in-game "standing on this floor" view). Kinds 4-7 exist only on
   * plane-0 cells: upper-storey wall/roof grids from the GROUND session,
   * which the engine builds at true storey heights (how castle towers are
   * visible from the ground) — drawn in the exterior view.
   */
  public static final String[] KIND_NAMES = {
      "terrain", "walls", "roofs", "scenery", "walls1", "roofs1", "walls2", "roofs2"};

  /** One exported cell: its region params, bot-tile origin, and which kind files exist. */
  private record Cell(int a, int b, int plane, int botX0, int botZ0, List<String> kinds) {}

  /** {@code manifest.json} — bake format + world extent + the cell index. */
  private record Manifest(int version, long baked, int cellTiles, int unitsPerTile, int textures,
                          int botXTiles, int botZTiles, List<Cell> cells) {}

  /** Sector index ranges present in the landscape archive. */
  private static final int SEC_X_MIN = 48;
  private static final int SEC_X_MAX = 68;
  private static final int SEC_Y_MIN = 37;
  private static final int SEC_Y_MAX = 57;

  /** Bot-coordinate offsets (world tile − offset = bot tile). */
  private static final int BOT_X_OFF = 2304; // sector 48 * 48
  private static final int BOT_Z_OFF = 1776; // sector 37 * 48

  public static void main(String[] args) throws Exception {
    String cacheDir = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
    File outDir = new File(args.length > 0 ? args[0] : "/tmp/world-mesh");
    export(cacheDir, outDir, System.out::println);
  }

  public static void export(String cacheDir, File outDir, java.util.function.Consumer<String> log)
      throws Exception {
    outDir.mkdirs();

    var conf = ServerConf.resolve();
    List<SceneryLocs.Loc> scenery = SceneryLocs.load(conf.locs().resolve("SceneryLocs.json"));
    List<BoundaryLocs.Loc> boundaries =
        BoundaryLocs.load(conf.locs().resolve("BoundaryLocs.json"));

    // Terrain from the JAG map archives — the source the SERVER paths
    // against — not the .orsc repack (which carries cosmetic filled-in
    // sections the server knows nothing about). Rev 64 = Uranium
    // based_map_data; matches GameEnvironment's collision loading.
    JagLandscape jag = JagLandscape.open(conf.data().resolve("maps"), 64, true);
    if (jag == null) {
      throw new IllegalStateException("maps64.jag not found under " + conf.data().resolve("maps"));
    }

    WorldRenderer r = new WorldRenderer(cacheDir, 512, 512);
    // Static boundary (door/gate) edges are STRIPPED from the baked walls —
    // the viewer assembles them client-side from boundaries.json so a bot
    // observing a different boundary state (opened door) can swap that edge.
    JagSectorProvider provider = new JagSectorProvider(jag);
    provider.stripBoundaries(boundaries);
    r.setSectorProvider(provider);
    // Animation frame models (firea2, torcha3…) are referenced by NAME at
    // runtime, not by any object def — register them before loadModels()
    // caches by name so the objlib can bake them.
    ObjectLibExporter.registerAnimModels();
    r.loadModels();
    int textures = r.dumpTextures(new File(outDir, "tex"));

    // Object-model library: lets the viewer assemble scenery client-side
    // from placements (and swap live-observed variants — mined rocks, cut
    // trees) instead of shipping scenery fused into the cell meshes.
    r.loadRegion(51 * 48 + 23, 50 * 48 + 23, 0);
    ObjectLibExporter.export(r, outDir, log);
    DoorLibExporter.export(boundaries, outDir, log);
    NpcSpriteAtlasBaker.export(outDir, log);
    ItemSpriteAtlasBaker.export(outDir, log);
    FontAtlasBaker.export(cacheDir, outDir, log);
    // Per-layer player-sprite atlas: the viewer composites any appearance token
    // from this in the browser (no server-side per-token strip service).
    PlayerLayerAtlasBaker.export(cacheDir, new File(outDir, "player-layers").toPath(), log);

    List<Cell> manifestCells = new ArrayList<>();
    long totalBytes = 0;
    int cells = 0;

    // Cells are 48x48-tile windows cut from the INTERIOR (local tiles 24..71)
    // of each 96x96 region load, stepping ONE sector per cell. Interior-only
    // export fixes two seam defects of naive tiling: (a) the engine only
    // builds terrain quads for local tiles 0..94, so a full-window export is
    // missing its last row/column (black gaps between cells); (b) lighting of
    // edge vertices lacks the neighbor faces outside the load. Every interior
    // face has its complete neighborhood, and — with the position-seeded
    // terrain grain in World — two loads covering the same tiles bake
    // bit-identical vertices, so seams vanish.
    final int WIN_MIN = 24;
    final int WIN_MAX = 72;
    for (int plane = 0; plane <= 3; plane++) {
      for (int a = SEC_X_MIN; a <= SEC_X_MAX + 1; a++) {
        for (int b = SEC_Y_MIN; b <= SEC_Y_MAX + 1; b++) {
          if (!cellHasSectors(jag, plane, a, b)) {
            continue;
          }
          try {
            r.loadRegion(a * 48 + 23, b * 48 + 23, plane);
          } catch (RuntimeException e) {
            log.accept("cell p" + plane + " " + a + "," + b + " load failed: " + e);
            continue;
          }

          // Load origin and this cell's window, in bot tiles.
          int loadBotX0 = (a - 49) * 48;
          int loadBotZ0 = (b - 38) * 48;
          int botX0 = loadBotX0 + WIN_MIN;
          int botZ0 = loadBotZ0 + WIN_MIN;

          // Register ALL of this plane's scenery in the load (not just the
          // window) BEFORE terrain export: registration stamps the footprint
          // shadows (vertLightOther 35) onto the terrain vertices, as the
          // live client's spawn handler does — including shadows that spill
          // into the window from objects outside it.
          for (SceneryLocs.Loc loc : scenery) {
            int y = loc.pos().y();
            if (y / 944 != plane) {
              continue;
            }
            int lx = loc.pos().x() - loadBotX0;
            int lz = (y % 944) - loadBotZ0;
            if (lx < 0 || lx >= 96 || lz < 0 || lz >= 96) {
              continue;
            }
            r.world().registerObjectDir(lx, lz, loc.direction());
            r.world().addGameObject_UpdateCollisionMap(lx, lz, loc.id(), false);
          }

          MeshExporter[] byKind = new MeshExporter[KIND_NAMES.length];
          for (int k = 0; k < byKind.length; k++) {
            byKind[k] = new MeshExporter();
            if (k == 3) {
              byKind[k].setOrigin(WIN_MIN * 128, WIN_MIN * 128);
            } else {
              byKind[k].setWindow(WIN_MIN * 128, WIN_MIN * 128, WIN_MAX * 128, WIN_MAX * 128);
            }
          }
          // Terrain: drop RSC's flat-colour water-passthrough bridge decks (the
          // fill=-2 slabs the engine skips) — without this they paint the
          // log-balance obstacles solid black. Textured wooden bridge decks
          // (fill>=0) are KEPT so real bridge floors still render.
          byKind[0].setDropFlatColourDecks(true);
          RegionExporter.exportTerrain(r.world(), byKind[0]);
          RegionExporter.exportWalls(r.world(), byKind[1], plane);
          RegionExporter.exportRoofs(r.world(), byKind[2], plane);
          if (plane == 0) {
            RegionExporter.exportWalls(r.world(), byKind[4], 1);
            RegionExporter.exportRoofs(r.world(), byKind[5], 1);
            RegionExporter.exportWalls(r.world(), byKind[6], 2);
            RegionExporter.exportRoofs(r.world(), byKind[7], 2);
          }
          // Scenery is NOT baked into cells: the viewer assembles it from
          // the object library + placements so live-observed state (mined
          // rocks, cut trees) can replace individual objects. Registration
          // above still stamps the terrain footprint shadows.

          // Un-flattened corner-elevation grid for the window (49x49). The
          // terrain MESH flattens bridge/water tiles (tileValue==4) to y=0 so
          // the water surface draws under the bridge, but the authentic clients
          // (and rsc-c) place scenery/characters at the UN-flattened ground
          // (World.getElevation -> getTileElevation); the viewer needs this grid
          // to sit objects on the bridge deck instead of sinking them to the
          // water. getElevation at an exact tile corner == getTileElevation, and
          // is always a multiple of 3, so it fits one byte (value/3).
          final int GRID = WIN_MAX - WIN_MIN + 1;
          byte[] elevGrid = new byte[GRID * GRID];
          for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
              elevGrid[i * GRID + j] =
                  (byte) (r.elevation((WIN_MIN + i) * 128, (WIN_MIN + j) * 128) / 3);
            }
          }

          List<String> kinds = new ArrayList<>();
          for (int kind = 0; kind < byKind.length; kind++) {
            byte[] bin = encodeCell(byKind[kind], plane, kind,
                (a - 1) * 48 + WIN_MIN, (b - 1) * 48 + WIN_MIN,
                kind == 0 ? elevGrid : null, GRID);
            if (bin == null) {
              continue;
            }
            String name = "c_p" + plane + "_" + a + "_" + b + "_" + KIND_NAMES[kind] + ".bin";
            Files.write(new File(outDir, name).toPath(), bin);
            totalBytes += bin.length;
            kinds.add(KIND_NAMES[kind]);
          }
          if (!kinds.isEmpty()) {
            manifestCells.add(new Cell(a, b, plane, botX0, botZ0, kinds));
            cells++;
          }
        }
      }
      log.accept("plane " + plane + " done (" + cells + " cells so far, "
          + (totalBytes / 1024 / 1024) + " MB)");
    }

    BakeJson.MAPPER.writeValue(new File(outDir, "manifest.json"),
        new Manifest(FORMAT_VERSION, System.currentTimeMillis(), 48, 128, textures,
            (SEC_X_MAX - SEC_X_MIN + 1) * 48, (SEC_Y_MAX - SEC_Y_MIN + 1) * 48, manifestCells));
    log.accept("world mesh export complete: " + cells + " cells, "
        + (totalBytes / 1024 / 1024) + " MB, " + textures + " textures -> " + outDir);
  }

  /** True when at least one of the cell's four sectors exists at this plane. */
  private static boolean cellHasSectors(JagLandscape jag, int plane, int a, int b) {
    for (int dx = -1; dx <= 0; dx++) {
      for (int dy = -1; dy <= 0; dy++) {
        if (jag.exists(plane, a + dx, b + dy)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Encode one kind's groups; null when empty. For the terrain kind a v2 trailer
   * carries the un-flattened corner-elevation grid ({@code elevGrid}, {@code
   * gridDim}×{@code gridDim} bytes = getElevation/3) so the viewer can place
   * scenery on the real ground rather than the bridge-flattened water surface.
   */
  private static byte[] encodeCell(MeshExporter ex, int plane, int kind,
      int originTileX, int originTileZ, byte[] elevGrid, int gridDim) throws IOException {
    List<MeshExporter.Group> groups = new ArrayList<>();
    for (MeshExporter.Group g : ex.groups()) {
      if (!g.positions.isEmpty()) {
        groups.add(g);
      }
    }
    if (groups.isEmpty()) {
      return null;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
    DataOutputStream out = new DataOutputStream(bos);
    out.writeInt(0x52534333); // 'RSC3'
    out.writeShort(2);        // v2: optional un-flattened elevation trailer (terrain)
    out.writeByte(plane);
    out.writeByte(kind);
    out.writeInt(originTileX);
    out.writeInt(originTileZ);
    out.writeShort(groups.size());
    for (MeshExporter.Group g : groups) {
      int n = g.positions.size();
      out.writeShort(g.texture);
      out.writeInt(n);
      for (float[] p : g.positions) {
        out.writeShort(clampI16(Math.round(p[0])));
        out.writeShort(clampI16(Math.round(p[1])));
        out.writeShort(clampI16(Math.round(p[2])));
      }
      for (int s : g.shades) {
        out.writeByte(s);
      }
      for (float[] c : g.bases) {
        out.writeByte(Math.round(c[0] * 255));
        out.writeByte(Math.round(c[1] * 255));
        out.writeByte(Math.round(c[2] * 255));
      }
      if (g.texture >= 0) {
        for (float[] uv : g.uvs) {
          out.writeShort(clampI16(Math.round(uv[0] * 512)));
          out.writeShort(clampI16(Math.round(uv[1] * 512)));
        }
      }
    }
    // v2 terrain trailer: gridDim, then gridDim*gridDim corner elevations
    // (row-major i=cornerX offset, j=cornerZ offset from the cell origin).
    if (kind == 0 && elevGrid != null) {
      out.writeShort(gridDim);
      out.write(elevGrid);
    }
    out.flush();
    return bos.toByteArray();
  }

  private static int clampI16(int v) {
    return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
  }

  private WorldMeshExporter() {}
}
