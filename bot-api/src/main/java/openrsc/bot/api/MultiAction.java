package openrsc.bot.api;

import java.util.Arrays;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

record MultiAction(Action @NotNull [] actions) implements Action {

    @Override
    public void execute(@NotNull ActionExecuteContext ctx) {
        for (Action action : actions) {
            action.execute(ctx);
        }
    }

    @Override
    public boolean restable() {
        for (Action action : actions) {
            if (!action.restable()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean doesNothing() {
        for (Action action : actions) {
            if (!action.doesNothing()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int talksToNpc() {
        for (Action action : actions) {
            int npc = action.talksToNpc();
            if (npc >= 0) {
                return npc;
            }
        }
        return -1;
    }

    @Override
    public boolean cancelsOptionMenu() {
        for (Action action : actions) {
            if (action.cancelsOptionMenu()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean installsWalkToAction() {
        for (Action action : actions) {
            if (action.installsWalkToAction()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean awaitsServer() {
        for (Action action : actions) {
            if (action.awaitsServer()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable Point firstStep() {
        for (Action action : actions) {
            Point step = action.firstStep();
            if (step != null) {
                return step;
            }
        }
        return null;
    }

    @Override
    @NotNull
    public String toString() {
        // A record's own toString would print the array's identity, not its contents.
        return "MultiAction" + Arrays.toString(actions);
    }
}
