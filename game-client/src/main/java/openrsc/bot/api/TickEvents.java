package openrsc.bot.api;

import java.util.List;
import java.util.Set;
import openrsc.bot.api.Features.KillAnnouncement;
import openrsc.bot.core.world.Opcode;
import openrsc.bot.core.world.RenderEvent;

public record TickEvents(
        List<RenderEvent> renderEvents,
        List<MessageBox> messageBoxes,
        List<NpcChat> npcChat,
        List<PrivateMessage> privateMessages,
        List<ActionBubble> actionBubbles,
        List<NpcKillCredit> killCredits,
        List<NpcDeath> npcDeaths,
        Set<Integer> damagedNpcs,
        int maxNpcDamage,
        int selfSplats,
        int selfDamage,
        Set<Opcode> signals,
        List<Long> clearedChunks,
        List<PlayerChat> selfChat,
        List<PlayerChat> playerChat,
        List<SceneryReplacement> sceneryReplacements,
        List<KillAnnouncement> killAnnouncements,
        List<FriendEntry> friendUpdates) {

    public static final TickEvents EMPTY = new TickEvents(
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(), -1, 0, 0, Set.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public List<ProjectileEvent> projectiles() {
        List<ProjectileEvent> shots = null;
        for (RenderEvent ev : renderEvents) {
            if (ev instanceof RenderEvent.Projectile p) {
                if (shots == null) {
                    shots = new java.util.ArrayList<>();
                }
                shots.add(new ProjectileEvent(
                        p.spriteType(), p.casterIndex(), p.casterIsNpc(), p.victimIndex(), p.victimIsPlayer()));
            }
        }
        return shots == null ? List.of() : List.copyOf(shots);
    }

    public boolean tookDamage() {
        return selfSplats > 0;
    }

    public static int clearedChunkX(long key) {
        return (int) (key >> 32);
    }

    public static int clearedChunkZ(long key) {
        return (int) key;
    }
}
