package openrsc.livemap.dto;

import java.util.List;
import openrsc.bot.api.WindowMove;
import openrsc.bot.api.packets.DuelRulesPacket;
import openrsc.bot.api.packets.TradeOfferPacket;
import openrsc.gamedata.ServerData;
import org.jspecify.annotations.Nullable;

/**
 * A page's move in a bot's open bank, trade or duel screen, as the {@code bank}, {@code trade} and {@code duel}
 * endpoints take it. Each gives the {@link WindowMove} it makes; null for one the player cannot make.
 */
public final class WindowMoveDto {

    private WindowMoveDto() {}

    /**
     * A move at the bank, as {@link WindowMove#bank} names its fields; a {@code slot} or {@code to} left out is no slot,
     * which no move that takes one is made with.
     */
    public record Bank(
            String action,
            int item,
            int amount,
            boolean noted,
            @Nullable Integer slot,
            @Nullable Integer to) {
        public @Nullable WindowMove move(ServerData data) {
            return WindowMove.bank(data, action, item, amount, noted, slot == null ? -1 : slot, to == null ? -1 : to);
        }
    }

    /** {@code offer} the {@code items} as the player's whole side, {@code accept}, {@code confirm} or {@code decline}. */
    public record Trade(String action, @Nullable List<WindowsDto.Stack> items) {
        public @Nullable WindowMove move(ServerData data) {
            return WindowMove.trade(data, action, offers(items));
        }
    }

    /** {@code offer} the {@code items} as the stake, set the {@code rules}, or one of the screens' verbs. */
    public record Duel(
            String action,
            @Nullable List<WindowsDto.Stack> items,
            @Nullable DuelRulesPacket rules) {
        public @Nullable WindowMove move(ServerData data) {
            return WindowMove.duel(data, action, offers(items), rules);
        }
    }

    private static @Nullable List<TradeOfferPacket.Offer> offers(@Nullable List<WindowsDto.Stack> items) {
        return items == null
                ? null
                : items.stream()
                        .map(it -> new TradeOfferPacket.Offer(it.id(), it.amount(), it.noted()))
                        .toList();
    }
}
