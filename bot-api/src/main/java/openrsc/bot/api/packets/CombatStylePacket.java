package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @param style 0 controlled, 1 aggressive, 2 accurate, 3 defensive
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/CombatStyleHandler.java#L11">CombatStyleHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/constants/Skills.java#L22">Skills</a>
 */
public record CombatStylePacket(int style) implements Packet {

    @Override
    public boolean cancelsOptionMenu() {
        return false;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteStyleSent(style);
    }

    @Override
    public boolean restable() {
        return true;
    }
}
