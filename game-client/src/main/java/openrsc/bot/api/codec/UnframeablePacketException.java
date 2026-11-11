package openrsc.bot.api.codec;

import org.jetbrains.annotations.NotNull;

public class UnframeablePacketException extends RuntimeException {

    public UnframeablePacketException(@NotNull String why) {
        super(why);
    }
}
