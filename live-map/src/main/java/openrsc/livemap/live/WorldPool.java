package openrsc.livemap.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import openrsc.livemap.dto.BotLiveDto;
import openrsc.livemap.dto.MapEntityDto;

/**
 * Every bot's view of the world merged into one pool per entity class ({@code npcs}, {@code players}, {@code objects},
 * {@code walls}, {@code ground}), keyed as {@link MapEntityDto#serverIndex} is, and the delta between two pools. The
 * online bots themselves are left out of {@code players}: a viewer has them from the bot stream. An entity seen by
 * several bots keeps the view of the bot that reported it last time, so two bots a tick apart do not take turns; a
 * bot that sees it beats one that sees it gone ({@code id} -1).
 *
 * <p>A row is an entity's columns in this order: key, id, x, z, name, dir, inCombat, hp, maxHp, combatLvl,
 * appearance, skulled, dmg, dmgTick, msg, msgTick, bubble, bubbleTick. A flag is 1 or null, and trailing nulls are
 * cut. Thread-safe.
 */
public final class WorldPool {

    private static final String[] CLASSES = {"npcs", "players", "objects", "walls", "ground"};

    private final Map<String, Map<Integer, MapEntityDto>> pools = new LinkedHashMap<>();
    private final Map<String, Map<Integer, String>> observers = new HashMap<>();

    public WorldPool() {
        for (String cls : CLASSES) {
            pools.put(cls, new LinkedHashMap<>());
            observers.put(cls, new HashMap<>());
        }
    }

    /**
     * Takes in the bots' views and returns what changed since the last update, by class: {@code u}, rows for
     * entities new to the pool; {@code p}, {@code [key, column, value, ...]} for the columns of a row that changed
     * (null when cleared); {@code r}, keys that left. Null when nothing changed.
     */
    public synchronized Map<String, Object> update(List<BotLiveDto> bots) {
        Set<Integer> ownIndices = new HashSet<>();
        Set<String> ownNames = new HashSet<>();
        for (BotLiveDto b : bots) {
            if (b.serverIndex() != null && "ONLINE".equals(b.status())) {
                ownIndices.add(b.serverIndex());
                ownNames.add(b.username().toLowerCase(Locale.ROOT));
            }
        }
        Map<String, Object> delta = new LinkedHashMap<>();
        for (String cls : CLASSES) {
            boolean players = cls.equals("players");
            Map<Integer, String> wasSeenBy = observers.get(cls);
            Map<Integer, String> seenBy = new HashMap<>();
            Map<Integer, MapEntityDto> next = new LinkedHashMap<>();
            for (BotLiveDto b : bots) {
                List<MapEntityDto> list = listOf(b, cls);
                if (list == null) {
                    continue;
                }
                for (MapEntityDto e : list) {
                    if (players
                            && (ownIndices.contains(e.serverIndex())
                                    || (e.name() != null
                                            && ownNames.contains(e.name().toLowerCase(Locale.ROOT))))) {
                        continue;
                    }
                    MapEntityDto held = next.get(e.serverIndex());
                    if (held == null
                            || (held.id() < 0 && e.id() >= 0)
                            || ((held.id() < 0) == (e.id() < 0)
                                    && b.username().equals(wasSeenBy.get(e.serverIndex())))) {
                        next.put(e.serverIndex(), e);
                        seenBy.put(e.serverIndex(), b.username());
                    }
                }
            }
            observers.put(cls, seenBy);
            Map<Integer, MapEntityDto> prev = pools.put(cls, next);
            List<List<Object>> u = new ArrayList<>();
            List<List<Object>> p = new ArrayList<>();
            for (Map.Entry<Integer, MapEntityDto> en : next.entrySet()) {
                MapEntityDto was = prev.get(en.getKey());
                if (en.getValue().equals(was)) {
                    continue;
                }
                List<Object> row = row(en.getValue());
                if (was == null || was.id() != en.getValue().id()) {
                    u.add(row);
                } else {
                    p.add(patch(row(was), row));
                }
            }
            List<Integer> r = new ArrayList<>();
            for (Integer k : prev.keySet()) {
                if (!next.containsKey(k)) {
                    r.add(k);
                }
            }
            if (!u.isEmpty() || !p.isEmpty() || !r.isEmpty()) {
                Map<String, Object> d = new LinkedHashMap<>();
                if (!u.isEmpty()) {
                    d.put("u", u);
                }
                if (!p.isEmpty()) {
                    d.put("p", p);
                }
                if (!r.isEmpty()) {
                    d.put("r", r);
                }
                delta.put(cls, d);
            }
        }
        return delta.isEmpty() ? null : delta;
    }

    private static List<Object> patch(List<Object> was, List<Object> now) {
        List<Object> out = new ArrayList<>();
        out.add(now.get(0));
        for (int col = 1; col < Math.max(was.size(), now.size()); col++) {
            Object a = col < was.size() ? was.get(col) : null;
            Object b = col < now.size() ? now.get(col) : null;
            if (!Objects.equals(a, b)) {
                out.add(col);
                out.add(b);
            }
        }
        return out;
    }

    /** The whole pool as one delta: {@code full} true, and every row under each class's {@code u}. */
    public synchronized Map<String, Object> seed() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("full", true);
        for (String cls : CLASSES) {
            List<List<Object>> u = new ArrayList<>();
            for (MapEntityDto e : pools.get(cls).values()) {
                u.add(row(e));
            }
            out.put(cls, Map.of("u", u));
        }
        return out;
    }

    private static List<MapEntityDto> listOf(BotLiveDto b, String cls) {
        return switch (cls) {
            case "npcs" -> b.npcs();
            case "players" -> b.players();
            case "objects" -> b.objects();
            case "walls" -> b.wallObjects();
            case "ground" -> b.groundItems();
            default -> null;
        };
    }

    private static List<Object> row(MapEntityDto e) {
        Object[] cols = {
            e.serverIndex(),
            e.id(),
            e.x(),
            e.z(),
            e.name(),
            e.dir(),
            e.inCombat() ? 1 : null,
            e.hp(),
            e.maxHp(),
            e.combatLvl(),
            e.appearance(),
            Boolean.TRUE.equals(e.skulled()) ? 1 : null,
            e.dmg(),
            e.dmgTick(),
            e.msg(),
            e.msgTick(),
            e.bubble(),
            e.bubbleTick()
        };
        int last = cols.length - 1;
        while (last >= 0 && cols[last] == null) {
            last--;
        }
        List<Object> out = new ArrayList<>(last + 1);
        for (int i = 0; i <= last; i++) {
            out.add(cols[i]);
        }
        return out;
    }
}
