package openrsc.bot.core.net;

import io.netty.buffer.ByteBuf;

public sealed interface InboundEvent {

    record Packet(int opcode, ByteBuf payload) implements InboundEvent {}

    record Disconnected(String reason, Throwable cause) implements InboundEvent {}
}
