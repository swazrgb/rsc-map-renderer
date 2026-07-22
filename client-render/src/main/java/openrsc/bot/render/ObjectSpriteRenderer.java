package openrsc.bot.render;

import com.openrsc.client.entityhandling.EntityHandler;
import com.openrsc.client.entityhandling.defs.GameObjectDef;
import java.awt.image.BufferedImage;
import orsc.graphics.three.RSModel;

/**
 * Renders one top-down sprite per scenery object (id, direction), exactly as
 * the live client draws it: same model spawn recipe ({@code placeObjectClient},
 * mirroring the opcode-48 handler), same software rasterizer, camera straight
 * down (pitch 768 — the in-game pitch clamp's top-down limit, validated by eye
 * in the live client). The terrain is removed from the scene so the frame
 * background stays sentinel → transparent; canopy gaps stay transparent too,
 * so the map terrain shows through exactly like the ground does in-game.
 *
 * <p>Geometry: the camera sits directly above the object's footprint centre,
 * so there is no perspective lean. The returned {@link Sprite} carries the
 * pixel offset from the sprite's top-left to that footprint-centre ground
 * point, so a consumer can anchor the sprite on a map with sub-tile precision,
 * plus the render scale in px/tile.
 */
public final class ObjectSpriteRenderer {

  /**
   * Nominal render scale: 128 world units * 2^SHIFT / ZOOM px per tile. The
   * shift/zoom pair stays in the live client's operating range — extreme
   * values (e.g. shift 13 + zoom 32k) break the texturer's fixed-point
   * precision and shred foliage textures into sparse streaks.
   */
  public static final int PX_PER_TILE = 40;

  /** Frame size in px; must fit the largest object + canopy overhang (grand
   * tree ≈ 16 tiles, Zamorakian Temple ≈ 13). 1024 px = 25.6 tiles. */
  private static final int FRAME = 1024;

  /**
   * Perspective focal shift — the live client's in-game value (m_qd=9), and
   * the ONLY robust one: raising it (10/11) flattens the projection but
   * destabilizes the texturer's fixed-point gradients on rotated fine-textured
   * canopies (tree id1 dirs 1-7 collapse into holes at shift 10 while dir 0
   * survives — see DirSweepProof; the in-game camera never runs there, which
   * is why the same trees look fine in the live client). Flatness comes from
   * {@link #Y_SQUASH} instead.
   */
  private static final int SHIFT = 9;

  /**
   * Model-height scale in 256ths, applied between the dir-rotation and the
   * translate. The in-game camera sits only ~1638 units up, so a canopy ~300
   * units tall is perspective-magnified ~1.22x — "trees bigger than their
   * tile" on the map. Squashing height 4x cuts that to ~1.05x (equivalent to
   * the shift-11 camera) while the camera stays in the texturer's proven
   * operating range at every direction. Straight down, height only affects
   * magnification and occlusion order (which monotonic scaling preserves), so
   * nothing else changes.
   */
  private static final int Y_SQUASH = 64;

  /** Straight-down pitch — the in-game clamp's top-down limit. */
  private static final int PITCH_TOP_DOWN = 768;

  /** Region-local placement tile (region centre-ish, well away from edges). */
  private static final int LOCAL_X = 48;
  private static final int LOCAL_Z = 48;

  public record Sprite(BufferedImage image, int anchorX, int anchorY, int pxPerTile) {
    /** Pixel offset of the footprint-centre ground point from the crop's top-left. */
  }

  private final WorldRenderer r;
  private final int zoom;

  public ObjectSpriteRenderer(String cacheDir) {
    this(cacheDir, SHIFT);
  }

  /** Diagnostic variant with an explicit focal shift (flatness comparisons). */
  public ObjectSpriteRenderer(String cacheDir, int shift) {
    this.zoom = 128 * (1 << shift) / PX_PER_TILE;
    this.r = new WorldRenderer(cacheDir, FRAME, FRAME);
    r.loadModels();
    r.setOrthographic(0);
    r.setProjectionShift(shift);
    // Any region works: the object and the camera use the same elevation
    // reference so terrain height cancels; Lumbridge is the stock login region.
    r.loadRegion(50 * 48 + 23, 50 * 48 + 23, 0);
    r.removeRoofs(0);
    r.removeAllWalls();
    r.removeLandscape();
  }

