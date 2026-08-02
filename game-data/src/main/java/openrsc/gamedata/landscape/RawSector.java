package openrsc.gamedata.landscape;

/**
 * One decoded 48x48 landscape sector, in the raw per-tile form both landscape containers share.
 *
 * <p>The classic {@code maps{rev}.jag/.mem} archives and the {@code .orsc} repack store exactly
 * these seven fields per tile — the {@code .orsc} as a flat 10-byte record
 * ({@code elevation, texture, overlay, roof, hWall, vWall, int diagonal}), the JAG as a set of
 * revision-specific RLE streams — so a decoded sector is format-independent and every consumer
 * (collision stamping, the 3D bake's terrain provider) reads the same values whichever
 * {@link LandscapeSource} produced them.
 *
 * <p>Arrays are indexed {@code lx * 48 + ly}, x-major — the convention the server's {@code Sector},
 * the client's {@code Sector.getTile(x, y)} and {@code CollisionMap} all use.
 *
 * <p>Mutable by design: the 3D bake strips boundary walls out of a sector before handing it to the
 * renderer, so {@link LandscapeSource#sector} implementations must hand back a fresh instance per
 * call rather than a shared cached one.
 */
public final class RawSector {

  /** Tiles per sector edge. */
  public static final int REGION_SIZE = 48;

  /** Tiles per sector ({@value #REGION_SIZE}²). */
  public static final int SIZE = REGION_SIZE * REGION_SIZE;

  public final byte[] groundElevation = new byte[SIZE];
  public final byte[] groundTexture = new byte[SIZE];
  public final byte[] groundOverlay = new byte[SIZE];
  public final byte[] roofTexture = new byte[SIZE];
  public final byte[] horizontalWall = new byte[SIZE];
  public final byte[] verticalWall = new byte[SIZE];
  public final int[] diagonalWalls = new int[SIZE];
}
