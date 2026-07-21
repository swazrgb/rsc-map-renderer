package orsc.graphics.three;

/**
 * Feeds a loaded region's chunk models (terrain, walls, roofs — the same
 * grids the engine renders) into a {@link MeshExporter}. Lives in this
 * package for access to {@link World}'s model grids.
 */
public final class RegionExporter {

  /**
   * Export terrain + walls and roofs of ALL storeys into {@code ex}. The
   * engine builds every plane's wall/roof grids at load (that's how castle
   * towers are visible from the ground), already at their storey heights.
   */
  public static void exportRegion(World world, MeshExporter ex,
      boolean walls, boolean roofs) {
    for (RSModel c : world.landscapeChunks()) {
      if (c != null) {
        ex.export(c);
      }
    }
    for (int plane = 0; plane < 4; plane++) {
      if (walls) {
        for (RSModel c : world.wallChunks(plane)) {
          if (c != null) {
            ex.export(c);
          }
        }
      }
      if (roofs) {
        for (RSModel c : world.roofChunks(plane)) {
          if (c != null) {
            ex.export(c);
          }
        }
      }
    }
  }

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
