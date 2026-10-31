package openrsc.nav.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.Location;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.TravelTimes;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.probe.NavProbe;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;
import openrsc.nav.transports.WebTransport;

/**
 * Compiles a camp recipe: the text a client's recipe camper reads to camp NPC spawns, bank at the
 * nearest bank and walk between the two. The format is the viewer's {@code scriptgen/RECIPE.md}.
 *
 * <p>Routes take only transports a client without a route planner can take: one click on a scenery
 * or wall object, with no dialogue and nothing spent. An item such a click asks to be carried or worn
 * counts only when the loadout's {@code min} keeps it in the pack (worn, for a worn one), since the
 * camper banks before it runs out. Doors and gates any player opens are crossed with a {@code door}
 * or {@code gate} step, since the collision map reads them open. The bank is the nearest one with
 * bankers.
 */
public final class BobMapCampGenerator {

    /** How many tiles of a route a {@code walk} step covers at most. */
    static final int WAYPOINT_TILES = 12;

    private static final int EXPLAINED_BANKS = 4;

    private static final int RASHILIYA_CORPSE = 977;

    // An NPC chases a player up to 4 tiles outside its wander box, and a fight drags it further:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L387
    private static final int IN_THE_WAY_TILES = 8;

    /** @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Inventory.java#L30">Inventory.MAX_SIZE</a> */
    public static final int INVENTORY_SLOTS = 30;

    // A maxed account: what a level or a quest gates is the player's to meet.
    private static final PathfinderPlans.AccountInput MAXED = maxed();

    private BobMapCampGenerator() {}

    /** An item to carry: below {@code min} the camper banks, and withdraws up to {@code target}. */
    public record Item(int id, int min, int target, boolean wear) {}

    /**
     * What to compile. {@code targets} are distinct spawn server indexes, all on one floor; the camp is
     * derived from them: it ends on the target spawn nearest the others. The routes are planned for a
     * maxed account: every skill at 99 and every quest complete. {@code eat} is in hits, {@code style}
     * 0 to 3; either may be {@code null} to leave it out. {@code sleep} is the fatigue in percent, 1 to
     * 100, to sleep at, on a world with fatigue: it carries a sleeping bag for it; {@code null} for no
     * sleeping. {@code bury} are the items to pick up and bury, each one whose inventory command is Bury.
     */
    public record Request(
            List<Integer> targets,
            List<Item> items,
            List<Integer> food,
            Integer eat,
            Integer sleep,
            Integer style,
            List<Integer> loot,
            List<Integer> bury,
            String title) {}

    /** A box of tiles, inclusive. */
    public record Box(int minX, int minZ, int maxX, int maxZ) {}

    /** A tile of a route as drawn; {@code hop} at a transport's landing. */
    public record RouteTile(int x, int z, boolean hop) {}

    /**
     * The recipe and what it was compiled from: the camp, the bank by name and stand tile, and the
     * two routes tile by tile. {@code stepHits} is the most a route step can hit for, 0 for none.
     * {@code fatigue} says the world has fatigue, so {@code sleep} applies. {@code error} alone when
     * nothing compiles.
     */
    public record Response(
            String recipe,
            PathfinderPlans.Tile camp,
            Box campBox,
            String bank,
            PathfinderPlans.Tile bankTile,
            List<RouteTile> toBank,
            List<RouteTile> toCamp,
            int stepHits,
            boolean fatigue,
            List<String> warnings,
            String error) {

        static Response failed(String error) {
            return new Response(null, null, null, null, null, List.of(), List.of(), 0, false, List.of(), error);
        }
    }

