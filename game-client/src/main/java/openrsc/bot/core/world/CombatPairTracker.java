package openrsc.bot.core.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.Attackable;
import openrsc.bot.api.Npc;
import openrsc.bot.api.Player;

public final class CombatPairTracker {

    private static final Object SELF = new Object();

    private record MobState(int sprite, int x, int z) {
        boolean inCombat() {
            return sprite == 8 || sprite == 9;
        }
    }

    private IdentityHashMap<Object, MobState> prev = new IdentityHashMap<>();

    private final IdentityHashMap<Object, Object> pairs = new IdentityHashMap<>();

    private Map<Object, Object> pairsSnapshot = Map.of();

    public void onTickBoundary(
            List<? extends Npc> npcs, List<? extends Player> players, int selfX, int selfZ, int selfSprite) {
        IdentityHashMap<Object, MobState> cur = new IdentityHashMap<>();
        for (Npc n : npcs) {
            cur.put(n, new MobState(n.sprite(), n.x(), n.z()));
        }
        for (Player p : players) {
            cur.put(p, new MobState(p.sprite(), p.x(), p.z()));
        }
        cur.put(SELF, new MobState(selfSprite, selfX, selfZ));

        dropInvalidPairs(cur);
        pairUnpaired(cur);

        prev = cur;
        pairsSnapshot = Map.copyOf(pairs);
    }

    private void dropInvalidPairs(Map<Object, MobState> cur) {
        List<Object> dead = new ArrayList<>();
        for (Map.Entry<Object, Object> e : pairs.entrySet()) {
            MobState a = cur.get(e.getKey());
            MobState b = cur.get(e.getValue());
            if (a == null || b == null || !a.inCombat() || !b.inCombat() || a.x() != b.x() || a.z() != b.z()) {
                dead.add(e.getKey());
            }
        }
        for (Object k : dead) {
            Object other = pairs.remove(k);
            if (other != null) {
                pairs.remove(other);
            }
        }
    }

    private void pairUnpaired(Map<Object, MobState> cur) {
        Map<Long, List<Object>> byTile = new HashMap<>();
        for (Map.Entry<Object, MobState> e : cur.entrySet()) {
            MobState s = e.getValue();
            if (s.inCombat() && !pairs.containsKey(e.getKey())) {
                byTile.computeIfAbsent(((long) s.x() << 32) | (s.z() & 0xffffffffL), k -> new ArrayList<>())
                        .add(e.getKey());
            }
        }

        for (List<Object> group : byTile.values()) {
            List<Object> eights = new ArrayList<>();
            List<Object> nines = new ArrayList<>();
            for (Object m : group) {
                (cur.get(m).sprite() == 8 ? eights : nines).add(m);
            }
            if (eights.size() == 1 && nines.size() == 1) {
                pair(eights.get(0), nines.get(0));
                continue;
            }
            Object newEight = soleNewlyInCombat(eights);
            Object newNine = soleNewlyInCombat(nines);
            if (newEight != null && newNine != null) {
                pair(newEight, newNine);
            }
        }
    }

    private Object soleNewlyInCombat(List<Object> mobs) {
        Object sole = null;
        for (Object m : mobs) {
            MobState was = prev.get(m);
            if (was == null || !was.inCombat()) {
                if (sole != null) {
                    return null;
                }
                sole = m;
            }
        }
        return sole;
    }

    private void pair(Object a, Object b) {
        pairs.put(a, b);
        pairs.put(b, a);
    }

    public Attackable opponentOfSelf() {
        return asAttackable(pairsSnapshot.get(SELF));
    }

    public Attackable opponentOf(Attackable mob) {
        return asAttackable(pairsSnapshot.get(mob));
    }

    private static Attackable asAttackable(Object mob) {
        return mob instanceof Attackable a ? a : null;
    }
}
