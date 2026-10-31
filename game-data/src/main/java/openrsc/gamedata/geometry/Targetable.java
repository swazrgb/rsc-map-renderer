package openrsc.gamedata.geometry;

/**
 * Something in the world a player acts on, at the tile {@code (x, z)}. As a {@link Dest} it contains
 * only its own tile; the tiles it can be reached from depend on its {@link #kind()} and the walls
 * around it, and come from {@link openrsc.gamedata.world.CollisionMap#canEngage}.
 */
public non-sealed interface Targetable extends Dest {

    int id();

    int x();

    int z();

    default Point point() {
        return new Point(x(), z());
    }

    @Override
    default boolean contains(int tx, int ty) {
        return tx == x() && ty == z();
    }

    Kind kind();

    /** The facing: 0, 2, 4 or 6 for a scenery, 0 to 3 for a boundary, 0 for the other kinds. */
    default int dir() {
        return 0;
    }

    /** What the target is and what is done to it, which picks the server's reach rule. */
    enum Kind {
        /**
         * An NPC to attack, talk to or use an item on.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/action/WalkToMobAction.java#L44">WalkToMobAction.shouldExecuteInternal</a>
         */
        NPC,
        /**
         * A player to attack, with an NPC's reach.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/action/WalkToMobAction.java#L44">WalkToMobAction.shouldExecuteInternal</a>
         */
        PLAYER,
        /**
         * A player to trade with: within 4 tiles rather than adjacent.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L98">PlayerTradeHandler.process</a>
         */
        PLAYER_TRADE,
        /**
         * A player to use an item on: a mob's reach, and the server also checks it can reach the player.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUseOnPlayer.java#L50">ItemUseOnPlayer.executeInternal</a>
         */
        PLAYER_USE,
        /** @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectAction.java#L61">GameObjectAction.process</a> */
        SCENERY,
        /** @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectWallAction.java#L57">GameObjectWallAction.process</a> */
        BOUNDARY,
        /** @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GroundItemTake.java#L59">GroundItemTake.process</a> */
        GROUND,
        /**
         * An NPC known by its spawn, at a tile it holds or will hold. Reached as an {@link #NPC}, except
         * that a diagonal across a blocked corner counts only when the attack would land from there
         * without a side-step.
         */
        NPC_SPAWN
    }
}