    public static Response compile(Pathfinder pf, Request req) {
        ServerData env = pf.serverData();
        boolean fatigue = env.world().rules().fatigue();
        boolean sleeps = fatigue && req.sleep() != null;
        if (sleeps && (req.sleep() < 1 || req.sleep() > 100)) {
            return Response.failed("sleep is 1 to 100");
        }
        if (sleeps) {
            req = withSleepingBag(req);
        }
        List<Item> items = req.items() == null ? List.of() : req.items();
        List<ServerData.NpcSpawn> spawns = new ArrayList<>();
        for (int index : req.targets() == null ? List.<Integer>of() : req.targets()) {
            ServerData.NpcSpawn s = env.npcSpawnByServerIndex(index);
            if (s == null) {
                return Response.failed("no spawn has server index " + index);
            }
            spawns.add(s);
        }
        if (spawns.isEmpty()) {
            return Response.failed("pick at least one NPC spawn");
        }
        if (new HashSet<>(spawns).size() != spawns.size()) {
            return Response.failed("a spawn is picked twice");
        }
        int floor = floorOf(spawns.get(0).startZ());
        for (ServerData.NpcSpawn s : spawns) {
            if (floorOf(s.startZ()) != floor) {
                return Response.failed("the spawns are on different floors");
            }
        }
        int maxItemId = env.world().rules().maxItemId();
        for (Item it : items) {
            if (maxItemId != ServerRules.UNRESTRICTED && it.id() > maxItemId) {
                return Response.failed(itemName(env, it.id()) + " does not exist on "
                        + env.world().name());
            }
        }
        for (int id : req.food() == null ? List.<Integer>of() : req.food()) {
            if (env.edibleHeals().getOrDefault(id, 0) <= 0) {
                return Response.failed(itemName(env, id) + " is not food");
            }
        }
        for (int id : req.bury() == null ? List.<Integer>of() : req.bury()) {
            if (!buryable(env, id)) {
                return Response.failed(itemName(env, id) + " cannot be buried");
            }
        }
        List<String> warnings = new ArrayList<>();
        for (ServerData.NpcSpawn s : spawns) {
            NpcDef def = env.npc(s.id());
            if (def == null || !def.attackable()) {
                warnings.add(npcName(env, s.id()) + " #" + s.serverIndex() + " cannot be attacked");
            } else if (def.respawnTime() <= 0) {
                warnings.add(def.name() + " #" + s.serverIndex() + " never respawns");
            }
        }

        PathfinderPlans.Tile camp = centreOf(spawns);
        // The camp: every tile any target wanders, so the camper fights and loots wherever they go.
        Box box = new Box(
                spawns.stream().mapToInt(ServerData.NpcSpawn::minX).min().orElseThrow(),
                spawns.stream().mapToInt(ServerData.NpcSpawn::minZ).min().orElseThrow(),
                spawns.stream().mapToInt(ServerData.NpcSpawn::maxX).max().orElseThrow(),
                spawns.stream().mapToInt(ServerData.NpcSpawn::maxZ).max().orElseThrow());

        AccountFacts facts = PathfinderPlans.probeFacts(env, MAXED, carriedAtMin(req), wornAtMin(req));
        NavPolicy policy = clickOnly(pf, facts);

        List<Location> banks = Location.BANKS.stream()
                .filter(l -> l.chest() == null && !l.quickBankOnly() && l.existsIn(env))
                .toList();
        int[][] goals = new int[banks.size()][];
        for (int i = 0; i < goals.length; i++) {
            goals[i] = new int[] {banks.get(i).x(), banks.get(i).z()};
        }
        TravelTimes there = pf.travelCostBatch(camp.x(), camp.z(), goals, facts, policy);
        if (there.nearest().isEmpty()) {
            // Of the banks nearest as the crow flies, the one that needs the least to reach, to name what it needs.
            Location near = null;
            List<String> nearWhys = List.of();
            for (Location l : banks.stream()
                    .sorted(Comparator.comparingInt(l -> Math.max(
                            Math.abs(l.x() - camp.x()),
                            Math.abs(l.z() % CollisionMap.FLOOR_HEIGHT - camp.z() % CollisionMap.FLOOR_HEIGHT))))
                    .limit(EXPLAINED_BANKS)
                    .toList()) {
                List<String> whys = new ArrayList<>(blockers(pf, facts, policy, camp.x(), camp.z(), l.x(), l.z()));
                for (String why : blockers(pf, facts, policy, l.x(), l.z(), camp.x(), camp.z())) {
                    if (!whys.contains(why)) {
                        whys.add(why);
                    }
                }
                if (near == null || whys.size() < nearWhys.size()) {
                    near = l;
                    nearWhys = whys;
                }
            }
            return Response.failed("no bank with bankers is reachable from the camp by walking and clicking"
                    + (near == null || nearWhys.isEmpty()
                            ? ""
                            : "; to and from " + near.label() + ": " + String.join("; ", nearWhys)));
        }
        Location bank = banks.get(there.nearest().getAsInt());

        List<NavProbe.ProbeStep> out =
                NavProbe.planBetween(pf, camp.x(), camp.z(), bank.x(), bank.z(), facts, policy, LoadoutSpec.any());
        if (out == null || !last(out, bank.x(), bank.z())) {
            return Response.failed("no walk from the camp to " + bank.label()
                    + because(blockers(pf, facts, policy, camp.x(), camp.z(), bank.x(), bank.z())));
        }
        List<NavProbe.ProbeStep> back =
                NavProbe.planBetween(pf, bank.x(), bank.z(), camp.x(), camp.z(), facts, policy, LoadoutSpec.any());
        if (back == null || !last(back, camp.x(), camp.z())) {
            return Response.failed("no walk from " + bank.label() + " back to the camp"
                    + because(blockers(pf, facts, policy, bank.x(), bank.z(), camp.x(), camp.z())));
        }
        Map<String, Transport> byName = new HashMap<>();
        for (Transport t : pf.transports()) {
            byName.putIfAbsent(t.name(), t);
        }
        int stepHits = 0;
        for (List<NavProbe.ProbeStep> leg : List.of(out, back)) {
            for (NavProbe.ProbeStep s : leg) {
                Transport t = s.hopName() == null ? null : byName.get(s.hopName());
                if (t == null || t.hitsGate() <= 0) {
                    continue;
                }
                // The gate is the Hits that survive the step: one more than it can hit for.
                int hits = t.hitsGate() - 1;
                stepHits = Math.max(stepHits, hits);
                String w = readable(t.name()) + " can hit for " + hits;
                if (!warnings.contains(w)) {
                    warnings.add(w);
                }
            }
        }

        StringBuilder b = new StringBuilder();
        String title = req.title() == null || req.title().isBlank() ? titleOf(env, spawns, camp) : req.title();
        b.append("# ").append(title.replace('\n', ' ')).append('\n');
        b.append("# Banks at ")
                .append(bank.label())
                .append(". Generated by rsc-map-renderer; the format is in RECIPE.md.\n");
        b.append("world ")
                .append(env.world().name())
                .append(' ')
                .append(env.world().rules().gameTickMs())
                .append('\n');
        for (ServerData.NpcSpawn s : spawns) {
            NpcDef def = env.npc(s.id());
            b.append("target ")
                    .append(s.serverIndex())
                    .append(' ')
                    .append(s.id())
                    .append(' ')
                    .append(s.startX())
                    .append(' ')
                    .append(s.startZ())
                    .append(' ')
                    .append(respawnTicks(env, def))
                    .append(' ')
                    .append(s.minX())
                    .append(' ')
                    .append(s.minZ())
                    .append(' ')
                    .append(s.maxX())
                    .append(' ')
                    .append(s.maxZ());
            comment(b, def == null ? npcName(env, s.id()) : def.name() + ", level " + def.combatLevel());
        }
        b.append("camp ")
                .append(camp.x())
                .append(' ')
                .append(camp.z())
                .append(' ')
                .append(box.minX())
                .append(' ')
                .append(box.minZ())
                .append(' ')
                .append(box.maxX())
                .append(' ')
                .append(box.maxZ())
                .append('\n');
        b.append("bank ").append(bank.x()).append(' ').append(bank.z());
        comment(b, bank.label());
        if (req.style() != null) {
            b.append("style ").append(Math.max(0, Math.min(3, req.style()))).append('\n');
        }
        if (req.eat() != null) {
            b.append("eat ").append(Math.max(0, req.eat())).append('\n');
        }
        if (sleeps) {
            b.append("sleep ").append(req.sleep()).append('\n');
        }
        if (req.food() != null && !req.food().isEmpty()) {
            b.append("food");
            req.food().forEach(id -> b.append(' ').append(id));
            comment(b, names(env, req.food()));
        }
        for (Item it : items) {
            int min = Math.max(0, it.min());
            b.append("item ")
                    .append(it.id())
                    .append(' ')
                    .append(min)
                    .append(' ')
                    .append(Math.max(min, it.target()))
                    .append(it.wear() ? " wear" : "");
            comment(b, itemName(env, it.id()));
        }
        if (req.loot() != null && !req.loot().isEmpty()) {
            b.append("loot");
            req.loot().forEach(id -> b.append(' ').append(id));
            comment(b, names(env, req.loot()));
        }
        if (req.bury() != null && !req.bury().isEmpty()) {
            b.append("bury");
            req.bury().forEach(id -> b.append(' ').append(id));
            comment(b, names(env, req.bury()));
        }
        List<Integer> block = inTheWay(env, box, List.of(tiles(out), tiles(back)));
        if (!block.isEmpty()) {
            b.append("block");
            block.forEach(id -> b.append(' ').append(id));
            List<String> blockNames = new ArrayList<>(block.size());
            block.forEach(id -> blockNames.add(npcName(env, id)));
            comment(b, String.join(", ", blockNames));
        }
        b.append("route bank\n");
        int cutter = cutterOf(env, req);
        route(b, out, byName, env, cutter);
        b.append("end\nroute camp\n");
        route(b, back, byName, env, cutter);
        b.append("end\n");
        return new Response(
                b.toString(),
                camp,
                box,
                bank.label(),
                new PathfinderPlans.Tile(bank.x(), bank.z()),
                tiles(out),
                tiles(back),
                stepHits,
                fatigue,
                List.copyOf(warnings),
                null);
    }

