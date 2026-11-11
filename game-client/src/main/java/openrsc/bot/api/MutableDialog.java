package openrsc.bot.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class MutableDialog {

    private List<String> options = List.of();
    private int menuOrdinal = -1;
    private int menuNpcIndex = -1;

    private int lastActivityTick = Integer.MIN_VALUE / 2;
    private int chatToUsNpcIndex = -1;
    private int chatToUsTick = Integer.MIN_VALUE / 2;
    private final Map<Integer, NpcDialog> npcDialogs = new HashMap<>();
    private final Map<Integer, Integer> busyReplyTicks = new HashMap<>();
    private Dialog snap;

    private final int gapTicks;

    /** A conversation goes quiet for at most {@code gapTicks} and is still running. */
    public MutableDialog(int gapTicks) {
        this.gapTicks = gapTicks;
    }

    public int gapTicks() {
        return gapTicks;
    }

    public void menuOpened(List<String> options, int tick) {
        snap = null;
        menuOrdinal++;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.menuNpcIndex = tick - chatToUsTick <= gapTicks ? chatToUsNpcIndex : -1;
        if (menuNpcIndex < 0) {
            this.chatToUsNpcIndex = -1;
        }
    }

    public void menuClosed() {
        snap = null;
        this.options = List.of();
        this.menuNpcIndex = -1;
    }

    public void menuCancelledByOurSend() {
        snap = null;
        this.options = List.of();
        this.menuOrdinal = -1;
        this.menuNpcIndex = -1;
    }

    public void npcChatted(int npcServerIndex, int recipientIndex, boolean directedAtUs, String message, int tick) {
        snap = null;
        npcDialogs.put(npcServerIndex, new NpcDialog(npcServerIndex, tick, recipientIndex, directedAtUs, message));
        if (directedAtUs) {
            this.lastActivityTick = tick;
            this.chatToUsNpcIndex = npcServerIndex;
            this.chatToUsTick = tick;
        }
    }

    private int lastTalkAttemptNpcIndex = -1;

    private int lastTalkAttemptTick = Integer.MIN_VALUE / 2;

    public int lastTalkAttemptNpcIndex() {
        return lastTalkAttemptNpcIndex;
    }

    public int lastTalkAttemptTick() {
        return lastTalkAttemptTick;
    }

    public void talkAttempt(int npcServerIndex, int tick) {
        this.lastTalkAttemptNpcIndex = npcServerIndex;
        this.lastTalkAttemptTick = tick;
    }

    public void activity(int tick) {
        snap = null;
        this.lastActivityTick = tick;
    }

    public void resolved() {
        snap = null;
        this.lastActivityTick = Integer.MIN_VALUE / 2;
    }

    public void npcBusyReply(int npcServerIndex, int tick) {
        snap = null;
        busyReplyTicks.put(npcServerIndex, tick);
    }

    public void npcRemoved(int npcServerIndex) {
        snap = null;
        npcDialogs.remove(npcServerIndex);
        busyReplyTicks.remove(npcServerIndex);
    }

    public List<String> options() {
        return options;
    }

    public int menuOrdinal() {
        return Math.max(0, menuOrdinal);
    }

    public int chatToUsNpcIndex() {
        return chatToUsNpcIndex;
    }

    public int chatToUsTick() {
        return chatToUsTick;
    }

    public @Nullable NpcDialog npcDialog(int npcServerIndex) {
        return npcDialogs.get(npcServerIndex);
    }

    public Dialog snapshot() {
        Dialog cached = snap;
        if (cached == null) {
            cached = new Dialog(
                    options,
                    menuOrdinal(),
                    menuNpcIndex,
                    lastActivityTick,
                    chatToUsNpcIndex,
                    chatToUsTick,
                    Map.copyOf(npcDialogs),
                    Map.copyOf(busyReplyTicks),
                    gapTicks);
            snap = cached;
        }
        return cached;
    }
}
