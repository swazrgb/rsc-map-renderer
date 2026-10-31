package openrsc.bot.api;

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * A client-to-server packet as data: what to do and the values it carries. The opcode and byte
 * layout are left to whoever frames it.
 */
public interface Packet extends Action {

    @Override
    default void execute(@NotNull ActionExecuteContext ctx) {
        ctx.describe(toString());
        ctx.writePacket(this);
    }

    /**
     * Whether the server drops an open option menu, without telling the client, before it handles this
     * packet. Only a short list of packets is exempt, so this defaults to {@code true}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/PayloadProcessorManager.java#L175">PayloadProcessorManager.checkIfShouldCancelMenu</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2671">Player.cancelMenuHandler</a>
     */
    @Override
    default boolean cancelsOptionMenu() {
        return true;
    }

    @Override
    default boolean restable() {
        return false;
    }

    /**
     * Records on {@code live} what sending this packet changed about the bot's own state. Called once,
     * after the packet is written, with the tick it went out on.
     */
    default void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {}

    /**
     * Whether the server's handler sets a walk-to action: a pending act it runs on the first tick the
     * player is in reach. Most later action packets cancel it, through their handler's
     * {@code Player.resetAll}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1297">GameStateUpdater.executeWalkToActions</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2622">Player.resetAllExceptTradeOrDuel</a>
     */
    @Override
    default boolean installsWalkToAction() {
        return false;
    }

    /** The extensions the server needs switched on to act on this packet; empty when every world can. */
    default @NotNull Set<Extension> requires() {
        return Set.of();
    }
}