    // Sleeping needs a sleeping bag carried: one at min 1, kept when the request names one already.
    private static Request withSleepingBag(Request req) {
        int bag = ItemId.SLEEPING_BAG.id();
        List<Item> items = new ArrayList<>();
        boolean named = false;
        for (Item it : req.items() == null ? List.<Item>of() : req.items()) {
            if (it.id() == bag) {
                named = true;
                int min = Math.max(1, it.min());
                items.add(new Item(bag, min, Math.max(min, it.target()), it.wear()));
            } else {
                items.add(it);
            }
        }
        if (!named) {
            items.add(new Item(bag, 1, 1, false));
        }
        return new Request(
                req.targets(),
                items,
                req.food(),
                req.eat(),
                req.sleep(),
                req.style(),
                req.loot(),
                req.bury(),
                req.title());
    }

    // What blocks a walk, from the planner's diagnosis: ": a needs b; c needs d", or "" when it names nothing.
    private static String because(List<String> whys) {
        return whys.isEmpty() ? "" : ": " + String.join("; ", whys);
    }

    /** {@code WATERFALL_JUMP_OFF_BAXTORIAN@659_3304#659_3305} as {@code waterfall jump off baxtorian at 659, 3304}. */
    private static String readable(String transport) {
        int at = transport.indexOf('@');
        String name = (at < 0 ? transport : transport.substring(0, at))
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ');
        if (at < 0) {
            return name;
        }
        String[] xz = transport.substring(at + 1).split("[#]")[0].split("_");
        return xz.length == 2 ? name + " at " + xz[0] + ", " + xz[1] : name;
    }

