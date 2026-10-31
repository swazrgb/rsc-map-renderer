package openrsc.bot.api;

import java.util.Arrays;
import java.util.Collection;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Work for one tick, run against an {@link ActionExecuteContext}.
 *
 * <p>{@code null} means "nothing to do". The {@code of} factories run their actions in argument
 * order, skip {@code null} ones (and a {@code null} array or collection), flatten nested composites
 * and copy what they are given; they return {@code null} when nothing is left and the action itself
 * when one is. A composite answers each hint method below from its parts.
 */
@FunctionalInterface
public interface Action {

    void execute(@NotNull ActionExecuteContext ctx);

    /**
     * Whether a sleeping bag may be used in the same tick as this action. True for an action that sends
     * no packet; a composite is restable only when every part is.
     */
    @Contract(pure = true)
    default boolean restable() {
        return true;
    }

    /**
     * Whether this action leaves the tick idle: it may record notes, as a {@link Note} does, but does
     * no work. A composite does nothing only when every part does.
     */
    @Contract(pure = true)
    default boolean doesNothing() {
        return false;
    }

    /**
     * Whether executing this makes the server drop an open option menu without telling the client; see
     * {@link Packet#cancelsOptionMenu()}. A composite does when any part does.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/PayloadProcessorManager.java#L175">PayloadProcessorManager.checkIfShouldCancelMenu</a>
     */
    @Contract(pure = true)
    default boolean cancelsOptionMenu() {
        return false;
    }

    /**
     * The first tile this action's walk sends the player to, or {@code null} when it carries no walk.
     * A composite answers with its first part that has one.
     */
    @Contract(pure = true)
    default @Nullable openrsc.gamedata.geometry.Point firstStep() {
        return null;
    }

    /**
     * The server index of the NPC this action talks to, or {@code -1} when it talks to none. A
     * composite answers with its first part that talks to one.
     */
    @Contract(pure = true)
    default int talksToNpc() {
        return -1;
    }

    /**
     * Whether executing this leaves the server holding a walk-to action for the player; see
     * {@link Packet#installsWalkToAction()}. A composite does when any part does.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/GameStateUpdater.java#L1297">GameStateUpdater.executeWalkToActions</a>
     */
    @Contract(pure = true)
    default boolean installsWalkToAction() {
        return false;
    }

    /**
     * Whether this action waits on the server to finish something it is doing for the player, such as
     * answering a click: a packet sent beside it could replace that. A composite does when any part does.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2622">Player.resetAllExceptTradeOrDuel</a>
     */
    @Contract(pure = true)
    default boolean awaitsServer() {
        return false;
    }

    @Nullable
    @Contract(value = "null, null -> null; !null, _ -> !null; _, !null -> !null", pure = true)
    static Action of(@Nullable Action first, @Nullable Action second) {
        if (first == null) {
            return second;
        }

        if (second == null) {
            return first;
        }

        Action[] flat = new Action[countOf(first) + countOf(second)];
        return build(flat, flattenInto(flat, flattenInto(flat, 0, first), second));
    }

    @Nullable
    @Contract(pure = true)
    static Action of(@Nullable Action first, @Nullable Action second, @Nullable Action @Nullable ... others) {
        if (others == null || others.length == 0) {
            return of(first, second);
        }

        int count = countOf(first) + countOf(second);
        for (Action other : others) {
            count += countOf(other);
        }

        Action[] flat = new Action[count];
        int size = flattenInto(flat, flattenInto(flat, 0, first), second);
        for (Action other : others) {
            size = flattenInto(flat, size, other);
        }

        return build(flat, size);
    }

    @Nullable
    @Contract(pure = true)
    static Action of(@Nullable Collection<? extends @Nullable Action> actions) {
        if (actions == null) {
            return null;
        }

        int count = 0;
        for (Action action : actions) {
            count += countOf(action);
        }

        Action[] flat = new Action[count];
        int size = 0;
        for (Action action : actions) {
            size = flattenInto(flat, size, action);
        }

        return build(flat, size);
    }

    @Contract(pure = true)
    private static int countOf(@Nullable Action action) {
        if (action == null) {
            return 0;
        }

        return action instanceof MultiAction(Action[] actions) ? actions.length : 1;
    }

    private static int flattenInto(Action @NotNull [] target, int index, @Nullable Action action) {
        if (action == null) {
            return index;
        }

        if (action instanceof MultiAction(Action[] actions)) {
            System.arraycopy(actions, 0, target, index, actions.length);
            return index + actions.length;
        }

        target[index] = action;
        return index + 1;
    }

    @Nullable
    @Contract(pure = true)
    private static Action build(Action @NotNull [] flat, int size) {
        if (size == 0) {
            return null;
        }

        if (size == 1) {
            return flat[0];
        }

        // Shorter than the array only if a collection changed between counting and copying.
        return new MultiAction(size == flat.length ? flat : Arrays.copyOf(flat, size));
    }
}
