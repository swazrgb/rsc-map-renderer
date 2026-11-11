package openrsc.bot.api;

import java.util.List;
import openrsc.bot.api.packets.TradeOfferPacket;
import openrsc.gamedata.geometry.Point;

/**
 * Bot state updated from what the bot sends, through {@link Packet#noteSent}. Each tick argument is
 * the session tick the packet went out on.
 */
public interface SentNotes {

    void noteAttackSent(SessionTick now, int targetIndex);

    void noteWalkSent(int tick, List<Point> waypoints);

    void notePrayerSent(int prayerId, boolean on);

    void noteStyleSent(int style);

    /** The four privacy settings went out; the server takes them as sent and does not echo them. */
    void notePrivacySent(int blockChat, int blockPrivate, int blockTrade, int blockDuel);

    /** A spell went out at the NPC or player with server index {@code targetIndex}, or at self with -1. */
    void noteCastSent(SessionTick now, int spellId, int targetIndex);

    void noteUseOnScenerySent(SessionTick now, int slot);

    void noteItemCommandSent(SessionTick now, int slot);

    void noteWornItemCommandSent(SessionTick now, int itemId);

    /**
     * A ranged attack went out at {@code targetServerIndex}. While the server's {@code RangeEvent} for
     * it runs, the server ignores every spell cast.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L241">SpellHandler.process</a>
     */
    void noteRangeArmed(int targetServerIndex, int tick);

    /** Something went out that stops a running ranged attack. */
    void noteRangeKilled(int tick);

    /**
     * Bank preset {@code slot} was saved: it holds what the player carries and wears. The server sends the presets
     * only at login, so the client keeps its own copy of a save.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L136-L172">BankHandler.process</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L2143-L2144">ActionSender.sendLogin</a>
     */
    void notePresetSaved(int slot);

    void noteEngageSent(long sessionTick);

    void noteTradeOfferSent();

    /**
     * The player accepted the open trade's first screen, which the server never confirms, only cancels.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L1745-L1754">ActionSender.sendOwnTradeAcceptUpdate</a>
     */
    void noteTradeAcceptSent();

    /**
     * The player staked {@code offers} in the open duel, which the server sends back only on the confirm screen.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/ActionSender.java#L345-L350">ActionSender.sendDuelOpponentItems</a>
     */
    void noteDuelStakeSent(List<TradeOfferPacket.Offer> offers);

    void noteDialogActivity(int tick);

    void noteTalkAttempt(int npcServerIndex, int tick);

    <T> void note(TickKey<T> key, T value);
}
