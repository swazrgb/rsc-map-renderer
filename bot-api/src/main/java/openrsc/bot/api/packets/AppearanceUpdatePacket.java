package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerAppearanceUpdater.java#L16">PlayerAppearanceUpdater.process</a>
 */
public record AppearanceUpdatePacket(
        int headRestrictions,
        int headType,
        int bodyType,
        int hairColour,
        int topColour,
        int trouserColour,
        int skinColour)
        implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
