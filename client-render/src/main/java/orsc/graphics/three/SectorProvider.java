package orsc.graphics.three;

import com.openrsc.client.model.Sector;

/**
 * Supplies landscape {@link Sector}s to {@link World}, bypassing the stock
 * {@code Authentic_Landscape.orsc} ZIP.
 *
 * <p>The headless world map sources terrain from the authoritative
 * {@code maps64/land64} JAG archives (decoded by the bot's {@code JagLandscape})
 * rather than the {@code .orsc} landscape — the JAG is the bot's source of
 * truth and the {@code .orsc} had known defects. The runner glue implements
 * this interface to convert the bot's decoded tiles into the client
 * {@link Sector}/{@code Tile} model {@link World} expects.
 */
@FunctionalInterface
public interface SectorProvider {

    /**
     * Returns the sector, or {@code null} if that section is empty/absent (the world then
     * substitutes a blank sector, matching the ZIP path).
     *
     * @param height the floor (0..3)
     * @param sectionX sector X index (world tile / 48)
     * @param sectionY sector Y index
     */
    Sector getSection(int height, int sectionX, int sectionY);
}
