package openrsc.bot.render;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.model.Sprite;
import com.openrsc.data.DataFileDecrypter;
import com.openrsc.data.DataOperations;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import orsc.Config;
import orsc.graphics.three.RSModel;
import orsc.graphics.three.Scene;
import orsc.graphics.three.World;
import orsc.graphics.two.GraphicsController;
import orsc.graphics.two.HeadlessSurface;

/**
 * Headless driver for the ported OpenRSC software renderer.
 *
 * <p>Builds a {@link GraphicsController} pixel buffer (no AWT/applet), a
 * {@link Scene}, and a {@link World}, loads a 96×96 landscape region from the
 * {@code Authentic_Landscape.orsc} cache, points a camera at it, rasterizes,
 * and exposes the result as a {@link BufferedImage}.
 *
 * <p>This is the Tier-1 proof: it reproduces the stock client's login-screen
 * world render (an oblique fly-over of the Lumbridge sector) to validate the
 * offscreen render path end-to-end before we retarget the camera to a
 * top-down map projection and add scenery models.
 */
public final class WorldRenderer {

  /** RSC world units per tile. */
  public static final int TILE = 128;

  /** Sprite-archive base index of the ground/wall texture sprites. */
  private static final int SPRITE_TEXTURE = 3225;

  private final GraphicsController surface;
  private final Scene scene;
  private final World world;

  /** When true, fill sentinel hairlines left by the ortho texturer (see render). */
  private boolean inpaint = true;
  /** When true, draw floor-plan wall lines over the render (walls are edge-on otherwise). */
  private boolean drawWalls = false;
  /** When true, fill transparent (foliage) texels with the texture's dominant colour. */
  private boolean fillTransparentTexels = System.getenv("FILL_GAPS") != null;
  /** RSC minimap wall colour (0x606078 grey). */
  private static final Color WALL_COLOR = new Color(6316128);
  /** Perspective focal divisor (right-shift on depth). */
  private int projShift = 8;
  /** Projection scale (focal numerator, {@code m_A}); larger = region fills more pixels. */
  private int projScale;

  private static boolean defsLoaded;

  /**
   * @param cacheDir directory containing {@code video/Authentic_Landscape.orsc}
   *     and {@code video/Authentic_Sprites.orsc}
   */
  public WorldRenderer(String cacheDir, int width, int height) {
    Config.F_CACHE_DIR = cacheDir;
    Config.S_WANT_CUSTOM_SPRITES = false;
    Config.S_WANT_CUSTOM_LANDSCAPE = false;

    // EntityHandler holds all tile/elevation/door/object defs as compiled-in
    // tables (no cache file); load once per JVM.
    if (!defsLoaded) {
      EntityHandler.load(false);
      defsLoaded = true;
    }

    // spriteCount 4501 mirrors the stock client's sprite-archive sizing.
    this.surface = new HeadlessSurface(width, height, 4501);
    this.scene = new Scene(this.surface, 25000, 50000, 1000);
    this.world = new World(this.scene, this.surface);

    // Viewport / projection setup (mirrors mudclient.setMidpoints with the
    // in-game projection scale m_qd = 64). Allocates the scanline buffer and
    // sets the screen-space midpoint, so it must precede any render.
    this.projScale = width / 2;
    applyProjection();

    loadTextures();
  }

  /**
   * Load the scene's texture table. Prefers the custom spritepack path when
   * {@code video/Custom_Sprites.osar} is present — that's what a live OpenRSC
   * client (e.g. Uranium) runs, and its "textures" pack is LARGER than the
   * stock 55-entry table: custom scenery models (fruit trees, berry bushes,
   * xmas tree, …) reference texture indices ≥ 55 and crash the rasterizer on
   * the smaller authentic table. Falls back to the authentic archive
   * (mirroring {@code mudclient.loadTexturesAuthentic()}) when no osar exists.
   */
  private void loadTextures() {
    if (surface.fillSpriteTree() && surface.spriteTree.get("textures") != null) {
      loadTexturesCustom();
    } else {
      loadTexturesAuthentic();
    }
  }

