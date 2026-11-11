package openrsc.sessionproxy;

import java.util.Set;
import openrsc.bot.core.world.Opcode;
import openrsc.bot.core.world.PacketDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs when a bot's action leaves for the server and which of the server's tick bundles answer it.
 * A bundle is cut where the bot cuts it: it holds one tick boundary and the tail after it, and ends
 * at the first packet that cannot continue that tail.
 *
 * <p>{@code out op=.. b=K +Nms} is an action sent N ms after the K-th boundary. The bundle holding
 * boundary K+1, and the two after it, are then logged as {@code in b=..} with their opcodes in
 * arrival order, {@code ^op} marking where the action was sent, and {@code dt} the gap since the
 * previous boundary. Walks are not logged.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/GameStateUpdater.java#L78-86">GameStateUpdater.sendUpdatePackets</a>
 */
final class WireTiming {

    private static final Logger LOG = LoggerFactory.getLogger("openrsc.sessionproxy.timing");

    private static final int BUNDLES_AFTER_ACTION = 3;

    private static final Set<Integer> WALKS = Set.of(16, 187);

    private static final int TICK_END = Opcode.SEND_TICK_END.wire();

    private final String username;
    private long boundaries;
    private long boundaryNanos = System.nanoTime();
    private long boundaryGapMs;
    private boolean inTail;
    private int watch;
    private final StringBuilder bundle = new StringBuilder();

    WireTiming(String username) {
        this.username = username;
    }

    synchronized void outbound(int opcode, int length, long sentNanos) {
        if (WALKS.contains(opcode) || !LOG.isInfoEnabled()) {
            return;
        }
        LOG.info(
                "[{}] out op={} len={} b={} +{}ms",
                username,
                opcode,
                length,
                boundaries,
                (sentNanos - boundaryNanos) / 1_000_000);
        bundle.append('^').append(opcode).append(' ');
        watch = BUNDLES_AFTER_ACTION + (inTail ? 1 : 0);
    }

    synchronized void inbound(int opcode) {
        if (inTail && (opcode == TICK_END || !PacketDispatcher.continuesTickBundle(opcode))) {
            closeBundle();
            if (opcode == TICK_END) {
                return;
            }
        }
        bundle.append(opcode).append(' ');
        if (PacketDispatcher.beginsTickBundle(opcode)) {
            long now = System.nanoTime();
            boundaries++;
            boundaryGapMs = (now - boundaryNanos) / 1_000_000;
            boundaryNanos = now;
            inTail = true;
        }
    }

    private void closeBundle() {
        if (watch > 0) {
            LOG.info(
                    "[{}] in b={} dt={}ms [{}]",
                    username,
                    boundaries,
                    boundaryGapMs,
                    bundle.toString().trim());
            watch--;
        }
        bundle.setLength(0);
        inTail = false;
    }
}
