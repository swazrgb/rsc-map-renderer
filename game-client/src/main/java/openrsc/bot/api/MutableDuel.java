package openrsc.bot.api;

import java.util.List;
import openrsc.bot.api.Duel.StakeItem;
import org.jetbrains.annotations.Nullable;

public final class MutableDuel {
    private boolean windowOpen;
    private int opponentIndex = -1;
    private List<StakeItem> opponentStake = List.of();
    private List<StakeItem> myStake = List.of();
    private int myAccepted;
    private int otherAccepted;
    private boolean confirmOpen;
    private @Nullable String confirmOpponentName;
    private int disallowRetreat;
    private int disallowMagic;
    private int disallowPrayer;
    private int disallowWeapons;
    private Duel snap = Duel.EMPTY;

    public void windowOpened(int opponentIndex) {
        close();
        snap = null;
        windowOpen = true;
        this.opponentIndex = opponentIndex;
    }

    /**
     * The other's stake, which the server sends after either side changes its stake. A change of stake or of the rules
     * takes back both accepts, which the server does not tell either side.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L332-L336">PlayerDuelHandler, a stake</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L416-L420">PlayerDuelHandler, the rules</a>
     */
    public void opponentStake(List<StakeItem> items) {
        snap = null;
        opponentStake = List.copyOf(items);
        unaccept();
    }

    /**
     * The stake the player offered. The server sends a player only the other's stake until the confirm screen, so a
     * client that sends a stake records it here.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L345-L350">ActionSender.sendDuelOpponentItems</a>
     */
    public void myStake(List<StakeItem> items) {
        snap = null;
        myStake = List.copyOf(items);
        unaccept();
    }

    private void unaccept() {
        myAccepted = 0;
        otherAccepted = 0;
    }

    public void settings(int retreat, int magic, int prayer, int weapons) {
        snap = null;
        unaccept();
        disallowRetreat = retreat;
        disallowMagic = magic;
        disallowPrayer = prayer;
        disallowWeapons = weapons;
    }

    public void myAccepted(boolean accepted) {
        snap = null;
        myAccepted = accepted ? 1 : 0;
    }

    public void otherAccepted(boolean accepted) {
        snap = null;
        otherAccepted = accepted ? 1 : 0;
    }

    public void confirm(String opponentName, List<StakeItem> opponent, List<StakeItem> mine) {
        snap = null;
        confirmOpen = true;
        confirmOpponentName = opponentName;
        opponentStake = List.copyOf(opponent);
        myStake = List.copyOf(mine);
    }

    public void close() {
        snap = null;
        windowOpen = false;
        opponentIndex = -1;
        opponentStake = List.of();
        myStake = List.of();
        myAccepted = 0;
        otherAccepted = 0;
        confirmOpen = false;
        confirmOpponentName = null;
        disallowRetreat = disallowMagic = disallowPrayer = disallowWeapons = 0;
    }

    public Duel snapshot() {
        Duel cached = snap;
        if (cached == null) {
            cached = new Duel(
                    windowOpen,
                    opponentIndex,
                    opponentStake,
                    myStake,
                    myAccepted,
                    otherAccepted,
                    confirmOpen,
                    confirmOpponentName,
                    disallowRetreat,
                    disallowMagic,
                    disallowPrayer,
                    disallowWeapons);
            snap = cached;
        }
        return cached;
    }
}
