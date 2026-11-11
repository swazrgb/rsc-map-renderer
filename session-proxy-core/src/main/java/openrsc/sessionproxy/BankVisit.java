package openrsc.sessionproxy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.GameState;
import openrsc.bot.api.Npc;
import openrsc.bot.api.Packet;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.packets.AtObjectPacket;
import openrsc.bot.api.packets.BankClosePacket;
import openrsc.bot.api.packets.CommandPacket;
import openrsc.bot.api.packets.DepositPacket;
import openrsc.bot.api.packets.DialogueReplyPacket;
import openrsc.bot.api.packets.TalkToNpcPacket;
import openrsc.bot.api.packets.WithdrawPacket;
import openrsc.gamedata.data.Location;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.Nullable;

/**
 * A bank stop of the map's walk, made on the bank's tile: talk to one of its bankers (or open its chest), take the bank
 * option of the menu, deposit and withdraw until the pack holds what the stop leaves with, and close the bank. Each
 * tick reads the session's state afresh; it remembers only what it sent, so it does not send it twice before the
 * server has answered.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/Bankers.java#L46">Bankers.onTalkNpc</a>
 */
final class BankVisit {

    /** One tick of the stop: the packets to send, then whether the stop is made, or why it failed. */
    record Next(
            List<Packet> packets, boolean done, @Nullable String failed) {
        static Next waiting() {
            return new Next(List.of(), false, null);
        }

        static Next send(Packet... packets) {
            return new Next(List.of(packets), false, null);
        }

        static Next fail(String why) {
            return new Next(List.of(), false, why);
        }
    }

    // The menu option that opens the bank: a banker's, then McGrubor's paid chest's.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/custom/misc/WoodcuttingGuild.java#L91
    private static final List<String[]> BANK_OPTION =
            List.of(new String[] {"access my bank account"}, new String[] {"use bank chest"});

    /** Ticks to wait for an answer to what was sent before sending it again. */
    private static final int ANSWER_TICKS = 6;

    /** Ticks a stop may take before it is given up. */
    private static final int GIVE_UP_TICKS = 100;

    private final @Nullable Location bank;
    private final Map<Integer, Long> withdraw;
    private final Map<Integer, Long> deposit;
    private final Map<Integer, Long> notes;
    private int started = Integer.MIN_VALUE;
    private int openedAt = Integer.MIN_VALUE / 2;
    private int answeredMenu = -1;
    private int movedAt = Integer.MIN_VALUE / 2;
    private long @Nullable [] movedFrom;

    /**
     * @param withdraw the least of each item the pack leaves with
     * @param deposit the most of each item the pack leaves with
     * @param notes the bank notes of each item the pack leaves with
     */
    BankVisit(Point tile, Map<Integer, Long> withdraw, Map<Integer, Long> deposit, Map<Integer, Long> notes) {
        this.bank = Location.bankAt(tile.x(), tile.y());
        this.withdraw = Map.copyOf(withdraw);
        this.deposit = Map.copyOf(deposit);
        this.notes = Map.copyOf(notes);
    }

    Next step(GameState s) {
        int now = s.sessionTick().tick();
        if (started == Integer.MIN_VALUE) {
            started = now;
        }
        if (now - started > GIVE_UP_TICKS) {
            return Next.fail(
                    s.bankOpen()
                            ? "the bank stop did not settle the pack"
                            : open(s) == null ? noBanker() : "the bank did not open");
        }
        if (s.bankOpen()) {
            return settle(s, now);
        }
        if (s.dialog().menuOpen() && s.dialog().menuOrdinal() != answeredMenu) {
            int option = Menus.optionFor(s.dialog().options(), BANK_OPTION);
            if (option < 0) {
                return Next.fail("the banker's menu has no bank option");
            }
            answeredMenu = s.dialog().menuOrdinal();
            return Next.send(new DialogueReplyPacket(option));
        }
        if (s.dialog().inDialog(now) || now - openedAt < ANSWER_TICKS) {
            return Next.waiting();
        }
        // The NPCs in view come a tick after the player lands on the tile, and a banker may have wandered out of it.
        Packet open = open(s);
        if (open == null) {
            return bank == null ? Next.fail(noBanker()) : Next.waiting();
        }
        openedAt = now;
        return Next.send(open);
    }

