package openrsc.maprender.bake;

import com.openrsc.client.model.Sector;
import com.openrsc.client.model.Tile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.landscape.RawSector;
import orsc.graphics.three.SectorProvider;

/**
 * Feeds the renderer's {@link orsc.graphics.three.World} from a {@link LandscapeSource} instead of
 * letting it open the {@code Authentic_Landscape.orsc} in the client cache itself.
 *
 * <p>The bake defaults to the JAG map archives — the source the SERVER actually paths against
 * (based_map_data 64) — rather than that {@code .orsc} repack, which carries cosmetic filled-in
 * sections (e.g. upper-floor sectors maps64 omits) the server knows nothing about; rendering from
 * the JAG makes the 3D view show exactly the world the server (and the bot's collision model) sees.
 * Going through the interface is what lets {@code -Dopenrsc.landscape} point the bake at any other
 * landscape — a custom server's {@code .orsc}, an older map revision — without a second provider.
 *
 * <p>Index conventions agree end to end: {@link RawSector} arrays and client
 * {@link Sector#getTile(int, int)} both use {@code lx*48 + lz}.
 */
public final class LandscapeSectorProvider implements SectorProvider {

  private final LandscapeSource landscape;
  /** (height, sectionX, sectionY) -> tile edges to strip, packed lx*48*8 + lz*8 + dir. */
  private final java.util.Map<Long, java.util.List<Integer>> strip = new java.util.HashMap<>();

  public LandscapeSectorProvider(LandscapeSource landscape) {
    this.landscape = landscape;
  }

  /**
   * Remove the wall values at these boundary edges from the sectors this
   * provider serves — the viewer draws those edges itself (and swaps them
   * when a bot observes a different state). Coordinates are server locs
   * (absolute Y: floor = y/944).
   */
  public void stripBoundaries(java.util.List<openrsc.gamedata.BoundaryLocs.Loc> locs) {
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
      strip.computeIfAbsent(key, k -> new java.util.ArrayList<>())
          .add(lx * 48 * 8 + lz * 8 + (loc.direction() & 7));
    }
  }

  @Override
  public Sector getSection(int height, int sectionX, int sectionY) {
    // Safe to mutate below: a LandscapeSource hands back a fresh RawSector per call.
    RawSector raw = landscape.sector(height, sectionX, sectionY);
    if (raw == null) {
      return null;
    }
    java.util.List<Integer> edges =
        strip.get(((long) height << 40) | ((long) sectionX << 20) | sectionY);
    if (edges != null) {
      for (int packed : edges) {
        int lx = packed / (48 * 8);
        int lz = (packed / 8) % 48;
        int dir = packed & 7;
        int i = lx * 48 + lz;
        if (dir == 0) {
          raw.verticalWall[i] = 0;    // north edge (runs east-west)
        } else if (dir == 1) {
          raw.horizontalWall[i] = 0;  // west edge (runs north-south)
        } else {
          raw.diagonalWalls[i] = 0;   // either diagonal orientation
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
