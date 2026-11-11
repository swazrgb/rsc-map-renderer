package openrsc.bot.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import openrsc.bot.api.packets.BankClosePacket;
import openrsc.bot.api.packets.BankInsertPacket;
import openrsc.bot.api.packets.BankPresetLoadPacket;
import openrsc.bot.api.packets.BankPresetSavePacket;
import openrsc.bot.api.packets.BankSwapPacket;
import openrsc.bot.api.packets.DepositAllPacket;
import openrsc.bot.api.packets.DepositAllWornPacket;
import openrsc.bot.api.packets.DepositPacket;
import openrsc.bot.api.packets.DuelAcceptFirstPacket;
import openrsc.bot.api.packets.DuelConfirmPacket;
import openrsc.bot.api.packets.DuelDeclinePacket;
import openrsc.bot.api.packets.DuelOfferPacket;
import openrsc.bot.api.packets.DuelRulesPacket;
import openrsc.bot.api.packets.ItemRemoveToBankPacket;
import openrsc.bot.api.packets.TradeAcceptFirstPacket;
import openrsc.bot.api.packets.TradeConfirmPacket;
import openrsc.bot.api.packets.TradeDeclinePacket;
import openrsc.bot.api.packets.TradeOfferPacket;
import openrsc.bot.api.packets.WithdrawPacket;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.defs.ItemDef;
import org.jetbrains.annotations.Nullable;

/**
 * A move a player makes in the bank, trade or duel screen: the packet that makes it, the window it is made in and the
 * step of that window it belongs to. The factories take a move as the map's pages name it, and give null for one the
 * player cannot make.
 */
public record WindowMove(Screen screen, Step step, Packet packet) {

    public enum Screen {
        BANK,
        TRADE,
        DUEL
    }

    /** Where in its window a move is made. */
    public enum Step {
        /** On any of its screens. */
        OPEN,
        /** On the first screen. */
        FIRST,
        /** On the first screen, until both sides have accepted it. */
        OFFER,
        /** On the confirm screen. */
        CONFIRM
    }

    /**
     * Why the move cannot be made in {@code s}, or null when it can. The server marks a player suspicious and resets the
     * trade or duel for a move off its step, and marks one who unequips a slot nothing is worn in.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L222-L230">PlayerTradeHandler, an offer</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L322-L328">PlayerTradeHandler, a confirm</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L323-L330">PlayerDuelHandler, a stake</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUnequip.java#L82-L86">ItemUnequip.process, an empty slot</a>
     */
    public @Nullable String refusal(GameState s) {
        if (screen == Screen.BANK) {
            if (!s.bankOpen()) {
                return "no bank is open";
            }
            if (packet instanceof ItemRemoveToBankPacket p
                    && s.selfPanel().equipment().stream().noneMatch(w -> w.wieldPos() == p.wieldPosition())) {
                return "nothing is worn there";
            }
            return null;
        }
        boolean trade = screen == Screen.TRADE;
        boolean confirm = trade ? s.trade().confirmOpen() : s.duel().confirmOpen();
        boolean first = !confirm && (trade ? s.trade().open() : s.duel().windowOpen());
        String name = screen.name().toLowerCase(Locale.ROOT);
        if (!first && !confirm) {
            return "no " + name + " is open";
        }
        boolean bothAccepted = trade
                ? s.trade().accepted() && s.trade().otherAccepted()
                : s.duel().myAccepted() != 0 && s.duel().otherAccepted() != 0;
        return switch (step) {
            case OPEN -> null;
            case FIRST -> first ? null : "the " + name + " is on its confirm screen";
            case OFFER ->
                !first ? "the " + name + " is on its confirm screen" : bothAccepted ? "both sides have accepted" : null;
            case CONFIRM -> confirm ? null : "the " + name + " is not on its confirm screen";
        };
    }

    /**
     * A move at the open bank, by its {@code action}: {@code "withdraw"} or {@code "deposit"} moves {@code amount} of
     * {@code item}, as notes only where the world has them; {@code "deposit_inventory"} and {@code "deposit_worn"} put
     * the whole pack or everything worn in the bank, and {@code "unequip"} the worn {@code item}; {@code "swap"} and
     * {@code "insert"} move the stack in bank slot {@code slot} to slot {@code to}; {@code "load_preset"} and
     * {@code "save_preset"} take preset {@code slot}; {@code "close"} closes the bank. Null too for one the world lacks.
     */
    public static @Nullable WindowMove bank(
            ServerData data, @Nullable String action, int item, int amount, boolean noted, int slot, int to) {
        if (action == null) {
            return null;
        }
        ServerRules rules = data.world().rules();
        boolean custom = rules.customBanks();
        Packet p =
                switch (action) {
                    case "withdraw" ->
                        amount < 1 || (noted && !rules.bankNotes()) ? null : new WithdrawPacket(item, amount, noted);
                    case "deposit" -> amount < 1 ? null : new DepositPacket(item, amount);
                    case "deposit_inventory" -> custom ? new DepositAllPacket() : null;
                    case "deposit_worn" -> custom && rules.equipmentTab() ? new DepositAllWornPacket() : null;
                    case "unequip" -> {
                        ItemDef def = data.item(item);
                        yield rules.equipmentTab() && def != null && def.wearable()
                                ? new ItemRemoveToBankPacket(def.wearSlot())
                                : null;
                    }
                    case "swap" -> custom && slot >= 0 && to >= 0 && slot != to ? new BankSwapPacket(slot, to) : null;
                    case "insert" ->
                        custom && slot >= 0 && to >= 0 && slot != to ? new BankInsertPacket(slot, to) : null;
                    case "load_preset" -> presets(rules, slot) ? new BankPresetLoadPacket(slot) : null;
                    case "save_preset" -> presets(rules, slot) ? new BankPresetSavePacket(slot) : null;
                    case "close" -> new BankClosePacket();
                    default -> null;
                };
        return p == null ? null : new WindowMove(Screen.BANK, Step.OPEN, p);
    }

