package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * The server casts at once: there is no walk-to action and no reach check.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L381">SpellHandler.process</a>
 */
public record CastOnSceneryPacket(int spellId, int x, int y) implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
