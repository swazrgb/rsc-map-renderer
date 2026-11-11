package openrsc.bot.api;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record Duel(
        boolean windowOpen,
        int opponentIndex,
        List<StakeItem> opponentStake,
        List<StakeItem> myStake,
        int myAccepted,
        int otherAccepted,
        boolean confirmOpen,
        @Nullable String confirmOpponentName,
        int disallowRetreat,
        int disallowMagic,
        int disallowPrayer,
        int disallowWeapons) {

    public record StakeItem(int catalogId, boolean noted, int amount) {}

    public static final Duel EMPTY = new Duel(false, -1, List.of(), List.of(), 0, 0, false, null, 0, 0, 0, 0);
}