    // What stops a walk, as a player would fix it: the requirement a hop fails, or a hop the camper cannot take.
    private static List<String> blockers(
            Pathfinder pf, AccountFacts facts, NavPolicy policy, int fx, int fz, int tx, int tz) {
        PathfinderPlans.Response plan = PathfinderPlans.plan(
                pf,
                facts,
                new PathfinderPlans.Request(
                        new PathfinderPlans.Tile(fx, fz), new PathfinderPlans.Tile(tx, tz), null, null, null, policy));
        if (plan.why() == null) {
            return List.of();
        }
        // The diagnosis's fixes are alternatives: name the one asking least of the camper.
        List<String> best = null;
        int bestShut = Integer.MAX_VALUE;
        for (PathfinderPlans.Fix fix : plan.why().fixes()) {
            List<String> whys = new ArrayList<>();
            int shut = 0;
            for (PathfinderPlans.Blocker b : fix.blockers()) {
                String why;
                if (b.what() != null && b.what().startsWith("the policy's prohibit") && b.transport() != null) {
                    why = readable(b.transport()) + " is not a plain click or item use";
                    shut++;
                } else if (b.fix() != null && b.fix().startsWith("satisfy ")) {
                    why = (b.transport() == null ? "" : readable(b.transport()) + " needs ")
                            + b.fix().substring("satisfy ".length());
                } else {
                    why = b.why();
                    shut++;
                }
                if (why != null && !whys.contains(why)) {
                    whys.add(why);
                }
            }
            if (best == null || shut < bestShut || (shut == bestShut && whys.size() < best.size())) {
                best = whys;
                bestShut = shut;
            }
        }
        return best == null ? List.of() : best;
    }

