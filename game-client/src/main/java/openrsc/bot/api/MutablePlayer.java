package openrsc.bot.api;

import java.util.ArrayDeque;
import java.util.List;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.Nullable;

public final class MutablePlayer implements Player {
    private final int serverIndex;
    private int x;
    private int z;
    private int sprite;
    private @Nullable String username;
    private Appearance appearance = Appearance.UNKNOWN;
    private int currentHits;
    private int maxHits;
    private int lastDamage = -1;
    private long lastDamageTick = -1;
    private @Nullable String lastChat;
    private long lastChatTick = -1;
    private int actionBubbleItemId;
    private long actionBubbleTick = -1;
    private long retreatTick = Long.MIN_VALUE;
    private int stepDir = -1;
    private final ArrayDeque<Point> trail = new ArrayDeque<>();
    private FrozenPlayer snap;

    public MutablePlayer(int serverIndex, int x, int z, int sprite) {
        this.serverIndex = serverIndex;
        this.x = x;
        this.z = z;
        this.sprite = sprite;
    }

    public FrozenPlayer snapshot() {
        FrozenPlayer cached = snap;
        if (cached == null) {
            cached = new FrozenPlayer(
                    serverIndex,
                    username,
                    x,
                    z,
                    sprite,
                    appearance,
                    currentHits,
                    maxHits,
                    lastDamage,
                    lastDamageTick,
                    lastChat,
                    lastChatTick,
                    actionBubbleItemId,
                    actionBubbleTick,
                    retreatTick,
                    stepDir,
                    trail());
            snap = cached;
        }
        return cached;
    }

    @Override
    public int serverIndex() {
        return serverIndex;
    }

    @Override
    public @Nullable String username() {
        return username;
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
    public int combatLevel() {
        return appearance.combatLevel();
    }

    @Override
    public boolean skulled() {
        return appearance.skulled();
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
    public @Nullable String appearanceToken() {
        return appearance.token();
    }

    @Override
    public Appearance appearance() {
        return appearance;
    }

    @Override
    public int actionBubbleItemId() {
        return actionBubbleItemId;
    }

    @Override
    public long actionBubbleTick() {
        return actionBubbleTick;
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
        return lastChat;
    }

    @Override
    public long lastChatTick() {
        return lastChatTick;
    }

    public void setAppearance(Appearance look) {
        snap = null;
        this.appearance = look;
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

    @Override
    public int stepDir() {
        return stepDir;
    }

    public static final int TRAIL = 10;

    @Override
    public List<Point> trail() {
        return trail.isEmpty() ? List.of(point()) : List.copyOf(trail);
    }

    public void noteTrailTick() {
        Point here = Point.of(x, z);
        Point last = trail.peekLast();
        if (last != null && Point.chebyshev(last.x(), last.y(), x, z) > 1) {
            trail.clear();
        }
        trail.addLast(here);
        while (trail.size() > TRAIL) {
            trail.removeFirst();
        }
        snap = null;
    }

    public void clearTrail() {
        trail.clear();
        snap = null;
    }

    public void setStepDir(int dir) {
        if (this.stepDir != dir) {
            snap = null;
            this.stepDir = dir;
        }
    }

    public void setSprite(int sprite) {
        if (this.sprite != sprite) {
            snap = null;
            this.sprite = sprite;
        }
    }

    public void setUsername(String username) {
        snap = null;
        this.username = username;
    }

    public void setCombatLevel(int lvl) {
        snap = null;
        this.appearance = appearance.withCombatLevel(lvl);
    }

    public void noteDamage(int damage, long tick) {
        snap = null;
        this.lastDamage = damage;
        this.lastDamageTick = tick;
    }

    public void noteChat(String text, long tick) {
        snap = null;
        this.lastChat = text;
        this.lastChatTick = tick;
    }

    public void setHits(int current, int max) {
        if (currentHits != current || maxHits != max) {
            snap = null;
            this.currentHits = current;
            this.maxHits = max;
        }
    }

    public void noteRetreat(long tick) {
        snap = null;
        this.retreatTick = tick;
    }

    public void noteActionBubble(int itemId, long tick) {
        snap = null;
        this.actionBubbleItemId = itemId;
        this.actionBubbleTick = tick;
    }

    @Override
    public String toString() {
        return "Player{idx=" + serverIndex + (username != null ? " " + username : "") + " @(" + x + "," + z + ") spr="
                + sprite + "}";
    }
}
