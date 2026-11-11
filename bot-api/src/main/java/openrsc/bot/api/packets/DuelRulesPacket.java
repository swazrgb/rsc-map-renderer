package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Sets the open duel's rules for both players, each one forbidding what it names, and clears both players' accepts.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L403">PlayerDuelHandler.process</a>
 */
public record DuelRulesPacket(boolean noRetreat, boolean noMagic, boolean noPrayer, boolean noWeapons)
        implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