    private static PathfinderPlans.AccountInput maxed() {
        Map<String, Integer> skills = new HashMap<>();
        for (Skill sk : Skill.values()) {
            skills.put(sk.name(), 99);
        }
        Map<Integer, Integer> quests = new HashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), QuestId.STAGE_COMPLETED);
        }
        return new PathfinderPlans.AccountInput(skills, quests, null, null, null);
    }

    // What the camper always carries: it banks before an item drops below its min.
    private static Map<Integer, Integer> carriedAtMin(Request req) {
        Map<Integer, Integer> out = new HashMap<>();
        for (Item it : req.items() == null ? List.<Item>of() : req.items()) {
            if (it.min() > 0) {
                out.merge(it.id(), it.min(), Integer::sum);
            }
        }
        return out;
    }

    private static Set<Integer> wornAtMin(Request req) {
        Set<Integer> out = new HashSet<>();
        for (Item it : req.items() == null ? List.<Item>of() : req.items()) {
            if (it.min() > 0 && it.wear()) {
                out.add(it.id());
            }
        }
        return out;
    }

    /**
     * The shortest walk: no danger avoidance, no teleports, and every transport that is not
     * {@link #simple} prohibited.
     */
    static NavPolicy clickOnly(Pathfinder pf, AccountFacts facts) {
        Map<String, Integer> prohibited = new HashMap<>();
        for (Transport t : pf.transports()) {
            if (!simple(t, facts)) {
                prohibited.put(t.name(), NavPolicy.PROHIBITIVE);
            }
        }
        NavPolicy d = NavPolicy.DEFAULT;
        return new NavPolicy(
                NavPolicy.DangerAvoidance.OFF,
                false,
                false,
                Map.copyOf(prohibited),
                false,
                d.avoidSkulled(),
                d.berth());
    }

    /**
     * Whether a client can take {@code t} with one click on a scenery or wall object, or one item used
     * on it: a declarative interact or use with no dialogue, from listed entries, gated on nothing but
     * skills, quests, the player cache and items carried or worn, spending nothing. The gate itself is
     * the planner's to test.
     */
    static boolean simple(Transport t, AccountFacts facts) {
        if (!(t instanceof DeclarativeTransport d) || t.entryFree()) {
            return false;
        }
        EngageSpec spec = d.engageSpec();
        if (!(spec.action() instanceof EngageSpec.Action.Interact || spec.action() instanceof EngageSpec.Action.UseItem)
                || !(spec.target() instanceof EngageSpec.Target.Scenery
                        || spec.target() instanceof EngageSpec.Target.Boundary)
                || !spec.dialog().isEmpty()
                || spec.awaitsDialog()) {
            return false;
        }
        for (Fact f : t.prereq().facts()) {
            switch (f.kind()) {
                case CARRIED_AT_LEAST,
                        WEARING,
                        CARRYING_MELEE_WEAPON,
                        SKILL_AT_LEAST,
                        BASE_SKILL_AT_LEAST,
                        QUEST_AT_LEAST,
                        QUEST_COMPLETE,
                        QUEST_EQUALS,
                        QUEST_POINTS_AT_LEAST,
                        TOTAL_LEVEL_AT_LEAST,
                        CACHE_BOOL,
                        CACHE_INT_AT_LEAST,
                        CACHE_INT_EQUALS -> {}
                default -> {
                    return false;
                }
            }
        }
        return t.consumes(facts).isEmpty();
    }

    private static void route(
            StringBuilder b,
            List<NavProbe.ProbeStep> steps,
            Map<String, Transport> byName,
            ServerData env,
            int cutter) {
        StaticLocIndex locs = env.collisionMap().staticLocIndex();
        NavProbe.ProbeStep first = steps.get(0);
        int lastX = first.x();
        int lastZ = first.y();
        walk(b, lastX, lastZ);
        int walked = 0;
        for (int i = 1; i < steps.size(); i++) {
            NavProbe.ProbeStep from = steps.get(i - 1);
            NavProbe.ProbeStep s = steps.get(i);
            if (s.hopName() != null) {
                Transport t = byName.get(s.hopName());
                if (t == null) {
                    throw new IllegalStateException("no transport " + s.hopName());
                }
                if (from.x() != lastX || from.y() != lastZ) {
                    walk(b, from.x(), from.y());
                }
                hop(b, t, s, cutter);
                lastX = s.x();
                lastZ = s.y();
                walked = 0;
                continue;
            }
            if (s.bankVisit() || (s.x() == from.x() && s.y() == from.y())) {
                continue;
            }
            String door = doorOn(locs, env, from.x(), from.y(), s.x(), s.y());
            if (door != null) {
                if (from.x() != lastX || from.y() != lastZ) {
                    walk(b, from.x(), from.y());
                }
                b.append("  ").append(door).append('\n');
                walk(b, s.x(), s.y());
                lastX = s.x();
                lastZ = s.y();
                walked = 0;
                continue;
            }
            walked++;
            if (walked >= WAYPOINT_TILES || i == steps.size() - 1) {
                walk(b, s.x(), s.y());
                lastX = s.x();
                lastZ = s.y();
                walked = 0;
            }
        }
    }

    private static void walk(StringBuilder b, int x, int z) {
        b.append("  walk ").append(x).append(' ').append(z).append('\n');
    }

    // A cut web is gone and its cutter stays put, so it is a door to clear and a walk through it:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L41
    private static void hop(StringBuilder b, Transport t, NavProbe.ProbeStep landing, int cutter) {
        EngageSpec spec = ((DeclarativeTransport) t).engageSpec();
        if (t instanceof WebTransport && spec.target() instanceof EngageSpec.Target.Boundary w) {
            b.append("  cut ")
                    .append(w.x())
                    .append(' ')
                    .append(w.y())
                    .append(' ')
                    .append(Math.max(0, w.dir()))
                    .append(' ')
                    .append(w.boundaryId())
                    .append(' ')
                    .append(spec.action() instanceof EngageSpec.Action.UseItem ? cutter : 0);
            comment(b, t.name());
            walk(b, landing.x(), landing.y());
            return;
        }
        String verb;
        String what;
        switch (spec.action()) {
            case EngageSpec.Action.Interact i -> {
                verb = "";
                what = String.valueOf(i.command());
            }
            case EngageSpec.Action.UseItem u -> {
                verb = "use";
                what = String.valueOf(u.selector() instanceof EngageSpec.ItemSelector.Fixed f ? f.itemId() : cutter);
            }
            default -> throw new IllegalStateException("not a click or a use: " + t.name());
        }
        switch (spec.target()) {
            case EngageSpec.Target.Scenery sc ->
                b.append("  ")
                        .append(verb)
                        .append("obj ")
                        .append(sc.x())
                        .append(' ')
                        .append(sc.y())
                        .append(' ')
                        .append(what);
            case EngageSpec.Target.Boundary w ->
                b.append("  ")
                        .append(verb)
                        .append("wall ")
                        .append(w.x())
                        .append(' ')
                        .append(w.y())
                        .append(' ')
                        .append(Math.max(0, w.dir()))
                        .append(' ')
                        .append(what);
            default -> throw new IllegalStateException("not a scenery or wall object: " + t.name());
        }
        b.append(' ').append(landing.x()).append(' ').append(landing.y());
        comment(b, t.name());
    }

    /**
     * The item a cut uses, as the server's cutter rule picks it from the loadout's always-carried
     * items: a knife, else the first melee weapon; 0 when there is none.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L26">CutWeb.onUseBound</a>
     */
    static int cutterOf(ServerData env, Request req) {
        int weapon = 0;
        for (Item it : req.items() == null ? List.<Item>of() : req.items()) {
            if (it.min() <= 0) {
                continue;
            }
            if (it.id() == ItemId.KNIFE.id()) {
                return it.id();
            }
            ItemDef def = env.item(it.id());
            if (weapon == 0 && def != null && def.isMeleeWeapon()) {
                weapon = it.id();
            }
        }
        return weapon;
    }

    /**
     * The {@code door} or {@code gate} step for a free door the step {@code (fx, fy)} to
     * {@code (tx, ty)} goes through, or {@code null}: the edges and diagonal doors a step crosses,
     * as the server's step check reads them.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L523">PathValidation.checkAdjacent</a>
     */
    static String doorOn(StaticLocIndex locs, ServerData env, int fx, int fy, int tx, int ty) {
        int dx = tx - fx;
        int dy = ty - fy;
        String door;
        if (dx == 0 || dy == 0) {
            door = onEdge(locs, env, fx, fy, tx, ty);
        } else {
            door = onEdge(locs, env, fx, fy, fx + dx, fy);
            if (door == null) {
                door = onEdge(locs, env, fx, fy, fx, fy + dy);
            }
            if (door == null) {
                door = onEdge(locs, env, fx, fy + dy, tx, ty);
            }
            if (door == null) {
                door = onEdge(locs, env, fx + dx, fy, tx, ty);
            }
        }
        if (door == null) {
            door = diagonalDoorOn(locs, env, tx, ty);
        }
        if (door == null && dx != 0 && dy != 0) {
            door = diagonalDoorOn(locs, env, fx + dx, fy);
            if (door == null) {
                door = diagonalDoorOn(locs, env, fx, fy + dy);
            }
        }
        return door;
    }

    private static String onEdge(StaticLocIndex locs, ServerData env, int ax, int ay, int bx, int by) {
        int dx = bx - ax;
        int dy = by - ay;
        int wx = dx == 1 ? ax + 1 : ax;
        int wz = dy == 1 ? ay + 1 : ay;
        int wdir = dy != 0 ? 0 : 1;
        int wall = locs.boundaryAt(wx, wz, wdir);
        if (wall >= 0 && env.isGenericFreeDoor(locs.boundaryId(wall))) {
            return "door " + wx + " " + wz + " " + wdir + " " + locs.boundaryId(wall);
        }
        if (wdir == 1) {
            String east = gate(locs, env, wx, wz, 0);
            return east != null ? east : gate(locs, env, wx - 1, wz, 4);
        }
        String north = gate(locs, env, wx, wz, 6);
        return north != null ? north : gate(locs, env, wx, wz - 1, 2);
    }

    private static String diagonalDoorOn(StaticLocIndex locs, ServerData env, int x, int z) {
        for (int dir = 2; dir <= 3; dir++) {
            int wall = locs.boundaryAt(x, z, dir);
            if (wall >= 0 && env.isGenericFreeDoor(locs.boundaryId(wall))) {
                return "door " + x + " " + z + " " + dir + " " + locs.boundaryId(wall);
            }
        }
        int h = locs.sceneryCovering(x, z);
        if (h >= 0 && env.isGenericFreeSceneryDoor(locs.sceneryId(h)) && (locs.sceneryDir(h) & 1) == 1) {
            return "gate " + locs.sceneryX(h) + " " + locs.sceneryY(h) + " " + locs.sceneryId(h);
        }
        return null;
    }

    private static String gate(StaticLocIndex locs, ServerData env, int x, int z, int dir) {
        int h = locs.sceneryCovering(x, z);
        if (h < 0 || !env.isGenericFreeSceneryDoor(locs.sceneryId(h)) || locs.sceneryDir(h) != dir) {
            return null;
        }
        return "gate " + locs.sceneryX(h) + " " + locs.sceneryY(h) + " " + locs.sceneryId(h);
    }

    /**
     * Ticks from an NPC's death to its return: its respawn time times the world's multiplier, in whole
     * milliseconds, rounded up to whole ticks.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/Npc.java#L840">Npc.remove</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/DelayedEvent.java#L16">DelayedEvent</a>
     */
    static int respawnTicks(ServerData env, NpcDef def) {
        if (def == null || def.respawnTime() <= 0) {
            return 0;
        }
        long ms = (long) (def.respawnTime() * env.world().rules().npcRespawnMultiplier() * 1000);
        int tick = env.world().rules().gameTickMs();
        return (int) ((ms + tick - 1) / tick);
    }

    // The spawn whose farthest other spawn is nearest.
    private static PathfinderPlans.Tile centreOf(List<ServerData.NpcSpawn> spawns) {
        ServerData.NpcSpawn best = spawns.get(0);
        int bestFar = Integer.MAX_VALUE;
        for (ServerData.NpcSpawn s : spawns) {
            int far = 0;
            for (ServerData.NpcSpawn o : spawns) {
                far = Math.max(far, Math.max(Math.abs(s.startX() - o.startX()), Math.abs(s.startZ() - o.startZ())));
            }
            if (far < bestFar) {
                bestFar = far;
                best = s;
            }
        }
        return new PathfinderPlans.Tile(best.startX(), best.startZ());
    }

    private static int floorOf(int z) {
        return z / CollisionMap.FLOOR_HEIGHT;
    }

    private static boolean last(List<NavProbe.ProbeStep> steps, int x, int z) {
        NavProbe.ProbeStep s = steps.get(steps.size() - 1);
        return s.x() == x && s.y() == z;
    }

    private static List<RouteTile> tiles(List<NavProbe.ProbeStep> steps) {
        List<RouteTile> out = new ArrayList<>(steps.size());
        for (NavProbe.ProbeStep s : steps) {
            out.add(new RouteTile(s.x(), s.y(), s.hopName() != null));
        }
        return out;
    }

    // "12x Pirate @ 268, 104": the spawns of each NPC, in first-picked order, and the camp.
    private static String titleOf(ServerData env, List<ServerData.NpcSpawn> spawns, PathfinderPlans.Tile camp) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ServerData.NpcSpawn s : spawns) {
            String n = npcName(env, s.id());
            counts.merge(n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((name, n) -> parts.add(n + "x " + name));
        return String.join(", ", parts) + " @ " + camp.x() + ", " + camp.z();
    }

    /**
     * The NPCs whose tile the world refuses a player's step onto, of every spawn that wanders within
     * {@link #IN_THE_WAY_TILES} of the camp or a route tile, by id.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L658">PathValidation.isMobBlocking</a>
     */
    private static List<Integer> inTheWay(ServerData env, Box camp, List<List<RouteTile>> routes) {
        ServerRules.NpcBlocking blocking = env.world().rules().npcBlocking();
        Set<Integer> ids = new TreeSet<>();
        for (ServerData.NpcSpawn s : env.npcSpawns()) {
            if (ids.contains(s.id()) || !blocking.mayBlock(env.npc(s.id()))) {
                continue;
            }
            Box near = new Box(
                    s.minX() - IN_THE_WAY_TILES,
                    s.minZ() - IN_THE_WAY_TILES,
                    s.maxX() + IN_THE_WAY_TILES,
                    s.maxZ() + IN_THE_WAY_TILES);
            boolean in = near.minX() <= camp.maxX()
                    && camp.minX() <= near.maxX()
                    && near.minZ() <= camp.maxZ()
                    && camp.minZ() <= near.maxZ();
            for (List<RouteTile> route : routes) {
                for (RouteTile t : route) {
                    in |= t.x() >= near.minX() && t.x() <= near.maxX() && t.z() >= near.minZ() && t.z() <= near.maxZ();
                }
            }
            if (in) {
                ids.add(s.id());
            }
        }
        return List.copyOf(ids);
    }

    private static String names(ServerData env, List<Integer> ids) {
        List<String> out = new ArrayList<>(ids.size());
        ids.forEach(id -> out.add(itemName(env, id)));
        return String.join(", ", out);
    }

    /**
     * The inventory slots {@code items} take at their targets: a stack one, anything else one each.
     * On a world with an equipment tab a worn item is not in the inventory, and a worn stack goes whole.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L94">Equipment.add</a>
     */
    public static int slots(ServerData env, List<Item> items) {
        boolean tab = env.world().rules().equipmentTab();
        int slots = 0;
        for (Item it : items) {
            int held = Math.max(it.min(), it.target());
            boolean stack = env.isStackable(it.id());
            if (tab && it.wear() && held > 0) {
                held = stack ? 0 : held - 1;
            }
            slots += held <= 0 ? 0 : stack ? 1 : held;
        }
        return slots;
    }

    /** @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/Bones.java#L22">Bones.blockOpInv</a> */
    static boolean buryable(ServerData env, int id) {
        var def = id >= 0 && id < env.itemDefs().size() ? env.itemDefs().get(id) : null;
        return def != null && "bury".equalsIgnoreCase(def.command()) && id != RASHILIYA_CORPSE;
    }

    private static String npcName(ServerData env, int id) {
        NpcDef def = env.npc(id);
        return def == null ? "npc " + id : def.name();
    }

    private static String itemName(ServerData env, int id) {
        String name = id >= 0 && id < env.itemDefs().size() ? env.itemDefs().nameOf(id) : null;
        return name == null || name.equals("?") ? "item " + id : name;
    }

    private static void comment(StringBuilder b, String text) {
        b.append("   # ").append(text.replace('\n', ' ')).append('\n');
    }
}
