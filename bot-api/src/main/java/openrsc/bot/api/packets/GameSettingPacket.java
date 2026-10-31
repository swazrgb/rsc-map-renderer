package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameSettingHandler.java#L12">GameSettingHandler.process</a>
 */
public record GameSettingPacket(int index, int value) implements Packet {

    /**
     * The batch progress bar switch. On a {@code batch_progression} world it starts on, and while it is
     * on the server refuses {@link CommandPacket#QUICK_BANK}.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameSettingHandler.java#L51">GameSettingHandler.process</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L3228">Player.getBatchProgressBar</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L728">Bank.quickFeature</a>
     */
    public static final int BATCH_PROGRESS_BAR = 24;

    @Override
    public boolean cancelsOptionMenu() {
        return false;
    }

    @Override
    public boolean restable() {
        return true;
    }
}
