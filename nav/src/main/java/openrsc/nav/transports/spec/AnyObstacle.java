package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.nav.Transport;

/**
 * A {@link SceneryObstacle} at every placement of its object; the declaration's own tile is not
 * read. Built by {@link SceneryObstacle#obstacleAnywhere}.
 */
public record AnyObstacle(SceneryObstacle crossing) implements TransportSpec {

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String constant) {
        List<Transport> out = new ArrayList<>();
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (loc.id() == crossing.id()) {
                out.addAll(crossing.expandAt(
                        env, constant, loc.pos().x(), loc.pos().y()));
            }
        }
        return out;
    }
}
