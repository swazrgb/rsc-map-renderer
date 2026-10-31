package openrsc.nav;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which NPCs a route follower keeps its distance from ({@link openrsc.nav.walk.Yield}). Only NPCs
 * aggressive to the account are ever berthed; this narrows that set, so an NPC the account means
 * to reach can be.
 *
 * <pre>{@code
 * BerthPolicy.all()                       // every aggressive NPC (the default)
 * BerthPolicy.all().exceptId(quarryId)    // all but NPCs of the quarry's kind
 * BerthPolicy.all().exceptIdx(index)      // all but this one NPC
 * }</pre>
 *
 * <p>An NPC is named when its definition id is in {@code ids} or its server index is in
 * {@code indexes}; naming one inverts {@code berthByDefault} for it.
 */
public record BerthPolicy(boolean berthByDefault, Set<Integer> ids, Set<Integer> indexes) {

    /** Null sets are empty. */
    public BerthPolicy {
        ids = ids == null ? Set.of() : Set.copyOf(ids);
        indexes = indexes == null ? Set.of() : Set.copyOf(indexes);
    }

    private static final BerthPolicy ALL = new BerthPolicy(true, Set.of(), Set.of());
    private static final BerthPolicy NONE = new BerthPolicy(false, Set.of(), Set.of());

    public static BerthPolicy all() {
        return ALL;
    }

    public static BerthPolicy none() {
        return NONE;
    }

    /** With NPCs of definition {@code npcId} named. */
    public BerthPolicy exceptId(int npcId) {
        return named(npcId, true);
    }

    /** With the NPC at {@code npcServerIndex} named. */
    public BerthPolicy exceptIdx(int npcServerIndex) {
        return named(npcServerIndex, false);
    }

    private BerthPolicy named(int value, boolean byId) {
        Set<Integer> nextIds = ids;
        Set<Integer> nextIdx = indexes;
        if (byId) {
            nextIds = plus(ids, value);
        } else {
            nextIdx = plus(indexes, value);
        }
        return new BerthPolicy(berthByDefault, nextIds, nextIdx);
    }

    private static Set<Integer> plus(Set<Integer> base, int value) {
        if (base.contains(value)) {
            return base;
        }
        Set<Integer> out = new LinkedHashSet<>(base);
        out.add(value);
        return Set.copyOf(out);
    }

    public boolean berths(int npcId, int npcServerIndex) {
        boolean named = ids.contains(npcId) || indexes.contains(npcServerIndex);
        return berthByDefault != named;
    }

    /** False only when no NPC is berthed at all: {@link #none()} with nothing named. */
    public boolean berthsAnything() {
        return berthByDefault || !ids.isEmpty() || !indexes.isEmpty();
    }
}
