package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Sets all four privacy settings at once, each {@link #BLOCK_NONE}, {@link #BLOCK_NON_FRIENDS} or {@link #BLOCK_ALL}.
 * The server keeps them across logins and sends them back only at the next login.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PrivacySettingHandler.java#L10">PrivacySettingHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/PlayerSettings.java#L229">PlayerSettings.BlockingMode</a>
 */
public record PrivacySettingsPacket(int blockChat, int blockPrivate, int blockTrade, int blockDuel) implements Packet {

    public static final int BLOCK_NONE = 0;
    public static final int BLOCK_NON_FRIENDS = 1;
    public static final int BLOCK_ALL = 2;

    @Override
    public boolean cancelsOptionMenu() {
        return false;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.notePrivacySent(blockChat, blockPrivate, blockTrade, blockDuel);
    }

    @Override
    public boolean restable() {
        return true;
    }
}
