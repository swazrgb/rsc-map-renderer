package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * A banker's "Collect" command, which moves what the auction house holds for the player into the
 * bank.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/NpcCommand.java#L16">NpcCommand.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/Bankers.java#L170">Bankers.onOpNpc</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/content/market/task/PlayerCollectItemsTask.java#L46">PlayerCollectItemsTask.doTask</a>
 */
@ExtendedOnly(Extension.AUCTION_HOUSE)
public record AuctionCollectPacket(int npcServerIndex) implements Packet {

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
        return Set.of(Extension.AUCTION_HOUSE);
    }
}
