package openrsc.bot.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import openrsc.bot.api.Features.KillAnnouncement;
import openrsc.bot.core.world.Opcode;
import openrsc.bot.core.world.RenderEvent;
import org.jetbrains.annotations.Nullable;

public final class MutableTickEvents {

    private final List<RenderEvent> renderEvents = new ArrayList<>();
    private final List<MessageBox> messageBoxes = new ArrayList<>();
    private final List<NpcChat> npcChat = new ArrayList<>();
    private final List<PrivateMessage> privateMessages = new ArrayList<>();
    private final List<ActionBubble> actionBubbles = new ArrayList<>();
    private final List<NpcKillCredit> killCredits = new ArrayList<>();
    private final List<NpcDeath> npcDeaths = new ArrayList<>();
    private final Set<Integer> damagedNpcs = new LinkedHashSet<>();
    private int maxNpcDamage = -1;
    private int selfSplats;
    private int selfDamage;
    private final EnumSet<Opcode> signals = EnumSet.noneOf(Opcode.class);
    private final LinkedHashSet<Long> clearedChunks = new LinkedHashSet<>();
    private final List<SceneryReplacement> sceneryReplacements = new ArrayList<>();
    private final List<PlayerChat> selfChat = new ArrayList<>();
    private final List<PlayerChat> playerChat = new ArrayList<>();
    private final List<KillAnnouncement> killAnnouncements = new ArrayList<>();
    private final List<FriendEntry> friendUpdates = new ArrayList<>();

    private TickEvents frozen = TickEvents.EMPTY;

    public void renderEvent(RenderEvent event) {
        renderEvents.add(event);
    }

    public void messageBox(String text, boolean top) {
        messageBoxes.add(new MessageBox(text == null ? "" : text, top));
    }

    public void npcChat(int npcIndex, int recipient, String text) {
        npcChat.add(new NpcChat(npcIndex, recipient, text));
    }

    public List<NpcChat> openNpcChat() {
        return npcChat.isEmpty() ? List.of() : List.copyOf(npcChat);
    }

    public void privateMessage(PrivateMessage pm) {
        privateMessages.add(pm);
    }

    public void actionBubble(int index, boolean isPlayer, int itemId) {
        actionBubbles.add(new ActionBubble(index, isPlayer, itemId));
    }

    public void killCredit(NpcKillCredit credit) {
        killCredits.add(credit);
    }

    public List<NpcKillCredit> openKillCredits() {
        return killCredits.isEmpty() ? List.of() : List.copyOf(killCredits);
    }

    public void npcDeath(NpcDeath death) {
        npcDeaths.add(death);
    }

    public void npcDamage(int serverIndex, int damage) {
        damagedNpcs.add(serverIndex);
        if (damage > maxNpcDamage) {
            maxNpcDamage = damage;
        }
    }

    public void selfDamage(int amount) {
        selfSplats++;
        selfDamage += amount;
    }

    public void signal(Opcode opcode) {
        signals.add(opcode);
    }

    public void clearedChunk(int chunkX, int chunkZ) {
        clearedChunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
    }

    public void sceneryReplaced(SceneryReplacement replacement) {
        sceneryReplacements.add(replacement);
    }

    public void selfChat(PlayerChat line) {
        selfChat.add(line);
    }

    public void playerChat(PlayerChat line) {
        playerChat.add(line);
    }

    public void killAnnouncement(KillAnnouncement k) {
        killAnnouncements.add(k);
    }

    public void friendUpdate(FriendEntry entry) {
        friendUpdates.add(entry);
    }

    public void bundlePublished(IntFunction<@Nullable String> playerName) {
        boolean any = !renderEvents.isEmpty()
                || !messageBoxes.isEmpty()
                || !npcChat.isEmpty()
                || !privateMessages.isEmpty()
                || !actionBubbles.isEmpty()
                || !killCredits.isEmpty()
                || !npcDeaths.isEmpty()
                || !damagedNpcs.isEmpty()
                || selfSplats > 0
                || !signals.isEmpty()
                || !clearedChunks.isEmpty()
                || !selfChat.isEmpty()
                || !playerChat.isEmpty()
                || !sceneryReplacements.isEmpty()
                || !killAnnouncements.isEmpty()
                || !friendUpdates.isEmpty();
        if (!any) {
            frozen = TickEvents.EMPTY;
            return;
        }
        List<PlayerChat> named;
        if (playerChat.isEmpty()) {
            named = List.of();
        } else {
            List<PlayerChat> out = new ArrayList<>(playerChat.size());
            for (PlayerChat c : playerChat) {
                String name = playerName.apply(c.senderIndex());
                out.add(c.named(name == null || name.isEmpty() ? "player#" + c.senderIndex() : name));
            }
            named = Collections.unmodifiableList(out);
        }
        frozen = new TickEvents(
                copy(renderEvents),
                copy(messageBoxes),
                copy(npcChat),
                copy(privateMessages),
                copy(actionBubbles),
                copy(killCredits),
                copy(npcDeaths),
                damagedNpcs.isEmpty() ? Set.of() : Set.copyOf(damagedNpcs),
                maxNpcDamage,
                selfSplats,
                selfDamage,
                signals.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(signals)),
                copy(clearedChunks.isEmpty() ? List.of() : new ArrayList<>(clearedChunks)),
                copy(selfChat),
                named,
                copy(sceneryReplacements),
                copy(killAnnouncements),
                copy(friendUpdates));
        renderEvents.clear();
        messageBoxes.clear();
        npcChat.clear();
        privateMessages.clear();
        actionBubbles.clear();
        killCredits.clear();
        npcDeaths.clear();
        damagedNpcs.clear();
        maxNpcDamage = -1;
        selfSplats = 0;
        selfDamage = 0;
        signals.clear();
        clearedChunks.clear();
        selfChat.clear();
        playerChat.clear();
        sceneryReplacements.clear();
        killAnnouncements.clear();
        friendUpdates.clear();
    }

    private static <T> List<T> copy(List<T> buffer) {
        return buffer.isEmpty() ? List.of() : List.copyOf(buffer);
    }

    public TickEvents snapshot() {
        return frozen;
    }
}
