package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;

/**
 * What a registry constant declares: a mechanic and its placement, before any world is consulted.
 * Whatever the world decides, such as which entry tiles are walkable or whether the object is placed
 * at all, is derived in {@link #expand}.
 */
public sealed interface TransportSpec
        permits Bespoke,
                CutBoundary,
                CutScenery,
                NpcTalk,
                ObjectTeleport,
                SceneryObstacle,
                ClimbScenery,
                SpellTeleport,
                WallTeleport,
                SceneryCrossing,
                WallCrossing,
                MenuDoor,
                ItemRub,
                ItemUse,
                OperateItem,
                ScriptedClimb,
                AnyPlacement,
                AnyWallPlacement,
                AnyObstacle,
                EveryPlacement,
                Door,
                OnlyIn,
                ByWorld {

    /**
     * The transports this declaration makes in {@code env}: any number, none where the world lacks
     * what it names. {@code id} is the declaring constant's name, which addresses every one of them
     * in a cost override.
     */
    List<Transport> expand(ServerData env, String id);

    /**
     * Whether this expands every placement of an object the world loads, so that its transports are
     * named by placement ({@link openrsc.nav.transports.AllTransports#namedByPlacement}).
     */
    default boolean namedByPlacement() {
        return false;
    }
}
