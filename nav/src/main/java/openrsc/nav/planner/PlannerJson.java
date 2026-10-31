package openrsc.nav.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.nav.BerthPolicy;
import openrsc.nav.NavPolicy;

/**
 * The planner's JSON wire format without a JSON library, for builds that have none. Reads a
 * {@link PathfinderPlans.Request} and writes each response as Jackson writes the records: component
 * names as keys, map keys as strings, and a {@code null} field left out.
 */
public final class PlannerJson {

    private PlannerJson() {}

    /**
     * The request {@code json} holds. A missing flag is false and a missing danger, berth or cost
     * override takes the {@link NavPolicy} default; numbers are truncated to {@code int}.
     *
     * @throws IllegalArgumentException at an unexpected token, or where a field that takes a number,
     *     object or array holds something else
     */
    public static PathfinderPlans.Request request(String json) {
        Map<String, Object> o = object(new Reader(json).value());
        return new PathfinderPlans.Request(
                tile(o.get("from")),
                tile(o.get("to")),
                (String) o.get("as"),
                account(o.get("account")),
                loadout(o.get("loadout")),
                policy(o.get("policy")));
    }

    /**
     * The {@link BobMapCampGenerator.Request} {@code json} holds: {@code targets},
     * {@code items} as {@code {id, min, target, wear}}, {@code food},
     * {@code loot} and {@code bury} as item ids, {@code eat}, {@code sleep}, {@code style} and
     * {@code title}.
     *
     * @throws IllegalArgumentException at an unexpected token, or a field of the wrong type
     */
    public static BobMapCampGenerator.Request campRequest(String json) {
        Map<String, Object> o = object(new Reader(json).value());
        List<BobMapCampGenerator.Item> items = new ArrayList<>();
        if (o.get("items") != null) {
            for (Object v : list(o.get("items"))) {
                Map<String, Object> m = object(v);
                int min = m.get("min") == null ? 0 : integer(m.get("min"));
                items.add(new BobMapCampGenerator.Item(
                        integer(m.get("id")),
                        min,
                        m.get("target") == null ? min : integer(m.get("target")),
                        flag(m.get("wear"))));
            }
        }
        return new BobMapCampGenerator.Request(
                intList(o.get("targets")),
                items,
                intList(o.get("food")),
                boxed(o.get("eat")),
                boxed(o.get("sleep")),
                boxed(o.get("style")),
                intList(o.get("loot")),
                intList(o.get("bury")),
                (String) o.get("title"));
    }

    private static List<Integer> intList(Object v) {
        List<Integer> out = new ArrayList<>();
        if (v != null) {
            for (Object x : list(v)) {
                out.add(integer(x));
            }
        }
        return out;
    }

    private static PathfinderPlans.Tile tile(Object v) {
        if (v == null) {
            return null;
        }
        Map<String, Object> o = object(v);
        return new PathfinderPlans.Tile(integer(o.get("x")), integer(o.get("z")));
    }

    private static PathfinderPlans.AccountInput account(Object v) {
        if (v == null) {
            return null;
        }
        Map<String, Object> o = object(v);
        Map<String, Integer> skills = new LinkedHashMap<>();
        if (o.get("skills") != null) {
            object(o.get("skills")).forEach((k, x) -> skills.put(k, integer(x)));
        }
        return new PathfinderPlans.AccountInput(
                skills,
                intMap(o.get("quests")),
                intMap(o.get("inventory")),
                intMap(o.get("bank")),
                cache(o.get("cache")));
    }

    private static CacheValues cache(Object v) {
        if (v == null) {
            return null;
        }
        Map<String, Object> o = object(v);
        Map<String, Boolean> bools = new LinkedHashMap<>();
        if (o.get("bools") != null) {
            object(o.get("bools")).forEach((k, x) -> bools.put(k, (Boolean) x));
        }
        Map<String, Integer> ints = new LinkedHashMap<>();
        if (o.get("ints") != null) {
            object(o.get("ints")).forEach((k, x) -> ints.put(k, integer(x)));
        }
        return new CacheValues(bools, ints);
    }

