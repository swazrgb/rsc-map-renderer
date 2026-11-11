package openrsc.bot.core.login;

import io.netty.buffer.ByteBuf;
import openrsc.bot.core.codec.RscPackets;

public final class PreLoginConfigsRequest {

    public static final int OPCODE = 19;

    private PreLoginConfigsRequest() {}

    public static ByteBuf build() {
        return RscPackets.finish(RscPackets.create(OPCODE));
    }
}
