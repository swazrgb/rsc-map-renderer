package openrsc.bot.api;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.packets.BankClosePacket;
import openrsc.bot.api.packets.CertSwapPacket;
import openrsc.bot.api.packets.DepositAllWornPacket;
import openrsc.bot.api.packets.DepositPacket;
import openrsc.bot.api.packets.DropPacket;
import openrsc.bot.api.packets.ItemEquipPacket;
import openrsc.bot.api.packets.ItemRemoveToBankPacket;
import openrsc.bot.api.packets.WithdrawPacket;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MutableBankOps {

    private static final Logger LOG = LoggerFactory.getLogger(MutableBankOps.class);

    static final String NO_PACK_ROOM = "You don't have room to hold everything!";

    static final String NO_BANK_ROOM = "You don't have room for that in your bank";

    static final int MAX_LINES = 256;

    private record Line(int itemId, int amount, boolean withdraw, SessionTick sent) {
        @Override
        public String toString() {
            return (withdraw ? "withdraw " : "deposit ") + amount + " of " + itemId + " (t" + sent.tick() + ")";
        }
    }

    private final ArrayDeque<Line> out = new ArrayDeque<>();

    private int roomLinesOwed;

    private @Nullable SessionTick closeSent;

    private @Nullable SessionTick packOpSent;

    private @Nullable String packOp;

    private @Nullable BankOpsOut snap = BankOpsOut.NONE;

    private boolean swappingCerts;

    public synchronized void sent(Packet packet, SessionTick now, boolean bankOpen) {
        if (packet instanceof CertSwapPacket s) {
            swappingCerts = s.on();
            return;
        }
        Line line = packet instanceof WithdrawPacket w
                ? new Line(w.itemId(), w.amount(), true, now)
                : packet instanceof DepositPacket d
                        ? new Line(
                                swappingCerts ? CertSwapPacket.landsAs(d.itemId()) : d.itemId(), d.amount(), false, now)
                        : null;
        if (line != null) {
            if (out.size() >= MAX_LINES) {
                LOG.warn("BANK: {} unanswered lines — dropping the oldest, {}", out.size(), out.peekFirst());
                out.pollFirst();
            }
            out.addLast(line);
            snap = null;
        } else if (packet instanceof BankClosePacket) {
            closeSent = now;
            snap = null;
        } else if (bankOpen
                && (packet instanceof ItemEquipPacket
                        || packet instanceof DropPacket
                        || packet instanceof ItemRemoveToBankPacket
                        || packet instanceof DepositAllWornPacket)) {
            packOpSent = now;
            packOp = packet + " (t" + now.tick() + ")";
            snap = null;
        }
    }

    public synchronized void packChanged() {
        packOpAnswered();
    }

    private void packOpAnswered() {
        if (packOpSent != null) {
            packOpSent = null;
            packOp = null;
            snap = null;
        }
    }

    public synchronized void bankUpdated(int itemId, long before, long after) {
        packOpAnswered();
        int at = 0;
        Line answered = null;
        for (Line l : out) {
            if (l.itemId() == itemId && (l.withdraw() ? after <= before : after > before)) {
                answered = l;
                break;
            }
            at++;
        }
        if (answered == null) {
            return;
        }
        answerThrough(at, "the bank's update of " + itemId);
        if (answered.withdraw() && after > 0 && before - after < answered.amount()) {
            roomLinesOwed++;
        }
    }

    public synchronized void bankEmptied(Map<Integer, Long> candidates) {
        for (Line l : out) {
            Long held = l.withdraw() ? candidates.get(l.itemId()) : null;
            if (held != null) {
                bankUpdated(l.itemId(), held, 0);
                return;
            }
        }
        packOpAnswered();
    }

    public synchronized void serverLine(String text) {
        packOpAnswered();
        boolean pack = NO_PACK_ROOM.equals(text);
        if (!pack && !NO_BANK_ROOM.equals(text)) {
            return;
        }
        if (pack && roomLinesOwed > 0) {
            roomLinesOwed--;
            return;
        }
        int at = 0;
        for (Line l : out) {
            if (l.withdraw() == pack) {
                answerThrough(at, "\"" + text + "\"");
                return;
            }
            at++;
        }
    }

    public synchronized void closed() {
        if (!out.isEmpty() || closeSent != null || packOpSent != null || roomLinesOwed != 0) {
            out.clear();
            closeSent = null;
            packOpSent = null;
            packOp = null;
            roomLinesOwed = 0;
            snap = null;
        }
    }

    public synchronized BankOpsOut snapshot() {
        BankOpsOut s = snap;
        if (s == null) {
            List<String> lines = new ArrayList<>(out.size());
            for (Line l : out) {
                lines.add(l.toString());
            }
            Line oldest = out.peekFirst();
            SessionTick first = oldest == null ? null : oldest.sent();
            if (packOpSent != null) {
                lines.add(packOp);
                first = first == null || packOpSent.isBefore(first) ? packOpSent : first;
            }
            s = new BankOpsOut(lines, first, closeSent);
            snap = s;
        }
        return s;
    }

    private void answerThrough(int at, String by) {
        Iterator<Line> it = out.iterator();
        for (int i = 0; i < at; i++) {
            LOG.warn("BANK: {} refused without a word — {} came after it", it.next(), by);
            it.remove();
        }
        it.next();
        it.remove();
        snap = null;
    }
}