  /** Mirrors {@code mudclient.loadTextures()} (the custom-spritepack variant). */
  private void loadTexturesCustom() {
    var textures = surface.spriteTree.get("textures");
    int count = textures.size();
    scene.setFrustum(0, 11, 7, count);
    for (int i = 0; i < count; i++) {
      var entry = textures.get(String.valueOf(i));
      if (entry == null || entry.getFrames().length < 1) {
        throw new IllegalStateException("Missing custom texture " + i + " in Custom_Sprites.osar");
      }
      quantizeAndLoadTexture(i, entry.getFrames()[0].getSprite());
    }
    System.out.println("[WorldRenderer] custom textures loaded: " + count);
  }

  /**
   * Quantize each ground/wall texture sprite to a 256-colour dictionary and
   * register it with the scene's texture cache. Mirrors the stock client's
   * {@code mudclient.loadTexturesAuthentic()} — required before terrain
   * generation, since overlay tiles (water, paths, bridges) resolve their
   * colour through {@code Scene.resourceToColor} against these textures.
   */
  private void loadTexturesAuthentic() {
    int count = EntityHandler.textureCount();
    // Sizes the scene's texture-cache arrays (resourceDatabase, m_ec, m_i, ...).
    scene.setFrustum(0, 11, 7, count);

    for (int i = 0; i < count; i++) {
      if (!surface.loadSprite(SPRITE_TEXTURE + i, "texture")) {
        throw new IllegalStateException("Missing texture sprite " + i + " in cache");
      }
      quantizeAndLoadTexture(i, surface.sprites[SPRITE_TEXTURE + i]);
    }
  }

  /** The client's shared texture quantization (identical in both loadTextures variants). */
  private void quantizeAndLoadTexture(int i, Sprite sprite) {
    {
      int length = sprite.getWidth() * sprite.getHeight();
      int[] pixels = sprite.getPixels();

      int[] histogram = new int[32768];
      for (int k = 0; k < length; k++) {
        histogram[((pixels[k] & 0xf80000) >> 9) + ((pixels[k] & 0xf800) >> 6) + ((pixels[k] & 0xf8) >> 3)]++;
      }
      for (int p = 0; p < pixels.length; ++p) {
        if (pixels[p] == 0x000000) {
          pixels[p] = 16711935; // magenta = transparent key
        }
      }

      int[] dictionary = new int[256];
      dictionary[0] = 0xff00ff;
      int[] temp = new int[256];
      for (int i1 = 0; i1 < histogram.length; i1++) {
        int j1 = histogram[i1];
        if (j1 > temp[255]) {
          for (int k1 = 1; k1 < 256; k1++) {
            if (j1 <= temp[k1]) {
              continue;
            }
            for (int i2 = 255; i2 > k1; i2--) {
              dictionary[i2] = dictionary[i2 - 1];
              temp[i2] = temp[i2 - 1];
            }
            dictionary[k1] = ((i1 & 0x7c00) << 9) + ((i1 & 0x3e0) << 6) + ((i1 & 0x1f) << 3) + 0x40404;
            temp[k1] = j1;
            break;
          }
        }
        histogram[i1] = -1;
      }

      byte[] indices = new byte[length];
      for (int l1 = 0; l1 < length; l1++) {
        int j2 = pixels[l1];
        int k2 = ((j2 & 0xf80000) >> 9) + ((j2 & 0xf800) >> 6) + ((j2 & 0xf8) >> 3);
        int l2 = histogram[k2];
        if (l2 == -1) {
          int best = 0x3b9ac9ff;
          int r = j2 >> 16 & 0xff;
          int g = j2 >> 8 & 0xff;
          int b = j2 & 0xff;
          for (int c = 0; c < 256; c++) {
            int d = dictionary[c];
            int dr = r - (d >> 16 & 0xff);
            int dg = g - (d >> 8 & 0xff);
            int db = b - (d & 0xff);
            int dist = dr * dr + dg * dg + db * db;
            if (dist < best) {
              best = dist;
              l2 = c;
            }
          }
          histogram[k2] = l2;
        }
        indices[l1] = (byte) l2;
      }
      if (fillTransparentTexels && temp[1] > 0) {
        // Fill transparent texels (palette index 0, the magenta key) with the
        // texture's dominant opaque colour (index 1, the most-common bucket).
        // Kills canopy/foliage see-through without altering texture detail; the
        // model geometry already fully covers the silhouette (proven solid).
        for (int l1 = 0; l1 < length; l1++) {
          if (indices[l1] == 0) {
            indices[l1] = (byte) 1;
          }
        }
      }
      scene.loadTexture(i, dictionary, sprite.getSomething1() / 64 - 1, indices);
    }
  }

