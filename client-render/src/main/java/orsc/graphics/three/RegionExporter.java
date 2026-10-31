package orsc.graphics.three;

/**
 * Feeds a loaded region's chunk models (terrain, walls, roofs — the same
 * grids the engine renders) into a {@link MeshExporter}. Lives in this
 * package for access to {@link World}'s model grids.
 */
public final class RegionExporter {

    /** Terrain chunks only. */
    public static void exportTerrain(World world, MeshExporter ex) {
        for (RSModel c : world.landscapeChunks()) {
            if (c != null) {
                ex.export(c);
            }
        }
    }

    /** One plane's wall chunks. */
    public static void exportWalls(World world, MeshExporter ex, int plane) {
        for (RSModel c : world.wallChunks(plane)) {
            if (c != null) {
                ex.export(c);
            }
        }
    }

    /** One plane's roof chunks. */
    public static void exportRoofs(World world, MeshExporter ex, int plane) {
        for (RSModel c : world.roofChunks(plane)) {
            if (c != null) {
                ex.export(c);
            }
        }
    }

    /**
     * Export a placed model in ANCHOR-LOCAL coordinates (its own translate —
     * footprint-centre + ground elevation — subtracted; ground at y=0), with
     * the far-flung-junk filter applied. For the object-model library bake.
     */
    public static void exportAnchorLocal(RSModel m, MeshExporter ex) {
        int[] t = m.translateForExport();
        ex.setOrigin3(t[0], t[1], t[2]);
        ex.setAnchorFilter(t[0], t[1], t[2]);
        ex.export(m);
    }

    private RegionExporter() {}
}
