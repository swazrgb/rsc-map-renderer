package openrsc.bot.api;

import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import org.jetbrains.annotations.Nullable;

public final class MutableNpc implements Npc {
    private final int serverIndex;
    private final int id;
    private final @Nullable NpcDef def;
    private final @Nullable ServerData.NpcSpawn spawn;
    private int x;
    private int z;
    private int sprite;
    private int currentHits;
    private int maxHits;
    private int skullType;
    private int wield1;
    private int wield2;
    private int actionBubbleItemId;
    private @Nullable String chatMessage;
    private int chatTick = -1;
    private int lastDamage = -1;
    private long lastDamageTick = -1;
    private long combatSinceTick = -1;
    private long retreatTick = Long.MIN_VALUE;
    private boolean desynced;
    private boolean steppedOntoSolid;
    private FrozenNpc snap;

    public MutableNpc(
            int serverIndex,
            int id,
            int x,
            int z,
            int sprite,
            @Nullable NpcDef def,
            @Nullable ServerData.NpcSpawn spawn) {
        this.serverIndex = serverIndex;
        this.id = id;
        this.def = def;
        this.spawn = spawn;
        this.x = x;
        this.z = z;
        this.sprite = sprite;
    }

    public FrozenNpc snapshot() {
        FrozenNpc cached = snap;
        if (cached == null) {
            cached = new FrozenNpc(
                    serverIndex,
                    id,
                    def,
                    spawn,
                    x,
                    z,
                    sprite,
                    currentHits,
                    maxHits,
                    skullType,
                    wield1,
                    wield2,
                    actionBubbleItemId,
                    chatMessage,
                    chatTick,
                    lastDamage,
                    lastDamageTick,
                    combatSinceTick,
                    retreatTick,
                    desynced);
            snap = cached;
        }
        return cached;
    }

    @Override
    public int serverIndex() {
        return serverIndex;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public @Nullable NpcDef def() {
        return def;
    }

    @Override
    public @Nullable ServerData.NpcSpawn spawn() {
        return spawn;
    }

    @Override
    public int x() {
        return x;
    }

    @Override
    public int z() {
        return z;
    }

    @Override
    public int sprite() {
        return sprite;
    }

    @Override
    public int currentHits() {
        return currentHits;
    }

    @Override
    public int maxHits() {
        return maxHits;
    }

    @Override
    public int skullType() {
        return skullType;
    }

    @Override
    public int wield1() {
        return wield1;
    }

    @Override
    public int wield2() {
        return wield2;
    }

    @Override
    public int actionBubbleItemId() {
        return actionBubbleItemId;
    }

    @Override
    public long combatSinceTick() {
        return combatSinceTick;
    }

    @Override
    public int lastDamage() {
        return lastDamage;
    }

    @Override
    public long lastDamageTick() {
        return lastDamageTick;
    }

    @Override
    public @Nullable String lastChat() {
        return chatMessage;
    }

    @Override
    public int lastChatTick() {
        return chatTick;
    }

    public void setCombatSinceTick(long tick) {
        if (combatSinceTick != tick) {
            snap = null;
            combatSinceTick = tick;
        }
    }

    public void setX(int x) {
        if (this.x != x) {
            snap = null;
            this.x = x;
        }
    }

    public void setZ(int z) {
        if (this.z != z) {
            snap = null;
            this.z = z;
        }
    }

    public void setSprite(int sprite) {
        if (this.sprite != sprite) {
            snap = null;
            this.sprite = sprite;
        }
    }

    @Override
    public boolean desynced() {
        return desynced;
    }

    public void noteStep(boolean ontoSolid) {
        steppedOntoSolid = ontoSolid;
    }

    /** Confirms the last step once the tick's scenery is in: still solid means the server never made it. */
    public void confirmStep(boolean stillSolid) {
        if (steppedOntoSolid && stillSolid) {
            setDesynced(true);
        }
        steppedOntoSolid = false;
    }

    public void setDesynced(boolean desynced) {
        steppedOntoSolid = false;
        if (this.desynced != desynced) {
            snap = null;
            this.desynced = desynced;
        }
    }

    public void noteRetreat(long tick) {
        snap = null;
        this.retreatTick = tick;
    }

    public void noteDamage(int damage, long tick) {
        snap = null;
        this.lastDamage = damage;
        this.lastDamageTick = tick;
    }

    public void setHits(int current, int max) {
        if (currentHits != current || maxHits != max) {
            snap = null;
            this.currentHits = current;
            this.maxHits = max;
        }
    }

    public void setSkullType(int skullType) {
        if (this.skullType != skullType) {
            snap = null;
            this.skullType = skullType;
        }
    }

    public void setWield(int wield1, int wield2) {
        if (this.wield1 != wield1 || this.wield2 != wield2) {
            snap = null;
            this.wield1 = wield1;
            this.wield2 = wield2;
        }
    }

    public void setActionBubbleItemId(int itemId) {
        if (actionBubbleItemId != itemId) {
            snap = null;
            actionBubbleItemId = itemId;
        }
    }

    public void setChatMessage(String chatMessage, int tick) {
        snap = null;
        this.chatMessage = chatMessage;
        this.chatTick = tick;
    }

    @Override
    public String toString() {
        return "Npc{idx=" + serverIndex + " id=" + id + " @(" + x + "," + z + ") spr=" + sprite + "}";
    }
}
