package openrsc.bot.api;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.nav.Planner;
import org.jetbrains.annotations.Nullable;

public interface World {

    List<Npc> npcs();

    List<Npc> npcGhosts();

    List<Player> players();

    Scenery scenery();

    Walls walls();

    GroundItems groundItems();

    boolean canEngage(Point from, @Nullable Targetable target);

    boolean canStep(Point from, int tx, int ty);

    @Nullable
    Point closestEngageTile(Point from, Targetable target);

    /**
     * The planner routes are found with.
     *
     * @throws IllegalStateException for a world built without one
     */
    Planner nav();
}
