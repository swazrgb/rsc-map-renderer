package openrsc.bot.api;

import openrsc.bot.api.packets.WalkToPointPacket;
import openrsc.gamedata.geometry.Point;

/**
 * The walk to the player's own tile that a click is sent behind, in the same tick.
 *
 * <p>In combat a walk is a retreat: refused during the opponent's first three hits, it ends the
 * fight after them. At scenery, the server accepts a diagonal approach only once the player's path
 * has finished.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L38">WalkRequest.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L206">Mob.atObject</a>
 */
public final class StayWalk {

    private StayWalk() {}

    /** Whether a click needs the stay-walk ahead of it. */
    public static boolean ahead(boolean fighting, boolean scenery) {
        return fighting || scenery;
    }

    public static WalkToPointPacket at(Point self) {
        return new WalkToPointPacket(self);
    }
}
