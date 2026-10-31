package openrsc.maprender.bake;

import com.openrsc.client.model.Sector;
import com.openrsc.client.model.Tile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.landscape.RawSector;
import orsc.graphics.three.SectorProvider;

/**
 * Serves the renderer's sectors from a {@link LandscapeSource} rather than from the client cache's
 * own landscape file, and can leave out the walls of given boundary placements.
 */
public final class LandscapeSectorProvider implements SectorProvider {

    private final LandscapeSource landscape;
    private final Map<Long, List<Integer>> strip = new HashMap<>();

    private boolean stripping = true;

    public LandscapeSectorProvider(LandscapeSource landscape) {
        this.landscape = landscape;
    }

    /**
     * Whether the walls named by {@link #stripBoundaries} are left out of sectors served from now on.
     * On by default. Terrain must be built with it off: the client splits a floor tile crossed by a
     * diagonal wall into two colours, and a stripped diagonal turns that tile into one.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L584">World.generateLandscapeModel</a>
     */
    public void setStripping(boolean on) {
        this.stripping = on;
    }

    /** Adds boundary placements, in server coordinates, whose walls stripping leaves out. */
    public void stripBoundaries(List<BoundaryLocs.Loc> locs) {
        for (var loc : locs) {
            int floor = loc.pos().y() / 944;
            int zLocal = loc.pos().y() % 944;
            int worldX = loc.pos().x() + 2304;
            int worldZ = zLocal + 1776;
            int sx = worldX / 48;
            int sy = worldZ / 48;
            int lx = worldX % 48;
            int lz = worldZ % 48;
            long key = ((long) floor << 40) | ((long) sx << 20) | sy;
            strip.computeIfAbsent(key, k -> new ArrayList<>()).add(lx * 48 * 8 + lz * 8 + (loc.direction() & 7));
        }
    }

    @Override
    public Sector getSection(int height, int sectionX, int sectionY) {
        RawSector raw = landscape.sector(height, sectionX, sectionY);
        if (raw == null) {
            return null;
        }
        List<Integer> edges = stripping ? strip.get(((long) height << 40) | ((long) sectionX << 20) | sectionY) : null;
        if (edges != null) {
            // The source hands out a fresh RawSector per call, so zeroing its walls changes no other load.
            for (int packed : edges) {
                int lx = packed / (48 * 8);
                int lz = (packed / 8) % 48;
                int dir = packed & 7;
                int i = lx * 48 + lz;
                if (dir == 0) {
                    raw.verticalWall[i] = 0;
                } else if (dir == 1) {
                    raw.horizontalWall[i] = 0;
                } else {
                    raw.diagonalWalls[i] = 0;
                }
            }
        }
        Sector sector = new Sector();
        for (int x = 0; x < 48; x++) {
            for (int z = 0; z < 48; z++) {
                int i = x * 48 + z;
                Tile t = new Tile();
                t.groundElevation = raw.groundElevation[i];
                t.groundTexture = raw.groundTexture[i];
                t.groundOverlay = raw.groundOverlay[i];
                t.roofTexture = raw.roofTexture[i];
                t.horizontalWall = raw.horizontalWall[i];
                t.verticalWall = raw.verticalWall[i];
                t.diagonalWalls = raw.diagonalWalls[i];
                sector.setTile(x, z, t);
            }
        }
        return sector;
    }
}
