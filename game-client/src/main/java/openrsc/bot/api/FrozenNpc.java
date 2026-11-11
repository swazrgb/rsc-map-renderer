package openrsc.bot.api;

import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import org.jetbrains.annotations.Nullable;

public record FrozenNpc(
        int serverIndex,
        int id,
        @Nullable NpcDef def,
        @Nullable ServerData.NpcSpawn spawn,
        int x,
        int z,
        int sprite,
        int currentHits,
        int maxHits,
        int skullType,
        int wield1,
        int wield2,
        int actionBubbleItemId,
        @Nullable String lastChat,
        int lastChatTick,
        int lastDamage,
        long lastDamageTick,
        long combatSinceTick,
        long retreatTick,
        boolean desynced)
        implements Npc {

    @Override
    public String toString() {
        return "Npc{idx=" + serverIndex + " id=" + id + " @(" + x + "," + z + ") spr=" + sprite + "}";
    }
}