    /**
     * How many presets a bank keeps.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/BankPreset.java#L18">BankPreset.PRESET_COUNT</a>
     */
    public static final int PRESETS = 2;

    /** Whether the world keeps bank presets, and {@code slot} is one of them. */
    public static boolean presets(ServerRules rules, int slot) {
        return rules.customBanks() && rules.bankPresets() && rules.equipmentTab() && slot >= 0 && slot < PRESETS;
    }

    /**
     * Action {@code "offer"} makes {@code offers} the player's whole side; {@code "accept"}, {@code "confirm"} and
     * {@code "decline"} take the screens' buttons.
     */
    public static @Nullable WindowMove trade(
            ServerData data, @Nullable String action, @Nullable List<TradeOfferPacket.Offer> offers) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "offer" -> {
                List<TradeOfferPacket.Offer> wire = onTheWire(data, offers, TradeOfferPacket.MAX_ITEMS);
                yield wire == null ? null : new WindowMove(Screen.TRADE, Step.OFFER, new TradeOfferPacket(wire));
            }
            case "accept" -> new WindowMove(Screen.TRADE, Step.FIRST, new TradeAcceptFirstPacket());
            case "confirm" -> new WindowMove(Screen.TRADE, Step.CONFIRM, new TradeConfirmPacket());
            case "decline" -> new WindowMove(Screen.TRADE, Step.OPEN, new TradeDeclinePacket());
            default -> null;
        };
    }

    /**
     * Action {@code "offer"} makes {@code offers} the whole stake and {@code "rules"} sets {@code rules};
     * {@code "accept"}, {@code "confirm"} and {@code "decline"} take the screens' buttons.
     */
    public static @Nullable WindowMove duel(
            ServerData data,
            @Nullable String action,
            @Nullable List<TradeOfferPacket.Offer> offers,
            @Nullable DuelRulesPacket rules) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "offer" -> {
                List<TradeOfferPacket.Offer> wire = onTheWire(data, offers, DuelOfferPacket.MAX_ITEMS);
                yield wire == null ? null : new WindowMove(Screen.DUEL, Step.OFFER, new DuelOfferPacket(wire));
            }
            case "rules" -> rules == null ? null : new WindowMove(Screen.DUEL, Step.FIRST, rules);
            case "accept" -> new WindowMove(Screen.DUEL, Step.FIRST, new DuelAcceptFirstPacket());
            case "confirm" -> new WindowMove(Screen.DUEL, Step.CONFIRM, new DuelConfirmPacket());
            case "decline" -> new WindowMove(Screen.DUEL, Step.OPEN, new DuelDeclinePacket());
            default -> null;
        };
    }

    // At most {@code max} stacks, notes only where the world has them; null for an offer the player cannot make. An
    // item that does not stack goes one to a stack, since the server keeps one of a stack of it:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/ItemContainer.java#L39-L40
    // An untradable item is never offered, as the game's client refuses to send one:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L17313-L17317
    private static @Nullable List<TradeOfferPacket.Offer> onTheWire(
            ServerData data, @Nullable List<TradeOfferPacket.Offer> offers, int max) {
        if (offers == null) {
            return null;
        }
        List<TradeOfferPacket.Offer> out = new ArrayList<>();
        for (TradeOfferPacket.Offer it : offers) {
            ItemDef def = data.item(it.itemId());
            if (it.amount() < 1
                    || (it.noted() && !data.world().rules().bankNotes())
                    || (def != null && def.untradable())) {
                return null;
            }
            if (it.noted() || data.isStackable(it.itemId())) {
                out.add(it);
            } else {
                for (int i = 0; i < it.amount() && out.size() <= max; i++) {
                    out.add(new TradeOfferPacket.Offer(it.itemId(), 1));
                }
            }
            if (out.size() > max) {
                return null;
            }
        }
        return out;
    }
}
