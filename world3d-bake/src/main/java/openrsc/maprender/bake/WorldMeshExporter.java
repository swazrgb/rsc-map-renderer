package openrsc.maprender.bake;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import openrsc.bot.render.WorldRenderer;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.landscape.LandscapeSource;
import orsc.graphics.three.MeshExporter;
import orsc.graphics.three.RegionExporter;
import tools.jackson.databind.JsonNode;

/**
 * Bakes the landscape into binary mesh cells, with the model library, door library and sprite
 * atlases beside them, and last {@code manifest.json}, which lists the cells.
 *
 * <p>A cell is the 48 by 48 tile interior (local tiles 24 to 71) of a 96 by 96 tile load centred on
 * the corner of sectors {@code (a, b)}; loads overlap, one sector apart. The interior is what the
 * engine builds completely: it makes terrain only for local tiles 0 to 94, and lights each vertex
 * from the faces around it.
 *
 * <p>A cell file, {@code c_p<plane>_<a>_<b>_<kind>.bin}, is big-endian: u32 {@code 0x52534333}; u16
 * format 2; u8 plane; u8 kind; i32 origin x and z, in landscape tiles; u16 group count; per group i16 texture
 * ({@code -1} for none), i32 vertex count n, n times i16 x, y, z in engine units (128 to a tile)
 * from the origin, n times u8 shade, n times u8 r, g, b, then for a texture n times i16 u, v
 * (times 512). A terrain cell ends with u16 grid size g and g times g bytes: the ground height at
 * each tile corner divided by 3, x-major, unflattened where the mesh lowers a bridge's tile to the
 * water.
 */
public final class WorldMeshExporter {

    /** {@code botX0, botZ0} is the cell's first tile in server coordinates; {@code kinds} the files written. */
    private record Cell(int a, int b, int plane, int botX0, int botZ0, List<String> kinds) {}

    private record Manifest(
            int version,
            String world,
            String landscapeFile,
            long baked,
            int cellTiles,
            int unitsPerTile,
            int textures,
            int botXTiles,
            int botZTiles,
            List<Cell> cells) {}

    /**
     * The format the manifest records. {@link #bakeIsCurrent} compares only this number, so it must
     * change whenever what the bake writes does.
     */
    public static final int FORMAT_VERSION = 24;

    /** The world of a manifest that names none. */
    private static final String UNSTAMPED_WORLD = WorldProfile.authentic().name();

