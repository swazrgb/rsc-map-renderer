package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/TutorialHandler.java#L12">TutorialHandler.process</a>
 */
public record SkipTutorialPacket() implements Packet {

    @Override
    public boolean restable() {
        return true;
    }
}
