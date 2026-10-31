package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L240">SpellHandler.process</a>
 */
public record CastOnInventoryItemPacket(int spellId, int slot) implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