    /**
     * Whether {@code outDir} holds a finished bake of this format for {@code world}, made on
     * {@code landscapeFile} ({@code null} for the world's own landscape). Only the manifest is read:
     * {@link #export} deletes it first and writes it last. Any read failure answers {@code false}.
     */
    public static boolean bakeIsCurrent(Path outDir, WorldProfile world, Path landscapeFile) {
        try {
            Path mf = outDir.resolve("manifest.json");
            if (!Files.isRegularFile(mf)) {
                return false;
            }
            JsonNode manifest = BakeJson.MAPPER.readTree(Files.readAllBytes(mf));
            if (manifest.path("version").asInt(-1) != FORMAT_VERSION) {
                return false;
            }
            String bakedWorld = string(manifest.path("world"));
            return (bakedWorld != null ? bakedWorld : UNSTAMPED_WORLD).equals(world.name())
                    && Objects.equals(string(manifest.path("landscapeFile")), stamp(landscapeFile));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The world {@code outDir}'s finished bake is of, or {@code null} when it holds none. */
    public static String bakedWorld(Path outDir) throws IOException {
        Path mf = outDir.resolve("manifest.json");
        if (!Files.isRegularFile(mf)) {
            return null;
        }
        String world = string(BakeJson.MAPPER.readTree(Files.readAllBytes(mf)).path("world"));
        return world != null ? world : UNSTAMPED_WORLD;
    }

    private static String string(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asString();
    }

    private static String stamp(Path landscapeFile) {
        return landscapeFile == null
                ? null
                : landscapeFile.toAbsolutePath().normalize().toString();
    }

    // Kind 3 is never written: the viewer places scenery from the model library. Kinds 4 to 7 are
    // the walls and roofs of floors 1 and 2 as the ground floor's load builds them, at their height.
    private static final String[] KIND_NAMES = {
        "terrain", "walls", "roofs", "scenery", "walls1", "roofs1", "walls2", "roofs2"
    };

    // The sector range the bake covers, inclusive.
    private static final int SEC_X_MIN = 48;

    private static final int SEC_X_MAX = 68;
    private static final int SEC_Y_MIN = 37;
    private static final int SEC_Y_MAX = 57;

    /** Rebakes only the mesh: {@code WorldMeshExporter [outDir] [clientCacheDir]}. */
    public static void main(String[] args) throws Exception {
        String cacheDir = args.length > 1 ? args[1] : "../../openrsc/Client_Base/Cache";
        File outDir = new File(args.length > 0 ? args[0] : "/tmp/world-mesh");
        export(cacheDir, outDir, System.out::println);
    }

    /** Bakes the world {@link WorldProfile#resolve} selects. */
    public static void export(String cacheDir, File outDir, Consumer<String> log) throws Exception {
        export(cacheDir, outDir, log, WorldProfile.resolve(ServerConf.resolve()));
    }

    /**
     * Bakes {@code world} on the landscape {@code LandscapeSource.resolve} picks. The manifest records
     * no landscape file, even when a property names one.
     */
    public static void export(String cacheDir, File outDir, Consumer<String> log, WorldProfile world) throws Exception {
        var conf = ServerConf.resolve();
        try (LandscapeSource landscape = LandscapeSource.resolve(conf, world)) {
            export(cacheDir, outDir, log, conf, world, landscape, null);
        }
    }

    /**
     * Bakes {@code world} on {@code landscape}.
     *
     * @param landscapeFile the file {@code landscape} was opened from when it is not the world's own,
     *     else {@code null}; the manifest records it for {@link #bakeIsCurrent}
     */
    public static void export(
            String cacheDir,
            File outDir,
            Consumer<String> log,
            ServerConf conf,
            WorldProfile world,
            LandscapeSource landscape,
            Path landscapeFile)
            throws Exception {
        outDir.mkdirs();
        Files.deleteIfExists(outDir.toPath().resolve("manifest.json"));
        log.accept("world: " + world);
        List<SceneryLocs.Loc> scenery = new ArrayList<>();
        for (var p : world.sceneryLocs(conf)) {
            scenery.addAll(SceneryLocs.load(p));
        }
        List<BoundaryLocs.Loc> boundaries = new ArrayList<>();
        for (var p : world.boundaryLocs(conf)) {
            boundaries.addAll(BoundaryLocs.load(p));
        }

        log.accept("terrain source: " + landscape.describe());
        export(cacheDir, outDir, log, world, stamp(landscapeFile), landscape, scenery, boundaries);
    }

    private static void export(
            String cacheDir,
            File outDir,
            Consumer<String> log,
            WorldProfile world,
            String landscapeFile,
            LandscapeSource landscape,
            List<SceneryLocs.Loc> scenery,
            List<BoundaryLocs.Loc> boundaries)
            throws Exception {
        WorldRenderer r = new WorldRenderer(cacheDir, 512, 512);
        LandscapeSectorProvider provider = new LandscapeSectorProvider(landscape);
        provider.stripBoundaries(boundaries);
        r.setSectorProvider(provider);
        ObjectLibExporter.registerAnimModels();
        r.loadModels();
        int textures = r.dumpTextures(new File(outDir, "tex"));

        r.loadRegion(51 * 48 + 23, 50 * 48 + 23, 0);
        ObjectLibExporter.export(r, outDir, log);
        DoorLibExporter.export(boundaries, outDir, log);
        NpcSpriteAtlasBaker.export(outDir, log);
        ItemSpriteAtlasBaker.export(outDir, log);
        FontAtlasBaker.export(cacheDir, outDir, log);
        PlayerLayerAtlasBaker.export(cacheDir, new File(outDir, "player-layers").toPath(), log);

        List<Cell> manifestCells = new ArrayList<>();
        long totalBytes = 0;
        int cells = 0;

        final int WIN_MIN = 24;
        final int WIN_MAX = 72;
        for (int plane = 0; plane <= 3; plane++) {
            for (int a = SEC_X_MIN; a <= SEC_X_MAX + 1; a++) {
                for (int b = SEC_Y_MIN; b <= SEC_Y_MAX + 1; b++) {
                    if (!cellHasSectors(landscape, plane, a, b)) {
                        continue;
                    }
                    try {
                        // Terrain from a load with every boundary in place; see setStripping.
                        provider.setStripping(false);
                        r.loadRegion(a * 48 + 23, b * 48 + 23, plane);
                    } catch (RuntimeException e) {
                        log.accept("cell p" + plane + " " + a + "," + b + " load failed: " + e);
                        continue;
                    }

                    int loadBotX0 = (a - 49) * 48;
                    int loadBotZ0 = (b - 38) * 48;
                    int botX0 = loadBotX0 + WIN_MIN;
                    int botZ0 = loadBotZ0 + WIN_MIN;

                    // Placed before the terrain is exported, since placing an object shades the terrain
                    // round its footprint, objects just outside the window included.
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
                    byKind[0].setDropVoidFills(true);
                    RegionExporter.exportTerrain(r.world(), byKind[0]);

                    final int GRID = WIN_MAX - WIN_MIN + 1;
                    byte[] elevGrid = new byte[GRID * GRID];
                    for (int i = 0; i < GRID; i++) {
                        for (int j = 0; j < GRID; j++) {
                            elevGrid[i * GRID + j] = (byte) (r.elevation((WIN_MIN + i) * 128, (WIN_MIN + j) * 128) / 3);
                        }
                    }

                    try {
                        provider.setStripping(true);
                        r.loadRegion(a * 48 + 23, b * 48 + 23, plane);
                    } catch (RuntimeException e) {
                        log.accept("cell p" + plane + " " + a + "," + b + " wall pass failed: " + e);
                        continue;
                    }
                    RegionExporter.exportWalls(r.world(), byKind[1], plane);
                    RegionExporter.exportRoofs(r.world(), byKind[2], plane);
                    if (plane == 0) {
                        RegionExporter.exportWalls(r.world(), byKind[4], 1);
                        RegionExporter.exportRoofs(r.world(), byKind[5], 1);
                        RegionExporter.exportWalls(r.world(), byKind[6], 2);
                        RegionExporter.exportRoofs(r.world(), byKind[7], 2);
                    }

                    List<String> kinds = new ArrayList<>();
                    for (int kind = 0; kind < byKind.length; kind++) {
                        byte[] bin = encodeCell(
                                byKind[kind],
                                plane,
                                kind,
                                (a - 1) * 48 + WIN_MIN,
                                (b - 1) * 48 + WIN_MIN,
                                kind == 0 ? elevGrid : null,
                                GRID);
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
            log.accept("plane " + plane + " done (" + cells + " cells so far, " + (totalBytes / 1024 / 1024) + " MB)");
        }

        BakeJson.MAPPER.writeValue(
                new File(outDir, "manifest.json"),
                new Manifest(
                        FORMAT_VERSION,
                        world.name(),
                        landscapeFile,
                        System.currentTimeMillis(),
                        48,
                        128,
                        textures,
                        (SEC_X_MAX - SEC_X_MIN + 1) * 48,
                        (SEC_Y_MAX - SEC_Y_MIN + 1) * 48,
                        manifestCells));
        log.accept("world mesh export complete: " + cells + " cells, " + (totalBytes / 1024 / 1024) + " MB, " + textures
                + " textures -> " + outDir);
    }

    private static boolean cellHasSectors(LandscapeSource landscape, int plane, int a, int b) {
        for (int dx = -1; dx <= 0; dx++) {
            for (int dy = -1; dy <= 0; dy++) {
                if (landscape.exists(plane, a + dx, b + dy)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] encodeCell(
            MeshExporter ex, int plane, int kind, int originTileX, int originTileZ, byte[] elevGrid, int gridDim)
            throws IOException {
        List<MeshExporter.Group> groups = new ArrayList<>();
        for (MeshExporter.Group g : ex.groups()) {
            if (!g.positions.isEmpty()) {
                groups.add(g);
            }
        }
        if (groups.isEmpty() && elevGrid == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0x52534333);
        out.writeShort(2);
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
