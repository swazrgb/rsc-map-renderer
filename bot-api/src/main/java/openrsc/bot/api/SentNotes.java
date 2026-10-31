package openrsc.bot.api;

import java.util.List;
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

    void noteEngageSent(long sessionTick);

    void noteTradeOfferSent();

    void noteDialogActivity(int tick);

    void noteTalkAttempt(int npcServerIndex, int tick);

    <T> void note(TickKey<T> key, T value);
}
