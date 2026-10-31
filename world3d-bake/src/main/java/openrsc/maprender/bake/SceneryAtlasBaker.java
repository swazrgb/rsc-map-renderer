package openrsc.maprender.bake;

import com.openrsc.client.entityhandling.EntityHandler;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import openrsc.bot.render.ObjectSpriteRenderer;
import openrsc.gamedata.defs.SceneryLocs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bakes a top-down sprite of every (scenery id, direction) pair placed in the world, rendered by
 * {@link ObjectSpriteRenderer} and shelf-packed into one PNG with a JSON index.
 */
public final class SceneryAtlasBaker {

    /**
     * One packed sprite: its rect {@code x, y, w, h} in the atlas, in pixels; {@code ax, ay}, where the
     * centre of the object's footprint falls, from the rect's top-left; {@code ppt}, the pixels per
     * tile it was rendered at; and {@code tw, th}, its footprint in tiles, turned with {@code dir}.
     */
    public record Entry(
            int id, int dir, int x, int y, int w, int h, int ax, int ay, int ppt, int tw, int th, String name) {}

    public record Index(int version, List<Entry> entries) {}

    private static final Logger LOG = LoggerFactory.getLogger(SceneryAtlasBaker.class);

    /** The atlas format, part of both file names; a different version is a different atlas. */
    public static final int VERSION = 4;

    private static final int ATLAS_WIDTH = 2048;
    private static final int PADDING = 1;

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
     * Writes the atlas into {@code outDir}, or does nothing when {@link #isBaked} already. Both files
     * appear under their final names only once complete. Sprites that fail to render are logged
     * and left out.
     */
    public static synchronized void bake(String clientCacheDir, List<SceneryLocs.Loc> locs, Path outDir)
            throws IOException {
        if (isBaked(outDir)) {
            return;
        }
        long t0 = System.nanoTime();
        Files.createDirectories(outDir);

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

        rendered.sort(
                (a, b) -> b.sprite().image().getHeight() - a.sprite().image().getHeight());
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
            positions.add(new int[] {penX, penY});
            shelfH = Math.max(shelfH, h);
            atlasH = Math.max(atlasH, penY + h + PADDING);
            penX += w + PADDING;
        }

        BufferedImage atlas = new BufferedImage(ATLAS_WIDTH, Math.max(1, atlasH), BufferedImage.TYPE_INT_ARGB);
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
            entries.add(new Entry(
                    r.id(),
                    r.dir(),
                    p[0],
                    p[1],
                    s.image().getWidth(),
                    s.image().getHeight(),
                    s.anchorX(),
                    s.anchorY(),
                    s.pxPerTile(),
                    tw,
                    th,
                    def.getName()));
        }
        g.dispose();

        Path pngTmp = outDir.resolve(".atlas.png.tmp");
        Path jsonTmp = outDir.resolve(".atlas.json.tmp");
        ImageIO.write(atlas, "png", pngTmp.toFile());
        BakeJson.MAPPER.writeValue(jsonTmp.toFile(), new Index(VERSION, entries));
        Files.move(pngTmp, atlasPng(outDir), StandardCopyOption.REPLACE_EXISTING);
        Files.move(jsonTmp, atlasJson(outDir), StandardCopyOption.REPLACE_EXISTING);

        LOG.info(
                "Baked scenery atlas: {} sprites ({} unrenderable) -> {}x{} px in {} ms",
                entries.size(),
                nulls,
                ATLAS_WIDTH,
                atlasH,
                (System.nanoTime() - t0) / 1_000_000);
    }

    private SceneryAtlasBaker() {}
}
