package openrsc.nav.transports;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;

/**
 * One direction across one web: cut it, then walk through. A cut does not move the player; it
 * replaces the web with an open boundary for 30 seconds, and succeeds 2 times in 5. When
 * {@code clicked}, the world cuts webs by a click on them, which the server answers with the worn
 * weapon, a carried one or a knife; otherwise the best carried cutter is used on the web. See
 * {@link openrsc.nav.transports.spec.CutBoundary} for who may cut.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L41">CutWeb.onUseBound</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L88">CutWeb.onOpBound</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L311">Formulae.cutWeb</a>
 */
public final class WebTransport implements Transport, DeclarativeTransport {

    public static final int WEB_ID = 24;

    private String name;
    private final String placementId;

    private final int webX, webY, webDir;
    private final int entryX, entryY;
    private final int destX, destY;
    private final Prereq prereq;

    private final int cost;

    private final boolean clicked;

    public WebTransport(
            String placementId,
            int webX,
            int webY,
            int webDir,
            int entryX,
            int entryY,
            int destX,
            int destY,
            Prereq prereq,
            int cost,
            boolean clicked) {
        this.placementId = placementId;
        this.webX = webX;
        this.webY = webY;
        this.webDir = webDir;
        this.entryX = entryX;
        this.entryY = entryY;
        this.destX = destX;
        this.destY = destY;
        this.prereq = prereq;
        this.cost = cost;
        this.clicked = clicked;
    }

    @Override
    public boolean isEntry(int x, int y) {
        return x == entryX && y == entryY;
    }

    @Override
    public EngageSpec.Target.Boundary boundary() {
        return new EngageSpec.Target.Boundary(WEB_ID, webX, webY, webDir);
    }

    @Override
    public List<Point> entries() {
        return List.of(Point.of(entryX, entryY));
    }

    @Override
    public Point destinationFrom(Point from) {
        return Point.of(destX, destY);
    }

    @Override
    public Prereq prereq() {
        return prereq;
    }

    @Override
    public int cost() {
        return cost;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void assignName(String name) {
        this.name = name;
    }

    @Override
    public String costOverrideKey() {
        return placementId;
    }

    @Override
    public EngageSpec engageSpec() {
        if (clicked) {
            return EngageSpec.of(EngageSpec.Action.INTERACT_1, boundary());
        }
        return EngageSpec.of(new EngageSpec.Action.UseItem(new EngageSpec.ItemSelector.BestCutter()), boundary());
    }
}
