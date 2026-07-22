package orsc.graphics.three;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes {@link RSModel} geometry into flat triangle buffers for a WebGL
 * viewer — the Phase-0 bridge from the ported software engine to the browser.
 *
 * <p>Colors are baked exactly the way the engine rasterizes them (verified
 * against rsc-c's clean reimplementation, {@code scene.c}):
 * <ul>
 *   <li>fill &lt; 0 → packed 5-5-5 RGB: {@code v=-1-fill; r=((v>>10)&31)*8 …}</li>
 *   <li>0 ≤ fill &lt; textureCount → texture id (UV-mapped)</li>
 *   <li>fill = 12345678 → that face side is invisible</li>
 *   <li>vertex shade = ambience(diffuseParam1) ∓ intensity (sign per side) +
 *       vertLightOther; intensities RELIT from transformed geometry for placed
 *       scenery (the engine recomputes lighting on every transform apply).</li>
 *   <li>flat colour = base × ((255−shade)²/65536); textured brightness =
 *       bank[(shade&gt;&gt;4)&amp;3] ∈ {1,.875,.75,.625} halved per 64 shade
 *       (verified pixel-exact against the engine with a gray-palette probe).</li>
 * </ul>
 *
 * <p>Output groups triangles by texture id (−1 = untextured/vertex-colour).
 * Coordinates stay in RSC world units (128/tile), y negative-up as stored;
 * the viewer handles axis conventions.
 */
public final class MeshExporter {

  /** One draw group: a texture id (or −1) plus flat triangle arrays. */
  public static final class Group {
    public final int texture;
    public final List<float[]> positions = new ArrayList<>(); // xyz per vertex
    public final List<float[]> colors = new ArrayList<>();    // rgb 0..1 per vertex (ramp pre-applied)
    public final List<float[]> uvs = new ArrayList<>();       // uv per vertex (textured only)
    /** Raw engine shade 0..255 per vertex — bake this + {@link #bases} and
     * apply the ramps per-fragment in the viewer shader for exact fidelity;
     * {@link #colors} keeps the pre-applied variant for the JSON spike. */
    public final List<Integer> shades = new ArrayList<>();
    public final List<float[]> bases = new ArrayList<>();     // base rgb 0..1 (white for textured)

    Group(int texture) {
      this.texture = texture;
    }
  }

  private final Map<Integer, Group> groups = new HashMap<>();

  // Optional clip window (engine units, half-open): faces whose centroid
  // falls outside are skipped, and exported coordinates are rebased so the
  // window's min corner is the origin. Lets a cell exporter cut an INTERIOR
  // window out of a full region load — every kept face then has its complete
  // neighborhood in the load, so lighting at cell seams is load-independent.
  private boolean windowed;
  private double winMinX;
  private double winMinZ;
  private double winMaxX;
  private double winMaxZ;
  private double winMinXClip;
  private double winMinZClip;

  /** Clip + rebase to [minX,maxX)×[minZ,maxZ) in engine units. */
  public void setWindow(int minX, int minZ, int maxX, int maxZ) {
    this.windowed = true;
    this.winMinX = minX;
    this.winMinZ = minZ;
    this.winMaxX = maxX;
    this.winMaxZ = maxZ;
    this.winMinXClip = minX;
    this.winMinZClip = minZ;
  }

  // Anchor-junk filter (scenery): drop faces whose centroid is absurdly far
  // from the model's own translate anchor. Several authentic models (Stall,
  // Log bridge, tree platform) contain stray quads displaced ~192 tiles from
  // the body — invisible in the fog-bound client, glaring in an
  // infinite-draw-distance viewer.
  private boolean anchorFiltered;
  private double anchorX;
  private double anchorY;
  private double anchorZ;
  private static final double ANCHOR_RADIUS_H = 3072;  // 24 tiles
  private static final double ANCHOR_RADIUS_V = 6144;  // 48 tiles

  /** Enable the anchor-junk filter for the next export(s). */
  public void setAnchorFilter(int x, int y, int z) {
    this.anchorFiltered = true;
    this.anchorX = x;
    this.anchorY = y;
    this.anchorZ = z;
  }

  // Terrain-only: drop the flat-colour "water passthrough" bridge decks.
  // RSC's landscape build (World.buildLandscape / rsc-c's "create bridge floor
  // tiles over water") lays a second, raised deck quad over every water tile
  // (tileValue==4), front-filled with the tile's decoration and back TRANSPARENT.
  // That decoration is EITHER a texture (a real wooden bridge deck — tex#3) OR a
  // flat near-black colour (fill<0, e.g. -2 → rgb(0,0,8)) marking "no deck, show
  // the water below". The authentic rasterizer draws the textured decks but skips
  // the flat-colour ones (verified: swapping fill -2→tex#3 turns the log's black
  // slab into a wooden deck). Our double-sided export drew BOTH, so the -2 decks
  // showed as opaque black slabs. Dropping only the flat-colour (front<0) decks
  // reproduces the engine exactly while KEEPING the textured wooden floors.
  // (First-pass up-facing terrain always front=TRANSPARENT, so front<0 uniquely
  // selects these colour decks.)
  private boolean dropFlatColourDecks;

  /** Terrain export: skip RSC's flat-colour water-passthrough bridge decks. */
  public void setDropFlatColourDecks(boolean drop) {
    this.dropFlatColourDecks = drop;
  }

  /**
   * Rebase only, no clipping — for scenery, which is deduplicated by anchor
   * tile instead: a tree anchored inside the window keeps faces that overhang
   * the window edge (clipping them would cut canopies at cell seams).
   */
  public void setOrigin(int minX, int minZ) {
    this.windowed = true;
    this.winMinX = minX;
    this.winMinZ = minZ;
    this.winMaxX = Double.POSITIVE_INFINITY;
    this.winMaxZ = Double.POSITIVE_INFINITY;
    this.winMinXClip = Double.NEGATIVE_INFINITY;
    this.winMinZClip = Double.NEGATIVE_INFINITY;
  }

  private double originY;

  /** Full 3D rebase (model-library bake: anchor-local coords, ground at 0). */
  public void setOrigin3(int x, int y, int z) {
    setOrigin(x, z);
    this.originY = y;
  }

  public Iterable<Group> groups() {
    return groups.values();
  }

  private Group group(int texture) {
    return groups.computeIfAbsent(texture, Group::new);
  }

  /**
   * Export every visible face side of {@code m}, applying the model's OWN
   * transform fields (dir rotation about Y in 256ths, per-axis scale in
   * 256ths, then translate) — the same order the engine's transform pipeline
   * applies them. Terrain/wall/roof chunk models have identity transforms and
   * world-space vertices; placed scenery carries rotation+translation.
   */
  public void export(RSModel m) {
    int n = m.vertHead;
    double[] xs = new double[n];
    double[] ys = new double[n];
    double[] zs = new double[n];
    double yaw = m.rot256YForExport() * 2.0 * Math.PI / 256.0;
    double cos = Math.cos(yaw);
    double sin = Math.sin(yaw);
    int[] scale = m.scale256ForExport();
    int[] trans = m.translateForExport();
    boolean identity = m.rot256YForExport() == 0
        && scale[0] == 256 && scale[1] == 256 && scale[2] == 256
        && trans[0] == 0 && trans[1] == 0 && trans[2] == 0;
    int[] vertY = m.vertYForExport();
    for (int i = 0; i < n; i++) {
      double x = m.vertX[i];
      double y = vertY[i];
      double z = m.vertZ[i];
      if (!identity) {
        // rotate256 about Y (engine convention), then scale, then translate.
        double rx = x * cos + z * sin;
        double rz = z * cos - x * sin;
        x = rx * scale[0] / 256.0;
        y = y * scale[1] / 256.0;
        z = rz * scale[2] / 256.0;
        x += trans[0];
        y += trans[1];
        z += trans[2];
      }
      xs[i] = x;
      ys[i] = y;
      zs[i] = z;
    }

    // The engine relights on transform apply (normals recomputed from the
    // TRANSFORMED vertices) — the stored intensities are stale recipe-time
    // values for placed scenery. Identity models (terrain/wall/roof chunks)
    // take the reset path and keep their stored lighting.
    int[][] relit = null;
    if (!identity) {
      int[] tix = new int[n];
      int[] tiy = new int[n];
      int[] tiz = new int[n];
      for (int i = 0; i < n; i++) {
        tix[i] = (int) Math.round(xs[i]);
        tiy[i] = (int) Math.round(ys[i]);
        tiz[i] = (int) Math.round(zs[i]);
      }
      relit = m.relitIntensitiesForExport(tix, tiy, tiz);
    }

    for (int f = 0; f < m.faceHead; f++) {
      int[] vs = m.faceIndices[f];
      if (vs == null || vs.length < 3) {
        continue;
      }
      // Water-passthrough deck cull (terrain only): a landscape face whose FRONT
      // is a flat colour (< 0) is one of RSC's "no deck, show water below" bridge
      // quads (fill=-2). The engine skips these but draws textured decks, so drop
      // only the flat-colour ones — textured wooden bridge floors are kept.
      if (dropFlatColourDecks && m.faceTextureFront[f] < 0) {
        continue;
      }
      emitSide(m, relit, vs, xs, ys, zs, m.faceTextureFront[f], f, false);
      emitSide(m, relit, vs, xs, ys, zs, m.faceTextureBack[f], f, true);
    }
  }

  private void emitSide(RSModel m, int[][] relit, int[] vs, double[] xs, double[] ys, double[] zs,
      int fill, int face, boolean reversed) {
    if (fill == 12345678) {
      return; // invisible side
    }
    if (windowed || anchorFiltered) {
      double cx = 0;
      double cy = 0;
      double cz = 0;
      for (int vi : vs) {
        cx += xs[vi];
        cy += ys[vi];
        cz += zs[vi];
      }
      cx /= vs.length;
      cy /= vs.length;
      cz /= vs.length;
      if (windowed
          && (cx < winMinXClip || cx >= winMaxX || cz < winMinZClip || cz >= winMaxZ)) {
        return;
      }
      if (anchorFiltered
          && (Math.abs(cx - anchorX) > ANCHOR_RADIUS_H
              || Math.abs(cz - anchorZ) > ANCHOR_RADIUS_H
              || Math.abs(cy - anchorY) > ANCHOR_RADIUS_V)) {
        return;
      }
    }
    boolean textured = fill >= 0;
    Group g = group(textured ? fill : -1);

    int k = vs.length;
    float[][] col = new float[k][];
    float[][] base = new float[k][];
    int[] shadeArr = new int[k];
    for (int j = 0; j < k; j++) {
      int shade = Math.min(255, Math.max(0, shade(m, relit, face, vs[j], reversed)));
      shadeArr[j] = shade;
      if (textured) {
        // The engine's texture shading, exact (Shader.shadeScanline, verified
        // pixel-for-pixel with a gray-palette probe): palette bank from shade
        // bits 4-5 (the four copies at 1, 1−1/8, 1−1/4, 1−1/4−1/8 brightness,
        // scene_set_texture_pixels), then the whole packed RGB right-shifted
        // by shade>>6 (halving per 64 shade). Same constants in the 64px path.
        double f2 = TEXTURE_BANK[(shade >> 4) & 3] / (1 << (shade >> 6));
        col[j] = new float[]{(float) f2, (float) f2, (float) f2};
        base[j] = new float[]{1f, 1f, 1f};
      } else {
        // Flat/gouraud ramp: quadratic darkness.
        double ramp = 255 - shade;
        double f2 = ramp * ramp / 65536.0;
        int v = -1 - fill;
        base[j] = new float[]{
            (((v >> 10) & 31) * 8) / 255f,
            (((v >> 5) & 31) * 8) / 255f,
            ((v & 31) * 8) / 255f};
        col[j] = new float[]{
            (float) (base[j][0] * f2), (float) (base[j][1] * f2), (float) (base[j][2] * f2)};
      }
    }

    float[][] uv = null;
    if (textured) {
      uv = faceUvs(vs, xs, ys, zs);
    }

    // Fan-triangulate; reversed side flips winding.
    for (int j = 1; j < k - 1; j++) {
      int[] tri = reversed ? new int[]{0, j + 1, j} : new int[]{0, j, j + 1};
      for (int t : tri) {
        int vi = vs[t];
        g.positions.add(new float[]{
            (float) (xs[vi] - (windowed ? winMinX : 0)),
            (float) (ys[vi] - originY),
            (float) (zs[vi] - (windowed ? winMinZ : 0))});
        g.colors.add(col[t]);
        g.shades.add(shadeArr[t]);
        g.bases.add(base[t]);
        if (uv != null) {
          g.uvs.add(uv[t]);
        }
      }
    }
  }

  private static final double[] TEXTURE_BANK = {1.0, 0.875, 0.75, 0.625};

  /**
   * Vertex shade, Scene polygon setup: the diffuse intensity SUBTRACTS for
   * the front-facing side (orientation &lt; 0 — the side you normally see)
   * and ADDS for the back side; the per-vertex ambience term (terrain
   * shadows) always adds. {@code relit} carries intensities recomputed from
   * the transformed geometry (non-null for placed scenery); stored fields
   * are used for identity-transform chunk models.
   */
  private static int shade(RSModel m, int[][] relit, int face, int vertex, boolean backSide) {
    int base = m.diffuseParam1ForExport();
    if (m.faceDiffuseLight[face] == 12345678) {
      int intensity = relit != null ? relit[1][vertex] : m.vertDiffuseLight[vertex];
      return base + (backSide ? intensity : -intensity) + m.vertLightOther[vertex];
    }
    int intensity = relit != null ? relit[0][face] : m.faceDiffuseLight[face];
    return base + (backSide ? intensity : -intensity);
  }

  /**
   * UVs: quads map texture corners in vertex order (the engine's convention
   * for tiles/walls/roofs); other polygons get a planar projection from the
   * first edge, tiling every 128 units.
   */
  private static float[][] faceUvs(int[] vs, double[] xs, double[] ys, double[] zs) {
    int k = vs.length;
    float[][] uv = new float[k][];
    if (k == 4) {
      uv[0] = new float[]{0, 0};
      uv[1] = new float[]{1, 0};
      uv[2] = new float[]{1, 1};
      uv[3] = new float[]{0, 1};
      return uv;
    }
    // Plane basis from v0: u along v0->v1, v = normal x u.
    double[] p0 = {xs[vs[0]], ys[vs[0]], zs[vs[0]]};
    double[] u = norm(sub(p(xs, ys, zs, vs[1]), p0));
    double[] nrm = {0, 1, 0};
    for (int j = 2; j < k; j++) {
      double[] e = sub(p(xs, ys, zs, vs[j]), p0);
      double[] c = cross(u, e);
      if (len(c) > 1e-6) {
        nrm = norm(c);
        break;
      }
    }
    double[] v = cross(nrm, u);
    for (int j = 0; j < k; j++) {
      double[] e = sub(p(xs, ys, zs, vs[j]), p0);
      uv[j] = new float[]{(float) (dot(e, u) / 128.0), (float) (dot(e, v) / 128.0)};
    }
    return uv;
  }

  private static double[] p(double[] xs, double[] ys, double[] zs, int i) {
    return new double[]{xs[i], ys[i], zs[i]};
  }

  private static double[] sub(double[] a, double[] b) {
    return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
  }

  private static double[] cross(double[] a, double[] b) {
    return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]};
  }

  private static double dot(double[] a, double[] b) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
  }

  private static double len(double[] a) {
    return Math.sqrt(dot(a, a));
  }

  private static double[] norm(double[] a) {
    double l = len(a);
    return l < 1e-9 ? a : new double[]{a[0] / l, a[1] / l, a[2] / l};
  }
}
