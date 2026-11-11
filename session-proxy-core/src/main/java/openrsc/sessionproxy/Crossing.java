package openrsc.sessionproxy;

import java.util.List;
import openrsc.bot.api.GameState;
import openrsc.bot.api.packets.DialogueReplyPacket;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.transports.EngageSpec;
import org.jetbrains.annotations.Nullable;

/**
 * A crossing that opens a conversation, once the map's walk has clicked it: each menu the conversation opens is answered
 * with the crossing's keywords ({@link EngageSpec#dialog}). It is over once the player stands on its landing, or once
 * the conversation has been quiet for {@value #QUIET_TICKS} ticks, wherever the player is.
 */
final class Crossing {

    /** One tick of the crossing: the answer to send, then whether it is over, or why it failed. */
    record Next(
            @Nullable DialogueReplyPacket answer,
            boolean over,
            @Nullable String failed) {}

    // A crossing can end in messages and a walk, which are no conversation, before it moves the player: the toll
    // gate's guard walks the player back to the gate before putting it through:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/BorderGuard.java#L52
    private static final int QUIET_TICKS = 30;

    private final String name;
    private final EngageSpec spec;
    private final Point landing;
    private int started = Integer.MIN_VALUE;
    private int answeredMenu = -1;
    private int answers;

    Crossing(String name, EngageSpec spec, Point landing) {
        this.name = name;
        this.spec = spec;
        this.landing = landing;
    }

    String name() {
        return name;
    }

    Point landing() {
        return landing;
    }

    Next step(GameState s) {
        int now = s.sessionTick().tick();
        if (started == Integer.MIN_VALUE) {
            started = now;
        }
        if (s.pos().equals(landing)) {
            return new Next(null, true, null);
        }
        if (s.dialog().menuOpen() && s.dialog().menuOrdinal() != answeredMenu) {
            List<String> options = s.dialog().options();
            int option = Menus.optionFor(options, groups());
            if (option < 0) {
                return new Next(null, false, name + " asks " + options + ", and the crossing names none of them");
            }
            answeredMenu = s.dialog().menuOrdinal();
            answers++;
            return new Next(new DialogueReplyPacket(option), false, null);
        }
        return new Next(null, now - Math.max(started, s.dialog().lastActivityTick()) > QUIET_TICKS, null);
    }

    // A sequenced crossing answers its menus in order, one keyword group each.
    private List<String[]> groups() {
        if (!spec.sequencedDialog()) {
            return spec.dialog();
        }
        return answers < spec.dialog().size() ? List.<String[]>of(spec.dialog().get(answers)) : List.of();
    }
}
