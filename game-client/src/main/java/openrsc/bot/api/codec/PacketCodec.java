package openrsc.bot.api.codec;

import io.netty.buffer.ByteBuf;
import openrsc.bot.api.Packet;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface PacketCodec {

    @NotNull
    ByteBuf frame(@NotNull Packet packet);
}
