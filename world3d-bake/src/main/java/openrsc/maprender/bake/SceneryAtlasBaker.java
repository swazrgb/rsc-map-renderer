package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import openrsc.gamedata.SceneryLocs;
import openrsc.bot.render.ObjectSpriteRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Bakes the world-map scenery sprite atlas: one top-down sprite per unique
 * (object id, direction) pair that actually occurs in {@code SceneryLocs},
 * rendered by {@link ObjectSpriteRenderer} (the live client's own rasterizer,
 * straight-down, map-oriented) and shelf-packed into a single PNG + JSON
 * index. Written to disk so a runner restart serves instantly.
 *
 * <p>Index entry fields: atlas rect {@code x,y,w,h}; {@code ax,ay} = pixel
 * offset of the object's footprint-centre ground point from the rect's
 * top-left; {@code ppt} = render scale in px/tile (halved for skyscraper
 * models that needed a farther camera); {@code tw,th} = footprint size in
 * tiles (direction-swapped); {@code name} for tooltips.
 */
public final class SceneryAtlasBaker {

  private static final Logger LOG = LoggerFactory.getLogger(SceneryAtlasBaker.class);

  /** Bump to force a re-bake on format/renderer changes. */
  public static final int VERSION = 4;

  private static final int ATLAS_WIDTH = 2048;
  private static final int PADDING = 1;

  public record Entry(int id, int dir, int x, int y, int w, int h,
                      int ax, int ay, int ppt, int tw, int th, String name) {}

  public record Index(int version, List<Entry> entries) {}

  public static Path atlasPng(Path dir) {
    return dir.resolve("scenery-atlas-v" + VERSION + ".png");
  }

  public static Path atlasJson(Path dir) {
    return dir.resolve("scenery-atlas-v" + VERSION + ".json");
  }

  public static boolean isBaked(Path dir) {
    return Files.isRegularFile(atlasPng(dir)) && Files.isRegularFile(atlasJson(dir));
  }

  /**
   * Render + pack + write. Idempotent: returns immediately when the versioned
   * artefacts already exist.
   */
  public static synchronized void bake(String clientCacheDir, List<SceneryLocs.Loc> locs, Path outDir)
      throws IOException {
    if (isBaked(outDir)) {
      return;
    }
    long t0 = System.nanoTime();
    Files.createDirectories(outDir);

    // Unique (id, dir) pairs in world order (stable output).
    Set<Long> pairs = new LinkedHashSet<>();
    for (SceneryLocs.Loc loc : locs) {
      pairs.add(((long) loc.id() << 8) | (loc.direction() & 0xFF));
    }

    ObjectSpriteRenderer renderer = new ObjectSpriteRenderer(clientCacheDir);

    record Rendered(int id, int dir, ObjectSpriteRenderer.Sprite sprite) {}
    List<Rendered> rendered = new ArrayList<>();
    int nulls = 0;
    for (long pair : pairs) {
      int id = (int) (pair >> 8);
      int dir = (int) (pair & 0xFF);
      ObjectSpriteRenderer.Sprite s = null;
      try {
        s = renderer.render(id, dir);
      } catch (Throwable t) {
        LOG.warn("scenery sprite render failed for id={} dir={}", id, dir, t);
      }
      if (s == null) {
        nulls++;
        continue;
      }
      rendered.add(new Rendered(id, dir, s));
    }

    // Shelf-pack, tallest first.
    rendered.sort((a, b) -> b.sprite().image().getHeight() - a.sprite().image().getHeight());
    List<Entry> entries = new ArrayList<>(rendered.size());
    int penX = PADDING;
    int penY = PADDING;
    int shelfH = 0;
    int atlasH = 0;
    List<int[]> positions = new ArrayList<>(rendered.size());
    for (Rendered r : rendered) {
      int w = r.sprite().image().getWidth();
      int h = r.sprite().image().getHeight();
      if (penX + w + PADDING > ATLAS_WIDTH) {
        penX = PADDING;
        penY += shelfH + PADDING;
        shelfH = 0;
      }
      positions.add(new int[]{penX, penY});
      shelfH = Math.max(shelfH, h);
      atlasH = Math.max(atlasH, penY + h + PADDING);
      penX += w + PADDING;
    }

    BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, Math.max(1, atlasH),
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = atlas.createGraphics();
    for (int i = 0; i < rendered.size(); i++) {
      Rendered r = rendered.get(i);
      var s = r.sprite();
      int[] p = positions.get(i);
      g.drawImage(s.image(), p[0], p[1], null);
      var def = EntityHandler.getObjectDef(r.id());
      int tw;
      int th;
      if (r.dir() == 0 || r.dir() == 4) {
        tw = def.getWidth();
        th = def.getHeight();
      } else {
        tw = def.getHeight();
        th = def.getWidth();
      }
      entries.add(new Entry(r.id(), r.dir(), p[0], p[1],
          s.image().getWidth(), s.image().getHeight(),
          s.anchorX(), s.anchorY(), s.pxPerTile(), tw, th, def.getName()));
    }
    g.dispose();

    Path pngTmp = outDir.resolve(".atlas.png.tmp");
    Path jsonTmp = outDir.resolve(".atlas.json.tmp");
    ImageIO.write(atlas, "png", pngTmp.toFile());
    new ObjectMapper().writeValue(jsonTmp.toFile(), new Index(VERSION, entries));
    Files.move(pngTmp, atlasPng(outDir), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.move(jsonTmp, atlasJson(outDir), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

    LOG.info("Baked scenery atlas: {} sprites ({} unrenderable) -> {}x{} px in {} ms",
        entries.size(), nulls, ATLAS_WIDTH, atlasH, (System.nanoTime() - t0) / 1_000_000);
  }

  private SceneryAtlasBaker() {}
}
