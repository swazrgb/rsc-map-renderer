package openrsc.bot.api;

import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import org.jetbrains.annotations.Nullable;

public record Dialog(
        List<String> options,
        int menuOrdinal,
        int menuNpcIndex,
        int lastActivityTick,
        int chatToUsNpcIndex,
        int chatToUsTick,
        Map<Integer, NpcDialog> npcDialogs,
        Map<Integer, Integer> busyReplyTicks,
        int gapTicks) {

    /**
     * The longest pause a plugin takes after a line, in 640 ms ticks: one 65 or more long.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1148">Functions.calcDelay</a>
     */
    static final int LONGEST_LINE_DELAY = 4;

    /** Ticks for a packet we send to reach the server, which can be a tick late, and for the plugin to see it. */
    public static final int ANSWER_TICKS = 3;

    /**
     * The ticks a conversation may go quiet and still be running, on {@code sd}'s world: the answer's
     * {@link #ANSWER_TICKS}, then the pause after its echo, a plugin's longest line delay scaled from
     * 640 ms ticks to the world's.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L127">Functions.normalizeTicks</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/RuneScript.java#L306">RuneScript.multi</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/RuneScript.java#L252">RuneScript.say</a>
     */
    public static int inDialogGap(ServerData sd) {
        return gapOf(sd.world().rules().gameTickMs());
    }

    /**
     * The ticks a plugin pauses after an NPC says {@code line} on {@code sd}'s world, its 640 ms pause
     * scaled to the world's tick.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L657-L678">Functions.npcsay</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1148-L1150">Functions.calcDelay</a>
     */
    public static int lineTicks(ServerData sd, String line) {
        int authentic = line.length() >= 65 ? LONGEST_LINE_DELAY : LONGEST_LINE_DELAY - 1;
        return (int) Math.ceil(
                (double) ServerTiming.AUTHENTIC_TICK_MS / sd.world().rules().gameTickMs() * authentic);
    }

    static int gapOf(int gameTickMs) {
        return (int) Math.ceil((double) ServerTiming.AUTHENTIC_TICK_MS / gameTickMs * LONGEST_LINE_DELAY)
                + ANSWER_TICKS;
    }

    public boolean menuOpen() {
        return !options.isEmpty();
    }

    @Override
    public int menuNpcIndex() {
        return menuNpcIndex;
    }

    public boolean inDialog(int nowTick) {
        return nowTick - lastActivityTick <= gapTicks;
    }

    public @Nullable NpcDialog said(int npcServerIndex) {
        return npcDialogs.get(npcServerIndex);
    }

    public boolean inConversationWith(int npcServerIndex, int nowTick) {
        return chatToUsNpcIndex == npcServerIndex && inDialog(nowTick);
    }

    public int busyReplyTick(int npcServerIndex) {
        Integer t = busyReplyTicks.get(npcServerIndex);
        return t == null ? Integer.MIN_VALUE / 2 : t;
    }

    public boolean busyWithAnother(int npcServerIndex, int nowTick, int graceTicks) {
        if (nowTick - busyReplyTick(npcServerIndex) <= graceTicks) {
            return true;
        }
        NpcDialog d = npcDialogs.get(npcServerIndex);
        return d != null && !d.directedAtUs() && nowTick - d.tick() <= graceTicks;
    }
}