  public static int objectCount() {
    return EntityHandler.objectCount();
  }

  public static GameObjectDef objectDef(int id) {
    return EntityHandler.getObjectDef(id);
  }

  /**
   * Render object {@code id} facing {@code direction} (0..7) straight down.
   *
   * @return the cropped sprite, or null when the object has no renderable model
   *     (empty def) or rasterizes to nothing.
   */
  public Sprite render(int id, int direction) {
    return renderAtPitch(id, direction, PITCH_TOP_DOWN);
  }

  /** Diagnostic variant with an explicit camera pitch. */
  public Sprite renderAtPitch(int id, int direction, int pitch) {
    RSModel m = r.placeObjectClient(id, LOCAL_X, LOCAL_Z, direction);
    if (m == null) {
      return null;
    }
    m.setScale256(256, Y_SQUASH, 256);
    try {
      GameObjectDef def = EntityHandler.getObjectDef(id);
      int xSize;
      int zSize;
      if (direction == 0 || direction == 4) {
        xSize = def.getWidth();
        zSize = def.getHeight();
      } else {
        xSize = def.getHeight();
        zSize = def.getWidth();
      }
      // Footprint-centre ground point — same expression the placement uses.
      int camX = (LOCAL_X * 2 + xSize) * 128 / 2;
      int camZ = (LOCAL_Z * 2 + zSize) * 128 / 2;
      // Very tall models (grand tree, Zamorakian Temple) tower toward the
      // camera and get perspective-magnified past the frame edge. When the
      // opaque bbox touches the frame, back the camera off 2x (halving the
      // scale, tracked in Sprite.pxPerTile) until it fits — but never past the
      // Scene's ~25000 depth cap, beyond which everything culls.
      // Yaw 512 (180°): the raw yaw-0 projection comes out rotated 180° vs the
      // web map's terrain raster (map: east right / north up, with RSC's
      // westward +X mirrored; verified in OrientationProof). Rendering with
      // the camera spun half a turn bakes the map orientation into the sprite.
      for (int attempt = 0; ; attempt++) {
        BufferedImage frame = r.renderArgb(camX, camZ, 180, pitch, 512, 0, zoom << attempt);
        if (!touchesEdge(frame) || (zoom << (attempt + 1)) > 24500) {
          return crop(frame, PX_PER_TILE >> attempt);
        }
      }
    } finally {
      r.removeModel(m);
    }
  }

  /** True when any opaque pixel lies on the frame border (render is clipped). */
  private static boolean touchesEdge(BufferedImage f) {
    int w = f.getWidth();
    int h = f.getHeight();
    for (int x = 0; x < w; x++) {
      if ((f.getRGB(x, 0) >>> 24) != 0 || (f.getRGB(x, h - 1) >>> 24) != 0) {
        return true;
      }
    }
    for (int y = 0; y < h; y++) {
      if ((f.getRGB(0, y) >>> 24) != 0 || (f.getRGB(w - 1, y) >>> 24) != 0) {
        return true;
      }
    }
    return false;
  }

  /** Crop to the opaque bounding box; anchor = frame centre relative to crop origin. */
  private static Sprite crop(BufferedImage frame, int pxPerTile) {
    int w = frame.getWidth();
    int h = frame.getHeight();
    int minX = w;
    int minY = h;
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if ((frame.getRGB(x, y) >>> 24) != 0) {
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }
    if (maxX < 0) {
      return null;
    }
    BufferedImage cropped = frame.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    // Copy out of the shared subimage raster so callers own the pixels.
    BufferedImage out = new BufferedImage(cropped.getWidth(), cropped.getHeight(),
        BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D g = out.createGraphics();
    g.drawImage(cropped, 0, 0, null);
    g.dispose();
    return new Sprite(out, w / 2 - minX, h / 2 - minY, pxPerTile);
  }
}