  /**
   * Inject a terrain source (e.g. the bot's JAG decode). Must be called before
   * {@link #loadRegion}. When set, the renderer ignores the {@code .orsc}
   * landscape entirely.
   */
  public void setSectorProvider(orsc.graphics.three.SectorProvider provider) {
    world.setSectorProvider(provider);
  }

  /**
   * Configure the projection: viewport scanlines, screen centre, focal divisor
   * ({@code rot1024_vp_src} = {@link #projShift}) and focal scale ({@code m_A} =
   * {@link #projScale}). Screen centre stays at the true frame midpoint while
   * the scale is independent, so the region can fill the frame at full
   * resolution. {@code shift} must be &lt;32 (Java masks shift counts to 5 bits;
   * a value like 64 silently becomes {@code >>0} = no perspective division).
   */
  private void applyProjection() {
    int halfW = surface.width2 / 2;
    int halfH = surface.height2 / 2;
    scene.setMidpoints(halfH, true, surface.width2, projScale, halfH, projShift, halfW);
  }

  /** Set the perspective focal divisor. Larger zoom needs larger shift to keep framing. */
  public void setProjectionShift(int shift) {
    this.projShift = shift;
    applyProjection();
  }

  /**
   * Switch to orthographic projection (zero parallax — a true top-down view) at
   * the given world→screen scale (8.8 fixed point: screen px per world unit
   * &times; 256). One tile is 128 units, so px/tile = scale/2; the 96-tile
   * region spans {@code 48 * scale} px. Pass 0 to return to perspective.
   */
  public void setOrthographic(int scale) {
    scene.orthoScale = scale;
  }

  /** Toggle the sentinel-hairline inpaint pass (on by default). */
  public void setInpaint(boolean enabled) {
    this.inpaint = enabled;
  }

  /**
   * Set the projection scale ({@code m_A}). Larger = the region projects to more
   * pixels (higher map resolution). Independent of framing shape and flatness.
   */
  public void setProjectionScale(int scale) {
    this.projScale = scale;
    applyProjection();
  }

  /**
   * Load the 3D scenery models from {@code models.orsc} into the world's model
   * cache (indexed by {@code GameObjectDef.modelID}). Required before
   * {@link #placeObject}. Mirrors {@code mudclient.loadModels()}.
   */
  public void loadModels() {
    loadModels(false);
  }

  /**
   * @param doubleSided duplicate every face reversed so geometry is visible from
   *     both sides. The live client does NOT do this — its scenery renders fine
   *     from straight down without it (proven in-game at pitch 768), and
   *     double-siding is what produced the black-disc trees in the earlier
   *     overhead-map attempt. Keep off for live-fidelity sprites.
   */
  public void loadModels(boolean doubleSided) {
    byte[] archive = unpackArchive(Config.F_CACHE_DIR + File.separator + "video" + File.separator + "models.orsc");
    int count = EntityHandler.getModelCount();
    RSModel[] cache = new RSModel[count];
    int loaded = 0;
    for (int j = 0; j < count; j++) {
      int offset = DataOperations.getDataFileOffset(EntityHandler.getModelName(j) + ".ob3", archive);
      if (offset == 0) {
        cache[j] = new RSModel(1, 1);
      } else {
        cache[j] = new RSModel(archive, offset, true);
        if (doubleSided) {
          cache[j].makeDoubleSided();
        }
        loaded++;
      }
    }
    world.setModelCache(cache);
    System.out.println("[WorldRenderer] models.orsc archive=" + archive.length
        + " bytes; modelCount=" + count + "; loaded=" + loaded);
  }

