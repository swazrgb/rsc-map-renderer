package openrsc.bot.api.codec;

import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

public class UnsupportedPacketException extends RuntimeException {

    public UnsupportedPacketException(@NotNull Packet packet, @NotNull PacketCodec codec) {
        super(codec.getClass().getSimpleName() + " has no encoder for "
                + packet.getClass().getName()
                + " — subclass the codec and handle this packet if it is a custom one, or send it on a"
                + " codec that supports it");
    }
}
