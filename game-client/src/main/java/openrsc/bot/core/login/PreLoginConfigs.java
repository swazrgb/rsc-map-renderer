package openrsc.bot.core.login;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import openrsc.bot.core.codec.RscPackets;

public record PreLoginConfigs(
        String serverName, BigInteger rsaExponent, BigInteger rsaModulus, Map<Integer, Integer> flags) {

    public static final int WANT_BANK_NOTES = 31;

    public static final int WANT_DROP_X = 34;

    public static final int WANT_EQUIPMENT_TAB = 62;

    public PreLoginConfigs {
        flags = Map.copyOf(flags);
    }

    public boolean wantBankNotes() {
        return flags.getOrDefault(WANT_BANK_NOTES, 0) != 0;
    }

    public boolean wantDropX() {
        return flags.getOrDefault(WANT_DROP_X, 0) != 0;
    }

    public boolean wantEquipmentTab() {
        return flags.getOrDefault(WANT_EQUIPMENT_TAB, 0) != 0;
    }

    public static PreLoginConfigs parse(ByteBuf payload) {
        Map<Integer, Integer> flags = new HashMap<>();
        String serverName = RscPackets.readNlString(payload);
        RscPackets.readNlString(payload);
        readFlags(payload, 3, 41, flags);
        RscPackets.readNlString(payload);
        readFlags(payload, 43, 44, flags);
        RscPackets.readNlString(payload);
        readFlags(payload, 46, 86, flags);
        BigInteger rsaExponent = new BigInteger(RscPackets.readNlString(payload));
        BigInteger rsaModulus = new BigInteger(RscPackets.readNlString(payload));
        readFlags(payload, 89, 90, flags);
        return new PreLoginConfigs(serverName, rsaExponent, rsaModulus, flags);
    }

    public byte[] encode() {
        ByteBuf out = Unpooled.buffer(128);
        RscPackets.writeNlString(out, serverName);
        RscPackets.writeNlString(out, "");
        writeFlags(out, 3, 41);
        RscPackets.writeNlString(out, "");
        writeFlags(out, 43, 44);
        RscPackets.writeNlString(out, "");
        writeFlags(out, 46, 86);
        RscPackets.writeNlString(out, rsaExponent.toString());
        RscPackets.writeNlString(out, rsaModulus.toString());
        writeFlags(out, 89, 90);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    public static PreLoginConfigs wireFlagsOnly(boolean wantBankNotes, boolean wantDropX) {
        return new PreLoginConfigs(
                "",
                BigInteger.ZERO,
                BigInteger.ZERO,
                Map.of(WANT_BANK_NOTES, wantBankNotes ? 1 : 0, WANT_DROP_X, wantDropX ? 1 : 0));
    }

    private void writeFlags(ByteBuf out, int first, int last) {
        for (int field = first; field <= last; field++) {
            out.writeByte(flags.getOrDefault(field, 0));
        }
    }

    private static void readFlags(ByteBuf payload, int first, int last, Map<Integer, Integer> into) {
        for (int field = first; field <= last; field++) {
            into.put(field, payload.readByte() & 0xFF);
        }
    }
}