    private String noBanker() {
        return "no banker in view at " + (bank == null ? "the bank" : bank.label());
    }

    // Deposits first, to make room, then withdrawals; all in one tick, then nothing more until the pack changes.
    private Next settle(GameState s, int now) {
        List<Packet> moves = new ArrayList<>();
        deposit.forEach((id, most) -> lower(s, id, NoteForm.UNNOTED, most, moves));
        notes.forEach((id, most) -> lower(s, id, NoteForm.NOTED, most, moves));
        withdraw.forEach((id, least) -> raise(s, id, false, least, moves));
        notes.forEach((id, least) -> raise(s, id, true, least, moves));
        if (moves.isEmpty()) {
            return new Next(List.of(new BankClosePacket()), true, null);
        }
        long[] held = held(s);
        if (movedFrom != null && Arrays.equals(movedFrom, held) && now - movedAt < ANSWER_TICKS) {
            return Next.waiting();
        }
        if (movedFrom != null && Arrays.equals(movedFrom, held)) {
            return Next.fail("the bank refused to move items");
        }
        movedFrom = held;
        movedAt = now;
        return new Next(moves, false, null);
    }

    // The server deposits from the last slot holding the item, noted or not, then from the last one left: to take the
    // excess of one form, the deposit also takes the other form's slots behind it, and withdrawals right after it put
    // those back. With an equipment tab a worn item is not among the server's slots at all.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L547-L549
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L628-L638
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L643-L648
    private static void lower(GameState s, int id, NoteForm form, long most, List<Packet> moves) {
        long excess = s.inventory().count(form, id) - most;
        if (excess <= 0) {
            return;
        }
        boolean noted = form == NoteForm.NOTED;
        long taken = 0;
        long behindNoted = 0;
        long behindUnnoted = 0;
        List<InventorySlot> slots = s.inventory().slots();
        boolean wornOff = s.inventory().wornOffPack();
        for (int i = slots.size() - 1; i >= 0 && taken < excess; i--) {
            InventorySlot slot = slots.get(i);
            if (slot.id() != id || (wornOff && slot.equipped())) {
                continue;
            }
            if (slot.noted() == noted) {
                taken += Math.min(slot.amount(), excess - taken);
            } else if (slot.noted()) {
                behindNoted += slot.amount();
            } else {
                behindUnnoted += slot.amount();
            }
        }
        moves.add(new DepositPacket(id, (int) (behindNoted + behindUnnoted + excess)));
        if (behindUnnoted > 0) {
            moves.add(new WithdrawPacket(id, (int) behindUnnoted, false));
        }
        if (behindNoted > 0) {
            moves.add(new WithdrawPacket(id, (int) behindNoted, true));
        }
    }

    private static void raise(GameState s, int id, boolean noted, long least, List<Packet> moves) {
        long missing = least - s.inventory().count(noted ? NoteForm.NOTED : NoteForm.UNNOTED, id);
        long banked = s.bank().count(id);
        if (missing > 0 && banked > 0) {
            moves.add(new WithdrawPacket(id, (int) Math.min(missing, banked), noted));
        }
    }

    private long[] held(GameState s) {
        List<Integer> ids = new ArrayList<>(deposit.keySet());
        ids.addAll(withdraw.keySet());
        ids.addAll(notes.keySet());
        long[] out = new long[2 * ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            out[2 * i] = s.inventory().count(NoteForm.UNNOTED, ids.get(i));
            out[2 * i + 1] = s.inventory().count(NoteForm.NOTED, ids.get(i));
        }
        return out;
    }

    // The bank's chest (a staffed one asks its keeper first), its quick-bank command, or its nearest banker in view.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/ShantayPassNpcs.java#L463
    private @Nullable Packet open(GameState s) {
        if (bank == null) {
            return null;
        }
        Point chest = bank.chest();
        if (chest != null) {
            return new AtObjectPacket(chest.x(), chest.y());
        }
        if (bank.quickBankOnly()) {
            return new CommandPacket(CommandPacket.QUICK_BANK);
        }
        Npc banker = s.nearestStaff(bank);
        return banker == null ? null : new TalkToNpcPacket(banker.serverIndex());
    }
}
