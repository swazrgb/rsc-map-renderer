package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * The server casts once the player is within 4 tiles.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/SpellHandler.java#L1163">SpellHandler.handleItemCast</a>
 */
public record CastOnGroundItemPacket(int spellId, int x, int y, int itemId) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public boolean restable() {
        return true;
    }
}
