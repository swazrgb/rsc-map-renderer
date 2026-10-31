package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * The "Trade" command the server gives a shopkeeper when {@code right_click_trade} is on, which
 * opens its shop with no dialogue.
 *
 * @param command which of the keeper's commands is "Trade": 1 or 2
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/NpcCommand.java#L16">NpcCommand.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/external/EntityHandler.java#L346">EntityHandler.customNpcConditions</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/AbstractShop.java#L31">AbstractShop.onOpNpc</a>
 */
@ExtendedOnly(Extension.QUICK_TRADE)
public record QuickTradePacket(int npcServerIndex, int command) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteDialogActivity(now.tick());
        live.noteTalkAttempt(npcServerIndex, now.tick());
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.QUICK_TRADE);
    }
}
