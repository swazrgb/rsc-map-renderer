package openrsc.bot.api;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class MutableMessages {

    private final List<ServerMessage> current = new ArrayList<>();

    private List<ServerMessage> last = List.of();
    private List<ServerMessage> thisTick = List.of();
    private int lastTick = Integer.MIN_VALUE;
    private int lastServerLineTick = Integer.MIN_VALUE;
    private @Nullable ServerMessage lastReceived;
    private Messages snap;

    public void messageReceived(ServerMessage line, int tick) {
        current.add(line);
        lastReceived = line;
        if (lastTick != tick) {
            snap = null;
            this.lastTick = tick;
        }
        if (!Messages.fromAPlayer(line) && lastServerLineTick != tick) {
            snap = null;
            this.lastServerLineTick = tick;
        }
    }

    public void bundlePublished() {
        if (current.isEmpty()) {
            if (thisTick.isEmpty()) {
                return;
            }
            snap = null;
            thisTick = List.of();
            return;
        }
        List<ServerMessage> frozen = List.copyOf(current);
        current.clear();
        snap = null;
        thisTick = frozen;
        last = frozen;
    }

    public List<ServerMessage> thisTick() {
        return thisTick;
    }

    public @Nullable ServerMessage lastReceived() {
        return lastReceived;
    }

    public Messages snapshot() {
        Messages cached = snap;
        if (cached == null) {
            cached = new Messages(last, thisTick, lastTick, lastServerLineTick);
            snap = cached;
        }
        return cached;
    }
}
