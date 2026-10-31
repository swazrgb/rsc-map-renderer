package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.ObjectInteractTransport;

/**
 * Every placement of a jungle tree, chopped from a tile beside it: the chop moves the player onto the
 * tree's tile, and needs Woodcutting 50, a machete and an axe. A tree is declared only where two of
 * its engage tiles have no walk of 10 steps or fewer between them.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L77">WoodcutJungle.handleJungleWoodcut</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L127">WoodcutJungle.cutJungle</a>
 */
public record CutScenery(Set<Integer> objectIds) implements TransportSpec {

    /**
     * What a chopped tree stands as until it grows back; clicking it moves the player onto it too.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L40">WoodcutJungle.onOpLoc</a>
     */
    public static final int JUNGLE_TREE_STUMP = 1087;

    public static CutScenery jungleTrees() {
        return new CutScenery(Set.of(1086, 1100, 1099, 1092, 1091));
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<Transport> out = new ArrayList<>();
        for (SceneryLocs.Loc s : env.sceneryLocs()) {
            if (objectIds.contains(s.id())) {
                crossings(
                        out,
                        env.collisionMap(),
                        id,
                        s.id(),
                        s.pos().x(),
                        s.pos().y());
            }
        }
        return out;
    }

    private static void crossings(List<Transport> out, CollisionMap map, String id, int objectId, int x, int z) {
        List<Point> entries = map.engageTilesAround(new Rect(x, z, x, z), Overlays.NONE);
        if (!isBridge(map, entries)) {
            return;
        }
        Prereq gate = Prereq.all(
                Prereq.all(
                        Prereq.fact(Fact.skillAtLeast(Skill.WOODCUTTING, CutBoundary.WOODCUTTING_REQ)),
                        Prereq.fact(Fact.carriedAtLeast(ItemId.MACHETTE.id(), 1))),
                Kit.AXE);
        for (Point n : entries) {
            out.add(new ObjectInteractTransport(objectId, n.x(), n.y(), x, z, gate, CutBoundary.JUNGLE_COST)
                    .atScenery(x, z)
                    .orScenery(JUNGLE_TREE_STUMP)
                    .withPlacement(id));
        }
    }

    private static boolean isBridge(CollisionMap map, List<Point> entries) {
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                if (!CutBoundary.cheapWalkAroundExists(
                        map,
                        entries.get(i).x(),
                        entries.get(i).y(),
                        entries.get(j).x(),
                        entries.get(j).y(),
                        CutBoundary.WALKAROUND_BUDGET)) {
                    return true;
                }
            }
        }
        return false;
    }
}