  /**
   * Place a scenery object's 3D model at a region-local tile (0..95). Region
   * must be loaded and {@link #loadModels} called first.
   */
  public void placeObject(int id, int localX, int localZ, int direction) {
    world.placeObject(id, localX, localZ, direction);
  }

  /**
   * Place a scenery object with the live client's exact spawn recipe (see
   * {@link orsc.graphics.three.World#placeObjectClient}); pair with
   * {@link #removeLandscape} for isolated transparent-background sprites.
   */
  public RSModel placeObjectClient(int id, int localX, int localZ, int direction) {
    return world.placeObjectClient(id, localX, localZ, direction);
  }

  /** Model-override placement (scenery animation frames — firea2, torcha3…). */
  public RSModel placeObjectClient(int id, int localX, int localZ, int direction, int modelId) {
    return world.placeObjectClient(id, localX, localZ, direction, modelId);
  }

  /** Remove a model previously placed via {@link #placeObjectClient}. */
  public void removeModel(RSModel m) {
    world.removeModel(m);
  }

  /** The engine World — for the mesh exporters (browser-viewer bake). */
  public orsc.graphics.three.World world() {
    return world;
  }

  /**
   * Dump the loaded texture set as t{i}.png (transparent key → alpha 0) for
   * the WebGL viewer. Uses the custom spritepack when present, mirroring
   * {@link #loadTextures()}'s source selection.
   */
  public int dumpTextures(java.io.File dir) throws java.io.IOException {
    dir.mkdirs();
    var textures = surface.spriteTree.get("textures");
    if (textures == null) {
      throw new IllegalStateException("texture dump currently requires the custom spritepack");
    }
    int count = textures.size();
    for (int i = 0; i < count; i++) {
      Sprite s = textures.get(String.valueOf(i)).getFrames()[0].getSprite();
      int w = s.getWidth();
      int h = s.getHeight();
      int[] px = s.getPixels();
      BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
      for (int p = 0; p < w * h; p++) {
        int c = px[p];
        boolean transparent = c == 0 || (c & 0xFFFFFF) == 0xFF00FF;
        img.setRGB(p % w, p / w, transparent ? 0 : 0xFF000000 | c);
      }
      ImageIO.write(img, "png", new java.io.File(dir, "t" + i + ".png"));
    }
    return count;
  }

  /** Remove the ground/terrain geometry so only placed models rasterize. */
  public void removeLandscape() {
    world.removeLandscape();
  }

  /** Terrain elevation (world units) at local world-unit coords. */
  public int elevation(int worldX, int worldZ) {
    return world.getElevation(worldX, worldZ);
  }

