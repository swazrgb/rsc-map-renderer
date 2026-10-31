package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * Needs the bank open; refused while the player is busy, ranging, trading, duelling or in combat.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L34">BankHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/BankHandler.java#L27">BankHandler.process</a>
 */
public record DepositPacket(int itemId, int amount) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }
}