    private static PathfinderPlans.LoadoutInput loadout(Object v) {
        if (v == null) {
            return null;
        }
        Map<String, Object> o = object(v);
        List<PathfinderPlans.Want> wants = null;
        if (o.get("wants") != null) {
            wants = new ArrayList<>();
            for (Object w : list(o.get("wants"))) {
                Map<String, Object> m = object(w);
                List<Integer> anyOf = null;
                if (m.get("anyOf") != null) {
                    anyOf = new ArrayList<>();
                    for (Object id : list(m.get("anyOf"))) {
                        anyOf.add(integer(id));
                    }
                }
                wants.add(new PathfinderPlans.Want(
                        boxed(m.get("item")),
                        anyOf,
                        boxed(m.get("min")),
                        boxed(m.get("target")),
                        boxed(m.get("max")),
                        (Boolean) m.get("worn")));
            }
        }
        return new PathfinderPlans.LoadoutInput(
                (Boolean) o.get("keepEverythingElse"), (Boolean) o.get("bankWhenFull"), wants);
    }

    private static NavPolicy policy(Object v) {
        if (v == null) {
            return null;
        }
        Map<String, Object> o = object(v);
        NavPolicy.DangerAvoidance danger = null;
        for (NavPolicy.DangerAvoidance d : NavPolicy.DangerAvoidance.values()) {
            if (d.name().equals(o.get("danger"))) {
                danger = d;
            }
        }
        Map<String, Integer> overrides = null;
        if (o.get("costOverrides") != null) {
            overrides = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : object(o.get("costOverrides")).entrySet()) {
                overrides.put(e.getKey(), integer(e.getValue()));
            }
        }
        BerthPolicy berth = null;
        if (o.get("berth") != null) {
            Map<String, Object> b = object(o.get("berth"));
            berth = new BerthPolicy(flag(b.get("berthByDefault")), intSet(b.get("ids")), intSet(b.get("indexes")));
        }
        return new NavPolicy(
                danger,
                flag(o.get("teleportSpells")),
                flag(o.get("teleportJewellery")),
                overrides,
                flag(o.get("assumeAllEligible")),
                flag(o.get("avoidSkulled")),
                berth);
    }

    private static boolean flag(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private static Set<Integer> intSet(Object v) {
        Set<Integer> out = new LinkedHashSet<>();
        if (v != null) {
            for (Object x : (List<?>) v) {
                out.add(integer(x));
            }
        }
        return out;
    }

    private static Map<Integer, Integer> intMap(Object v) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        if (v != null) {
            object(v).forEach((k, x) -> out.put(Integer.parseInt(k), integer(x)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object v) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("expected an object, got " + v);
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("expected an array, got " + v);
        }
        return (List<Object>) v;
    }

    private static int integer(Object v) {
        if (!(v instanceof Number n)) {
            throw new IllegalArgumentException("expected a number, got " + v);
        }
        return (int) n.longValue();
    }

    private static Integer boxed(Object v) {
        return v == null ? null : integer(v);
    }

    private static final class Reader {
        private final String s;
        private int at;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            if (at >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = s.charAt(at);
            switch (c) {
                case '{' -> {
                    at++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    skip();
                    if (peek('}')) {
                        at++;
                        return m;
                    }
                    while (true) {
                        skip();
                        String k = string();
                        skip();
                        expect(':');
                        m.put(k, value());
                        skip();
                        if (peek(',')) {
                            at++;
                            continue;
                        }
                        expect('}');
                        return m;
                    }
                }
                case '[' -> {
                    at++;
                    List<Object> l = new ArrayList<>();
                    skip();
                    if (peek(']')) {
                        at++;
                        return l;
                    }
                    while (true) {
                        l.add(value());
                        skip();
                        if (peek(',')) {
                            at++;
                            continue;
                        }
                        expect(']');
                        return l;
                    }
                }
                case '"' -> {
                    return string();
                }
                case 't' -> {
                    word("true");
                    return true;
                }
                case 'f' -> {
                    word("false");
                    return false;
                }
                case 'n' -> {
                    word("null");
                    return null;
                }
                default -> {
                    return number();
                }
            }
        }

        private Object number() {
            int start = at;
            boolean fraction = false;
            while (at < s.length()) {
                char c = s.charAt(at);
                if (c == '.' || c == 'e' || c == 'E') {
                    fraction = true;
                } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    break;
                }
                at++;
            }
            String n = s.substring(start, at);
            if (n.isEmpty()) {
                throw new IllegalArgumentException("unexpected '" + s.charAt(start) + "' at " + start);
            }
            return fraction ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(at++);
                if (c == '"') {
                    return b.toString();
                }
                if (c != '\\') {
                    b.append(c);
                    continue;
                }
                char e = s.charAt(at++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> {
                        b.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> b.append(e);
                }
            }
        }

        private void word(String w) {
            if (!s.startsWith(w, at)) {
                throw new IllegalArgumentException("unexpected token at " + at);
            }
            at += w.length();
        }

        private boolean peek(char c) {
            return at < s.length() && s.charAt(at) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("expected '" + c + "' at " + at);
            }
            at++;
        }

        private void skip() {
            while (at < s.length() && Character.isWhitespace(s.charAt(at))) {
                at++;
            }
        }
    }

    public static String write(PathfinderPlans.Response r) {
        Writer w = new Writer();
        w.open();
        w.key("found").bool(r.found());
        w.key("ticks").num(r.ticks());
        w.key("steps");
        if (r.steps() == null) {
            w.nul();
        } else {
            w.arrayOpen();
            for (PathfinderPlans.Step s : r.steps()) {
                w.next();
                w.open();
                w.key("type").str(s.type());
                w.key("x").num(s.x());
                w.key("z").num(s.z());
                w.key("name").str(s.name());
                w.key("cost").num(s.cost());
                w.key("req").str(s.req());
                w.key("withdraw").longMap(s.withdraw());
                w.key("deposit").longMap(s.deposit());
                w.key("wears").intList(s.wears());
                w.key("because").strList(s.because());
                w.key("pack");
                if (s.pack() == null) {
                    w.nul();
                } else {
                    w.arrayOpen();
                    for (PathfinderPlans.PackItem p : s.pack()) {
                        w.next();
                        w.open();
                        w.key("slot").num(p.slot());
                        w.key("id").num(p.id());
                        w.key("amount").num(p.amount());
                        w.key("equipped").bool(p.equipped());
                        w.close();
                    }
                    w.arrayClose();
                }
                w.close();
            }
            w.arrayClose();
        }
        w.key("plan");
        if (r.plan() == null) {
            w.nul();
        } else {
            w.arrayOpen();
            for (PathfinderPlans.PlanStep p : r.plan()) {
                w.next();
                w.open();
                w.key("type").str(p.type());
                w.key("x").num(p.x());
                w.key("z").num(p.z());
                w.key("x2").num(p.x2());
                w.key("z2").num(p.z2());
                w.key("name").str(p.name());
                w.close();
            }
            w.arrayClose();
        }
        w.key("account");
        PathfinderPlans.Derived d = r.account();
        if (d == null) {
            w.nul();
        } else {
            w.open();
            w.key("combatLevel").num(d.combatLevel());
            w.key("questPoints").num(d.questPoints());
            w.key("wildernessLevel").num(d.wildernessLevel());
            w.key("usableTransports").num(d.usableTransports());
            w.key("totalTransports").num(d.totalTransports());
            w.close();
        }
        w.key("diagnosis").str(r.diagnosis());
        w.key("why");
        PathfinderPlans.Why why = r.why();
        if (why == null) {
            w.nul();
        } else {
            w.open();
            w.key("kind").str(why.kind());
            w.key("summary").str(why.summary());
            w.key("complete").bool(why.complete());
            w.key("fixes");
            w.arrayOpen();
            for (PathfinderPlans.Fix f : why.fixes()) {
                w.next();
                w.open();
                w.key("kind").str(f.kind());
                w.key("summary").str(f.summary());
                w.key("hops").strList(f.hops());
                w.key("blockers");
                w.arrayOpen();
                for (PathfinderPlans.Blocker b : f.blockers()) {
                    w.next();
                    w.open();
                    w.key("what").str(b.what());
                    w.key("why").str(b.why());
                    w.key("fix").str(b.fix());
                    w.key("items").intList(b.items());
                    w.key("quests").intList(b.quests());
                    w.key("skills").strList(b.skills());
                    w.key("cache").strList(b.cache());
                    w.key("transport").str(b.transport());
                    w.close();
                }
                w.arrayClose();
                w.close();
            }
            w.arrayClose();
            w.close();
        }
        w.key("timings");
        PathfinderPlans.Timings t = r.timings();
        if (t == null) {
            w.nul();
        } else {
            w.open();
            w.key("totalMs").dbl(t.totalMs());
            w.key("estimateMs").dbl(t.estimateMs());
            w.key("botPlanMs").dbl(t.botPlanMs());
            w.key("botWeightSetsBuilt").num(t.botWeightSetsBuilt());
            w.key("botLegPlanesBuilt").num(t.botLegPlanesBuilt());
            w.key("simulatedTicks").num(t.simulatedTicks());
            w.key("simulatedPlans").num(t.simulatedPlans());
            w.key("simulatedWalks").num(t.simulatedWalks());
            w.key("simulationPlanMs").dbl(t.simulationPlanMs());
            w.key("simulationWalkMs").dbl(t.simulationWalkMs());
            w.key("derivedMs").dbl(t.derivedMs());
            w.key("diagnosisMs").dbl(t.diagnosisMs());
            w.close();
        }
        w.close();
        return w.toString();
    }

    public static String write(PathfinderInputs.Response r) {
        Writer w = new Writer();
        w.open();
        PathfinderInputs.Totals t = r.totals();
        w.key("totals").open();
        w.key("transports").num(t.transports());
        w.key("gated").num(t.gated());
        w.key("never").num(t.never());
        w.key("gates").num(t.gates());
        w.key("facts").num(t.facts());
        w.close();
        w.key("skills").arrayOpen();
        for (PathfinderInputs.SkillInput s : r.skills()) {
            w.next();
            w.open();
            w.key("skill").str(s.skill());
            w.key("combat").bool(s.combat());
            w.key("breakpoints");
            thresholds(w, s.breakpoints());
            w.close();
        }
        w.arrayClose();
        w.key("combatSkills").strList(r.combatSkills());
        w.key("itemGroups").arrayOpen();
        for (PathfinderInputs.ItemGroup g : r.itemGroups()) {
            w.next();
            w.open();
            w.key("label").str(g.label());
            w.key("items").arrayOpen();
            for (PathfinderInputs.ItemRef i : g.items()) {
                w.next();
                w.open();
                w.key("id").num(i.id());
                w.key("name").str(i.name());
                w.close();
            }
            w.arrayClose();
            w.key("transports").num(g.transports());
            w.close();
        }
        w.arrayClose();
        w.key("items").arrayOpen();
        for (PathfinderInputs.ItemInput i : r.items()) {
            w.next();
            w.open();
            w.key("id").num(i.id());
            w.key("name").str(i.name());
            w.key("transports").num(i.transports());
            w.key("carry");
            thresholds(w, i.carry());
            w.key("mustNotCarry");
            thresholds(w, i.mustNotCarry());
            w.key("worn").bool(i.worn());
            w.close();
        }
        w.arrayClose();
        w.key("quests").arrayOpen();
        for (PathfinderInputs.QuestInput q : r.quests()) {
            w.next();
            w.open();
            w.key("id").num(q.id());
            w.key("name").str(q.name());
            w.key("transports").num(q.transports());
            w.key("atLeast");
            thresholds(w, q.atLeast());
            w.key("exact");
            thresholds(w, q.exact());
            w.key("done");
            threshold(w, q.done());
            w.close();
        }
        w.arrayClose();
        w.key("cache").arrayOpen();
        for (PathfinderInputs.CacheInput c : r.cache()) {
            w.next();
            w.open();
            w.key("key").str(c.key());
            w.key("kind").str(c.kind());
            w.key("transports").num(c.transports());
            w.key("targets");
            thresholds(w, c.targets());
            w.close();
        }
        w.arrayClose();
        w.key("policy").arrayOpen();
        for (PathfinderInputs.PolicyInput p : r.policy()) {
            w.next();
            w.open();
            w.key("key").str(p.key());
            w.key("label").str(p.label());
            w.key("defaultValue").bool(p.defaultValue());
            w.close();
        }
        w.arrayClose();
        w.key("derived").strList(r.derived());
        w.close();
        return w.toString();
    }

    public static String write(BobMapCampGenerator.Response r) {
        Writer w = new Writer();
        w.open();
        w.key("recipe").str(r.recipe());
        w.key("camp");
        tileOrNull(w, r.camp());
        w.key("campBox");
        if (r.campBox() == null) {
            w.nul();
        } else {
            w.open();
            w.key("minX").num(r.campBox().minX());
            w.key("minZ").num(r.campBox().minZ());
            w.key("maxX").num(r.campBox().maxX());
            w.key("maxZ").num(r.campBox().maxZ());
            w.close();
        }
        w.key("bank").str(r.bank());
        w.key("bankTile");
        tileOrNull(w, r.bankTile());
        routeTiles(w, "toBank", r.toBank());
        routeTiles(w, "toCamp", r.toCamp());
        w.key("stepHits").num(r.stepHits());
        w.key("fatigue").bool(r.fatigue());
        w.key("warnings").strList(r.warnings());
        w.key("error").str(r.error());
        w.close();
        return w.toString();
    }

    private static void tileOrNull(Writer w, PathfinderPlans.Tile t) {
        if (t == null) {
            w.nul();
            return;
        }
        w.open();
        w.key("x").num(t.x());
        w.key("z").num(t.z());
        w.close();
    }

    private static void routeTiles(Writer w, String key, List<BobMapCampGenerator.RouteTile> tiles) {
        w.key(key).arrayOpen();
        for (BobMapCampGenerator.RouteTile t : tiles) {
            w.next();
            w.open();
            w.key("x").num(t.x());
            w.key("z").num(t.z());
            w.key("hop").bool(t.hop());
            w.close();
        }
        w.arrayClose();
    }

    public static String write(PathfinderReach.Response r) {
        Writer w = new Writer();
        w.open();
        w.key("from");
        if (r.from() == null) {
            w.nul();
        } else {
            w.open();
            w.key("x").num(r.from().x());
            w.key("z").num(r.from().z());
            w.close();
        }
        w.key("reachable").arrayOpen();
        for (int id : r.reachable()) {
            w.next();
            w.num(id);
        }
        w.arrayClose();
        w.key("components").num(r.components());
        w.key("ms").dbl(r.ms());
        w.close();
        return w.toString();
    }

    public static String write(PathfinderAccount.Response r) {
        Writer w = new Writer();
        w.open();
        w.key("facts").arrayOpen();
        for (PathfinderAccount.FactRow f : r.facts()) {
            w.next();
            w.open();
            w.key("fact").str(f.fact());
            w.key("holds").bool(f.holds());
            w.key("transports").num(f.transports());
            w.close();
        }
        w.arrayClose();
        w.key("rules").arrayOpen();
        for (PathfinderAccount.RuleRow f : r.rules()) {
            w.next();
            w.open();
            w.key("rule").str(f.rule());
            w.key("passes").bool(f.passes());
            w.key("transports").num(f.transports());
            w.close();
        }
        w.arrayClose();
        w.key("usable");
        openings(w, r.usable());
        w.key("total").num(r.total());
        w.key("cacheAssumed").strList(r.cacheAssumed());
        w.close();
        return w.toString();
    }

    /** A JSON array of the id of every item that can be worn. */
    public static String wearableItems(ItemDefs defs) {
        Writer w = new Writer();
        w.arrayOpen();
        for (int id = 0; id < defs.size(); id++) {
            ItemDef def = defs.get(id);
            if (def != null && def.wearable()) {
                w.next();
                w.num(id);
            }
        }
        w.arrayClose();
        return w.toString();
    }

    /** A JSON array of the id of every item that heals when eaten, ascending. */
    public static String edibleItems(Map<Integer, Integer> edibleHeals) {
        Writer w = new Writer();
        w.arrayOpen();
        for (int id : edibleHeals.keySet().stream()
                .filter(id -> edibleHeals.get(id) > 0)
                .sorted()
                .toList()) {
            w.next();
            w.num(id);
        }
        w.arrayClose();
        return w.toString();
    }

    /**
     * A JSON object of the name of every item the world has, keyed by id: each defined item up to
     * {@code maxItemId}, or every one when it is {@link ServerRules#UNRESTRICTED}.
     */
    public static String itemNames(ItemDefs defs, int maxItemId) {
        Writer w = new Writer();
        w.open();
        int last = maxItemId == ServerRules.UNRESTRICTED ? defs.size() - 1 : Math.min(maxItemId, defs.size() - 1);
        for (int id = 0; id <= last; id++) {
            if (defs.get(id) != null) {
                w.key(String.valueOf(id)).str(defs.nameOf(id));
            }
        }
        w.close();
        return w.toString();
    }

    private static void thresholds(Writer w, List<PathfinderInputs.Threshold> list) {
        if (list == null) {
            w.nul();
            return;
        }
        w.arrayOpen();
        for (PathfinderInputs.Threshold t : list) {
            w.next();
            threshold(w, t);
        }
        w.arrayClose();
    }

    private static void threshold(Writer w, PathfinderInputs.Threshold t) {
        if (t == null) {
            w.nul();
            return;
        }
        w.open();
        w.key("at").num(t.at());
        w.key("transports").num(t.transports());
        w.key("opens");
        openings(w, t.opens());
        w.close();
    }

    private static void openings(Writer w, List<PathfinderInputs.Opening> list) {
        if (list == null) {
            w.nul();
            return;
        }
        w.arrayOpen();
        for (PathfinderInputs.Opening o : list) {
            w.next();
            w.open();
            w.key("name").str(o.name());
            w.key("x").num(o.x());
            w.key("z").num(o.z());
            w.close();
        }
        w.arrayClose();
    }

    private static final class Writer {
        private final StringBuilder b = new StringBuilder(1 << 12);
        private final ArrayList<Boolean> filled = new ArrayList<>();
        private String pending;

        Writer open() {
            flush();
            b.append('{');
            filled.add(false);
            return this;
        }

        void close() {
            b.append('}');
            filled.remove(filled.size() - 1);
        }

        Writer arrayOpen() {
            flush();
            b.append('[');
            filled.add(false);
            return this;
        }

        void arrayClose() {
            b.append(']');
            filled.remove(filled.size() - 1);
        }

        void next() {
            int top = filled.size() - 1;
            if (filled.get(top)) {
                b.append(',');
            }
            filled.set(top, true);
        }

        Writer key(String k) {
            pending = k;
            return this;
        }

        private void flush() {
            if (pending != null) {
                next();
                quote(pending);
                b.append(':');
                pending = null;
            }
        }

        // Under a key, a null drops the field, as Jackson's NON_NULL does; an array element is written.
        void nul() {
            if (pending != null) {
                pending = null;
            } else {
                b.append("null");
            }
        }

        void bool(boolean v) {
            flush();
            b.append(v);
        }

        void num(long v) {
            flush();
            b.append(v);
        }

        void num(Integer v) {
            if (v == null) {
                nul();
            } else {
                num(v.intValue());
            }
        }

        void dbl(double v) {
            flush();
            if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
                b.append((long) v).append(".0");
            } else {
                b.append(v);
            }
        }

        void str(String v) {
            if (v == null) {
                nul();
            } else {
                flush();
                quote(v);
            }
        }

        void strList(List<String> list) {
            if (list == null) {
                nul();
                return;
            }
            arrayOpen();
            for (String s : list) {
                next();
                str(s);
            }
            arrayClose();
        }

        void intList(List<Integer> list) {
            if (list == null) {
                nul();
                return;
            }
            arrayOpen();
            for (Integer i : list) {
                next();
                num(i);
            }
            arrayClose();
        }

        void longMap(Map<Integer, Long> map) {
            if (map == null) {
                nul();
                return;
            }
            open();
            for (Map.Entry<Integer, Long> e : map.entrySet()) {
                key(String.valueOf(e.getKey()));
                flush();
                if (e.getValue() == null) {
                    b.append("null");
                } else {
                    b.append(e.getValue().longValue());
                }
            }
            close();
        }

        private void quote(String s) {
            b.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            String hex = Integer.toHexString(c);
                            b.append("\\u");
                            for (int p = hex.length(); p < 4; p++) {
                                b.append('0');
                            }
                            b.append(hex);
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            b.append('"');
        }

        @Override
        public String toString() {
            return b.toString();
        }
    }
}