  /**
   * Render with an explicit camera into an ARGB image: sentinel background →
   * fully transparent, everything else opaque. No inpainting (a transparent
   * pixel inside a canopy is legitimate — the map shows through, exactly like
   * the ground does in the live client).
   */
  public BufferedImage renderArgb(
      int camX, int camZ, int yElevationBias, int pitch1024, int yaw1024, int roll1024, int zoom) {
    boolean prevInpaint = this.inpaint;
    this.inpaint = false;
    try {
      render(camX, camZ, yElevationBias, pitch1024, yaw1024, roll1024, zoom);
    } finally {
      this.inpaint = prevInpaint;
    }
    int w = surface.width2;
    int h = surface.height2;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    int[] row = new int[w];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int p = surface.pixelData[y * w + x];
        row[x] = p == SENTINEL ? 0 : 0xFF000000 | p;
      }
      img.setRGB(0, y, w, 1, row, 0, w);
    }
    return img;
  }

  /**
   * Drop roofs and upper-storey walls so the overhead map of {@code floor} shows
   * building footprints (ground-floor walls) rather than rooftops or the walls
   * and chimneys of the storeys above it.
   */
  public void removeRoofs(int floor) {
    world.removeRoofs(floor);
  }

  /** Remove all wall geometry (edge-on shadows); pair with {@link #setDrawWalls}. */
  public void removeAllWalls() {
    world.removeAllWalls();
  }

  /** Diagnostic: classify a scenery model's geometry. */
  public void debugModelExtents(int objectId) {
    world.debugModelExtents(objectId);
  }

  /** Set the diffuse-light args for placed scenery (light from above for overhead maps). */
  public void setSceneryLight(int v1, int v2, int dirY, int v4, int dirX, int dirZ) {
    world.setSceneryLight(v1, v2, dirY, v4, dirX, dirZ);
  }

  /** Draw floor-plan wall lines over the render (on by default off). */
  public void setDrawWalls(boolean enabled) {
    this.drawWalls = enabled;
  }

  /** Diagnostic: remove arbitrary roof/wall grids by floor. */
  public void hideGrids(boolean[] roofFloors, boolean[] wallFloors) {
    world.hideGrids(roofFloors, wallFloors);
  }

  /** Read a stock-client {@code .orsc} blob: 6-byte (decmpLen, cmpLen) header + (bzip2) payload. */
  private static byte[] unpackArchive(String path) {
    try {
      byte[] raw = Files.readAllBytes(Path.of(path));
      int decmpLen = ((raw[0] & 0xFF) << 16) | ((raw[1] & 0xFF) << 8) | (raw[2] & 0xFF);
      int cmpLen = ((raw[3] & 0xFF) << 16) | ((raw[4] & 0xFF) << 8) | (raw[5] & 0xFF);
      byte[] payload = Arrays.copyOfRange(raw, 6, 6 + cmpLen);
      if (cmpLen != decmpLen) {
        byte[] out = new byte[decmpLen];
        DataFileDecrypter.unpackData(out, decmpLen, payload, cmpLen, 0);
        return out;
      }
      return payload;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read model archive: " + path, e);
    }
  }

  /**
   * Load the landscape sectors around the given tile coordinate.
   *
   * @param tileX world tile X (sector = tileX/48)
   * @param tileZ world tile Z
   * @param plane floor (0..3)
   */
  public void loadRegion(int tileX, int tileZ, int plane) {
    world.loadSections(tileX, tileZ, plane);
  }

  /**
   * Render with an explicit camera and return the pixel buffer as an image.
   *
   * @param camX,camZ camera target in LOCAL region units (0..96*128), as the
   *     stock login screen uses (independent of which sector was loaded)
   * @param pitch1024,yaw1024,roll1024 Euler angles in 1024ths of a turn
   * @param zoom camera pull-back distance
   */
  public BufferedImage render(
      int camX, int camZ, int yElevationBias, int pitch1024, int yaw1024, int roll1024, int zoom) {
    // Fog distance must exceed the camera-to-far-geometry distance (≈ zoom plus
    // the region's depth spread) so geometry isn't culled. The far-clip frustum
    // corner is m_A*fog>>shift (Scene.endScene ~2559); at the large shifts used
    // for high-camera (flat) renders that stays bounded, so fog can track zoom
    // directly instead of being capped. A flat margin covers the region spread.
    int fog = zoom + 24000;
    scene.fogLandscapeDistance = fog;
    scene.fogEntityDistance = fog;
    scene.fogZFalloff = 1;
    scene.fogSmoothingStartDistance = fog;

    // Clear to a sentinel so the 3D scene's true coverage is unambiguous
    // (anything left as sentinel = not covered by a 3D polygon; e.g. the
    // minimap raster generateLandscapeModel draws into the top-left).
    Arrays.fill(surface.pixelData, SENTINEL);

    int camY = -world.getElevation(camX, camZ) + yElevationBias;
    scene.setCamera(camX, camY, camZ, pitch1024, yaw1024, roll1024, zoom);
    scene.endScene(0);

    if (inpaint) {
      inpaintSentinel(surface.pixelData, surface.width2, surface.height2);
    }
    BufferedImage img = toImage();
    if (drawWalls) {
      // World→screen scale for ground-level points. Ortho: orthoScale/256.
      // Perspective straight-down: 2^shift/depth, with depth ≈ zoom for the
      // (uniform-depth) ground plane — so the overlay stays a linear map.
      double s = scene.orthoScale != 0
          ? scene.orthoScale / 256.0
          : (double) (1 << projShift) / zoom;
      drawWallOverlay(img, camX, camZ, s);
    }
    return img;
  }

  /**
   * Draw the region's walls as floor-plan lines, projected with the same
   * straight-down orthographic transform as the 3D pass so they sit exactly on
   * the building floors. World→screen (pitch 768, yaw 0): {@code px = cx +
   * (worldX-camX)*s}, {@code py = cy - (worldZ-camZ)*s}, with {@code s =
   * orthoScale/256} and {@code (cx,cy)} the frame centre. Z is screen-inverted.
   */
  private void drawWallOverlay(BufferedImage img, int camX, int camZ, double s) {
    java.util.List<int[]> segments = world.collectWallSegments();
    double cx = surface.width2 / 2.0;
    double cy = surface.height2 / 2.0;
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(WALL_COLOR);
    g.setStroke(new BasicStroke(Math.max(1f, (float) (s * 128 * 0.18))));
    for (int[] seg : segments) {
      int x1 = (int) Math.round(cx + (seg[0] * (double) TILE - camX) * s);
      int y1 = (int) Math.round(cy - (seg[1] * (double) TILE - camZ) * s);
      int x2 = (int) Math.round(cx + (seg[2] * (double) TILE - camX) * s);
      int y2 = (int) Math.round(cy - (seg[3] * (double) TILE - camZ) * s);
      g.drawLine(x1, y1, x2, y2);
    }
    g.dispose();
  }

  /** Pre-multiplied sentinel: never produced by the texturer (it skips this key). */
  private static final int SENTINEL = 0x00FF00FF;

  /**
   * Fill the 1-px sentinel hairlines that the texturer leaves where ortho U/V
   * drift samples a transparent texel at a polygon edge. A sentinel pixel with
   * enough non-sentinel 8-neighbours is an interior gap → replace it with their
   * average. Pixels in the large exterior block keep the sentinel (too few real
   * neighbours), so they remain croppable for tiling. A couple of passes close
   * 1–2 px seams without materially eroding the exterior boundary.
   */
  private static void inpaintSentinel(int[] px, int w, int h) {
    for (int pass = 0; pass < 8; pass++) {
      int[] src = px.clone();
      boolean changed = false;
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
          int i = y * w + x;
          if (src[i] != SENTINEL) {
            continue;
          }
          int r = 0, g = 0, b = 0, n = 0;
          for (int dy = -1; dy <= 1; dy++) {
            int ny = y + dy;
            if (ny < 0 || ny >= h) {
              continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
              int nx = x + dx;
              if ((dx == 0 && dy == 0) || nx < 0 || nx >= w) {
                continue;
              }
              int c = src[ny * w + nx];
              if (c == SENTINEL) {
                continue;
              }
              r += (c >> 16) & 0xFF;
              g += (c >> 8) & 0xFF;
              b += c & 0xFF;
              n++;
            }
          }
          if (n >= 1) {
            px[i] = ((r / n) << 16) | ((g / n) << 8) | (b / n);
            changed = true;
          }
        }
      }
      if (!changed) {
        break;
      }
    }
  }

  private BufferedImage toImage() {
    int w = surface.width2;
    int h = surface.height2;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    // surface.pixelData is packed 0x00RRGGBB, row-major.
    img.setRGB(0, 0, w, h, surface.pixelData, 0, w);
    return img;
  }

  /** Proof entrypoint: sweep camera pitch over the Lumbridge region to find top-down. */
  public static void main(String[] args) throws Exception {
    String cache = args.length > 0 ? args[0] : "../../idlersc/Cache";

    WorldRenderer r = new WorldRenderer(cache, 512, 512);
    // Lumbridge sector (h0x50y50).
    r.loadRegion(50 * 48 + 23, 50 * 48 + 23, 0);

    // Centre of the loaded 96x96 region, in local model units (96*128/2).
    int cx = 6144, cz = 6144;
    int[] pitches = {256, 384, 512, 640, 768, 896};
    for (int p : pitches) {
      BufferedImage img = r.render(cx, cz, 180, p, 0, 0, 4000);
      File f = new File("/tmp/td_pitch_" + p + ".png");
      ImageIO.write(img, "png", f);
      System.out.println("Wrote " + f.getAbsolutePath());
    }
  }
}
