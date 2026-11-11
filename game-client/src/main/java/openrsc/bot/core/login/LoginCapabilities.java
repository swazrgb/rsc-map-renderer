package openrsc.bot.core.login;

import io.netty.buffer.ByteBuf;
import openrsc.bot.core.codec.RscPackets;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.gamedata.defs.ObjectDefs;

public record LoginCapabilities(int maxItemId, int maxSceneryId, int maxBankItems) {

    public static final LoginCapabilities AUTHENTIC =
            new LoginCapabilities(1289, 1188, ServerRules.MEMBERS_BANK_CAPACITY);

    public static LoginCapabilities of(ServerData serverData) {
        return of(serverData.world().rules(), serverData.itemDefs(), serverData.objectDefs());
    }

    public static LoginCapabilities of(ServerRules rules, ItemDefs items, ObjectDefs scenery) {
        return new LoginCapabilities(
                ceilingOr(rules.maxItemId(), items.size() - 1),
                ceilingOr(rules.maxSceneryId(), scenery.size() - 1),
                rules.bankCapacity());
    }

    private static int ceilingOr(int ceiling, int known) {
        return ceiling == ServerRules.UNRESTRICTED ? known : ceiling;
    }

    public void write(ByteBuf b, String mapHash) {
        b.writeShort(228);
        b.writeInt(maxItemId);
        b.writeInt(793);
        b.writeInt(maxSceneryId);
        b.writeShort(13);
        b.writeShort(47);
        b.writeByte(17);
        b.writeShort(5);
        b.writeShort(54);
        b.writeShort(24);
        b.writeInt(213);
        b.writeByte(1);
        b.writeShort(6);
        b.writeInt(4);
        b.writeInt(9);
        b.writeInt(14);
        b.writeShort(49);
        b.writeInt(37);
        b.writeByte(0);
        b.writeByte(5);
        b.writeInt(maxBankItems);
        RscPackets.writeNlString(b, mapHash);
    }
}
