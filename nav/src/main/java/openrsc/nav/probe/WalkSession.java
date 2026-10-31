package openrsc.nav.probe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.Action;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.StayWalk;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.packets.AttackNpcPacket;
import openrsc.bot.api.packets.ItemEquipPacket;
import openrsc.bot.api.packets.ItemUnequipFromEquipmentPacket;
import openrsc.bot.api.packets.ItemUnequipPacket;
import openrsc.bot.api.packets.WalkToPointPacket;
import openrsc.gamedata.ServerRules.NpcBlocking;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.LocalOverlay;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.engine.ClusterGeometry;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;
import openrsc.nav.walk.Berth;
import openrsc.nav.walk.Plow;
import openrsc.nav.walk.RouteFollower;
import openrsc.nav.walk.RouteKeeper;
import openrsc.nav.walk.StepView;
import openrsc.nav.walk.WalkYield;
import openrsc.nav.walk.WindowSearch;
import openrsc.nav.walk.Yield;

/**
 * One simulated walk, a tick at a time, for a host that plays the server: each tick the host says
 * what the bot sees ({@link View}) and the session answers what the bot does ({@link Move}) and the
 * packets that carry it. The host decides what only the server decides: whether a step is taken,
 * whether a fight lets the bot go, where a hop lands.
 *
 * <p>Each tick re-plans from the tile stood on ({@link RouteKeeper}), berths the aggressive NPCs in
 * view ({@link Berth}), aims with the {@link RouteFollower}, and keeps the path searched earlier
 * while the bot is still on it and no NPC threatens it. A session is not thread-safe.
 */
public final class WalkSession {

    /** The most tiles one {@link Walk} chunk holds, the tile stood on included. */
    public static final int MAX_WAYPOINTS = 26;

    /**
     * What the bot sees at the start of a tick: its tile, whether it is in combat, the NPCs in view.
     * It carries no retreat, so the session never takes the aggro grace that follows one.
     */
    public record View(Point pos, boolean inCombat, List<? extends Yield.Sighting> npcs) {}

    /** What the bot does this tick. */
    public sealed interface Move {}

    /**
     * One tick's answer: {@code send} carries {@code move}, {@code null} for nothing to send;
     * {@code unsent} says why a move that needs packets has none; {@code held} is the inventory
     * whose slot numbers the packets use.
     */
    public record Tick(
            Move move, @Nullable Action send, @Nullable String unsent, Inventory held) {}

    /** Walks down {@code chunk}: index 0 is the tile stood on, the rest the tiles to take in turn. */
    public record Walk(List<Point> chunk) implements Move {}

    /**
     * Stays put: a walk to the bot's own tile, which replaces any walk queued earlier.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L95">WalkRequest.process</a>
     */
    public record Hold() implements Move {}

    /** Steps to {@code tile} instead, away from an aggressor that closed in. */
    public record BackOff(Point tile) implements Move {}

    /**
     * Waits: the next tile holds an NPC that the world's {@code npc_blocking} rule says blocks it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L672">PathValidation.isMobBlocking</a>
     */
    public record Wait() implements Move {}

    /** Attacks the NPC that has sealed the way ({@link Plow}). */
    public record Attack(int serverIndex) implements Move {}

    /** Takes a transport from the tile stood on; the bot comes out at {@code landing}. */
    public record Hop(NavProbe.ProbeStep step, Point landing) implements Move {}

    /** Banks here; the session has already applied the stop to what it holds. */
    public record Bank(NavProbe.ProbeStep step) implements Move {}

    /** Equips {@code item}, or takes it off when not {@code on}. */
    public record Wear(int item, boolean on) implements Move {}

    /** At the goal, or inside the area the route arrives in. */
    public record Arrived() implements Move {}

    /**
     * Nothing to do, for good: no route ({@code first}: not even from the start), a bank stop the plan
     * asks for again, or no path in the search window to the tile aimed at.
     */
    public record Stuck(String why, boolean first) implements Move {}

    private final Pathfinder pf;
    private final Planner nav;
    private final NavPolicy pol;
    private final LoadoutSpec loadout;
    private final Point goal;
    private final NavProbe.SimulationStats stats;
    private final Map<String, Transport> byName = new HashMap<>();
    private final CollisionMap map;
    private final RouteFollower follower;
    private final RouteKeeper keeper;
    private final int mobRange;
    private final StepView plain;
    private final boolean berthOn;
    private final int combatLevel;
    private final NpcBlocking blocking;

    private final Yield yield = new Yield();
    private final Set<String> stopsMade = new HashSet<>();
    private AccountFacts base;
    private WindowSearch.Momentum momentum = WindowSearch.Momentum.NONE;
    private boolean first = true;
    private @Nullable Point hopLanding;

    private @Nullable Inventory hopLanded;
    private final Set<Integer> wearing = new LinkedHashSet<>();
    private int sealedTicks;

    private Inventory pack;
    private @Nullable List<Point> lastPath;

    private @Nullable Rect lastTarget;

    /**
     * A walk from {@code start} to {@code goal}. A {@code null} account walks as
     * {@link NavProbe#allEligibleFacts}; a {@code null} policy is {@link NavPolicy#DEFAULT}.
     */
    public WalkSession(
            Pathfinder pf,
            @Nullable AccountFacts account,
            @Nullable NavPolicy policy,
            LoadoutSpec loadout,
            Point start,
            Point goal,
            NavProbe.SimulationStats stats) {
        this.pf = pf;
        this.nav = pf.nav();
        this.loadout = loadout;
        this.goal = goal;
        this.stats = stats;
        this.base = NavProbe.factsFor(account, pf, start.x(), start.y());
        this.pol = NavProbe.policyFor(account, policy).forWalker(base.stats());
        for (Transport t : pf.transports()) {
            byName.putIfAbsent(t.name(), t);
        }
        this.map = pf.serverData().collisionMap();
        this.follower = new RouteFollower(map);
        this.keeper = new RouteKeeper(nav);
        this.mobRange = pf.serverData().world().mobRange();
        this.blocking = pf.serverData().world().rules().npcBlocking();
        this.plain = StepView.ofMap(map, Planner.DangerCharge.NONE);
        this.berthOn = pol.berth().berthsAnything();
        this.combatLevel = AccountFacts.combatLevel(base.stats());
        this.pack = base.inventory();
        for (InventorySlot slot : pack.slots()) {
            if (slot.equipped()) {
                wearing.add(slot.id());
            }
        }
    }

    /** What the bot holds after the last tick's move, as the route projects it. */
    public Inventory pack() {
        return pack;
    }

    /** Item ids worn: the account's own, then as this session's equips changed them. */
    public Set<Integer> wearing() {
        return wearing;
    }

    /** The bank as this walk's stops have left it. */
    public openrsc.bot.api.container.Bank bank() {
        return base.bank();
    }

    /** The path being walked, from the tile it was searched on; {@code null} before the first. */
    public @Nullable List<Point> path() {
        return lastPath;
    }

    public int combatLevel() {
        return combatLevel;
    }

    /** How far the bot sees NPCs, in tiles: the world's mob range. */
    public int viewRadius() {
        return mobRange;
    }

    public Tick tick(View v) {
        send = null;
        unsent = null;
        Inventory held = base.inventory();
        Move move = decide(v);
        if (move instanceof Wear wear) {
            InventorySlot slot = slotOf(held, wear.item(), wear.on());
            send = slot == null ? null : wear.on() ? new ItemEquipPacket(slot.slot()) : takeOff(slot);
        }
        return new Tick(move, send, unsent, held);
    }

    private @Nullable Action send;

    private @Nullable String unsent;

    private Move decide(View v) {
        Point pos = v.pos();
        // A hop's pack is booked only once the bot stands on its landing: the host may refuse it.
        if (hopLanding != null) {
            if (pos.equals(hopLanding)) {
                base = NavProbe.holding(base, hopLanded);
            }
            hopLanding = null;
            hopLanded = null;
        }
        if (pos.equals(goal)) {
            return new Arrived();
        }
        AccountFacts facts = base.at(pos.x(), pos.y());
        stats.ticks++;
        long[] workBefore = first ? nav.workCounters() : null;
        long planStart = System.nanoTime();
        PlanResult r = keeper.plan(new PlanRequest(facts, pos, goal, loadout, pol));
        long planNanos = System.nanoTime() - planStart;
        stats.planNanos += planNanos;
        if (first) {
            long[] workAfter = nav.workCounters();
            stats.firstPlanNanos = planNanos;
            stats.firstPlanBuilt[0] = workAfter[0] - workBefore[0];
            stats.firstPlanBuilt[1] = workAfter[1] - workBefore[1];
            stats.enginePlan = keeper.kept();
        }
        stats.plans = keeper.plans();
        stats.walks = keeper.walks() + keeper.leads();
        boolean wasFirst = first;
        first = false;
        if (!(r instanceof PlanResult.Planned p)) {
            return new Stuck("no route", wasFirst);
        }
        yield.roll(v.npcs(), List.of());
        Berth berth = v.npcs().isEmpty() ? Berth.EMPTY : Berth.build(pos, v.npcs(), combatLevel, blocking);
        Planner.DangerCharge field = keeper.leadFollowsField()
                ? StepView.beyond(nav.dangerCharge(facts, pol), pos, mobRange)
                : Planner.DangerCharge.NONE;
        StepView view = berth == Berth.EMPTY
                ? (field == Planner.DangerCharge.NONE ? plain : StepView.ofMap(map, field))
                : StepView.amongNpcs(map, field, berth, berthOn);
        if (berth != Berth.EMPTY && lastPath != null && threatened(lastPath, pos, berth)) {
            lastPath = null;
        }
        Route route = p.route();
        List<Route.Step> steps = route.steps();
        pack = steps.get(0).pack();
        if (steps.get(0) instanceof Route.BankStop stop && stopsMade.contains(NavProbe.stopKey(stop))) {
            return new Stuck("the stop did not take", false);
        }
        Move kit = assertWorn(steps);
        if (kit != null) {
            return kit;
        }
        if (steps.get(0) instanceof Route.Arrive a && a.target().contains(pos.x(), pos.y())) {
            return new Arrived();
        }
        boolean sameWindow = lastPath != null
                && ClusterGeometry.clusterOf(
                                lastPath.getFirst().x(), lastPath.getFirst().y())
                        == ClusterGeometry.clusterOf(pos.x(), pos.y());
        Rect committed = sameWindow
                        && lastPath != null
                        && lastTarget != null
                        && onPath(lastPath, lastTarget, pos, lastTarget) != null
                ? lastTarget
                : null;
        long walkStart = System.nanoTime();
        RouteFollower.Aim aim = follower.aim(route, pos, view, momentum, committed);
        stats.walkNanos += System.nanoTime() - walkStart;
        Rect target;
        Rect corridor = null;
        Rect onward = null;
        List<Point> path = null;
        if (aim instanceof RouteFollower.Delegate d) {
            Point at = d.step() instanceof Route.Hop h ? h.entry() : ((Route.BankStop) d.step()).bank();
            if (pos.equals(at)) {
                momentum = WindowSearch.Momentum.NONE;
                if (d.step() instanceof Route.Hop hop) {
                    Transport t = byName.get(hop.transport());
                    // The step after the hop holds the pack with the fare paid; the hop's own still counts it.
                    Inventory landed = steps.size() > 1 ? steps.get(1).pack() : hop.pack();
                    hopLanding = hop.landing();
                    hopLanded = landed;
                    pack = landed;
                    click(t, base.inventory(), v, pos);
                    return new Hop(
                            new NavProbe.ProbeStep(
                                    hop.landing().x(),
                                    hop.landing().y(),
                                    hop.transport(),
                                    t == null ? null : t.cost(),
                                    t == null
                                            ? null
                                            : Transport.requirementText(
                                                    t, pf.serverData().itemDefs()),
                                    false,
                                    null,
                                    null,
                                    hop.wears(),
                                    List.of(),
                                    landed),
                            hop.landing());
                }
                Route.BankStop stop = (Route.BankStop) d.step();
                Map<Integer, Long> withdraw = new LinkedHashMap<>();
                stop.manifest().withdraw().forEach((id, n) -> withdraw.put(id.id(), n));
                Map<Integer, Long> deposit = new LinkedHashMap<>();
                stop.manifest().deposit().forEach((id, n) -> deposit.put(id.id(), n));
                base = NavProbe.settled(base, stop);
                stopsMade.add(NavProbe.stopKey(stop));
                pack = stop.leaving();
                return new Bank(new NavProbe.ProbeStep(
                        pos.x(),
                        pos.y(),
                        null,
                        null,
                        null,
                        true,
                        withdraw,
                        deposit,
                        Set.of(),
                        NavProbe.reasons(route, stop, loadout, byName),
                        stop.leaving()));
            }
            target = Rect.of(at, at);
        } else {
            RouteFollower.Walk w = (RouteFollower.Walk) aim;
            target = w.target();
            corridor = w.corridor();
            onward = w.onward();
            path = w.path();
        }
        List<Point> kept = onPath(lastPath, lastTarget, pos, target);
        if (kept != null) {
            path = kept;
        } else if (path == null) {
            long searchStart = System.nanoTime();
            path = follower.search().run(view, pos, target, corridor, onward, true, momentum);
            if (berth != Berth.EMPTY && (path == null || path.size() < 2)) {
                // Walled in by NPCs: search through them, and wait at the one in the way.
                path = follower.search().run(view, pos, target, corridor, onward, false, momentum);
            }
            stats.walkNanos += System.nanoTime() - searchStart;
        }
        if (path == null || path.size() < 2) {
            return new Stuck("no way in the window to " + target, false);
        }
        lastPath = path;
        lastTarget = target;
        List<Point> chunk = path.subList(0, Math.min(path.size(), MAX_WAYPOINTS));
        Point next = path.get(1);
        Yield.Sighting blocker = blocker(v.npcs(), next, berth, blocking, combatLevel);
        if (blocker != null) {
            sealedTicks++;
            boolean plowable = Plow.plowable(
                    blocker.def(),
                    blocker.inCombat(),
                    map.canReachMob(pos.x(), pos.y(), next.x(), next.y(), (LocalOverlay) null));
            if (plowable && sealedTicks >= Plow.PATIENCE && !v.inCombat()) {
                send = new AttackNpcPacket(blocker.serverIndex());
                return new Attack(blocker.serverIndex());
            }
            // Any other blocker is walked at every tick, so the step lands the tick it moves off.
            send = plowable ? null : walk(chunk);
            return new Wait();
        }
        sealedTicks = 0;
        if (!v.npcs().isEmpty() || v.inCombat()) {
            StepView stepView = view;
            WalkYield.Decision d = yield.decide(
                    pos,
                    target,
                    chunk,
                    v.npcs(),
                    List.of(),
                    combatLevel,
                    v.inCombat(),
                    Integer.MAX_VALUE,
                    pol,
                    -1,
                    (fx, fy, tx, ty) -> stepView.canStep(fx, fy, tx, ty) && !stepView.occupied(tx, ty),
                    (fx, fy, tx, ty) -> map.canReachMob(fx, fy, tx, ty, (LocalOverlay) null),
                    Yield.byComponent(map));
            switch (d.action) {
                case BACK_OFF -> {
                    Point tile = Point.of(d.tileX, d.tileY);
                    momentum = WindowSearch.Momentum.of(pos, tile);
                    send = new WalkToPointPacket(tile);
                    return new BackOff(tile);
                }
                case HOLD -> {
                    send = StayWalk.at(pos);
                    return new Hold();
                }
                default -> {}
            }
        }
        momentum = WindowSearch.Momentum.of(pos, next);
        send = walk(chunk);
        return new Walk(chunk);
    }

    /**
     * The equip or take-off this tick needs, or {@code null} when what is worn already matches: the
     * loadout's worn items and what the next hop checks is worn, the hop's winning a slot both want.
     * Anything else worn comes off, unless the loadout is a wildcard. One change a tick.
     */
    private @Nullable Move assertWorn(List<Route.Step> steps) {
        Set<Integer> gate = steps.get(0) instanceof Route.Hop hop ? hop.wears() : Set.of();
        Set<Integer> want = new LinkedHashSet<>(gate);
        for (LoadoutSpec.Requirement req : loadout.requirements()) {
            if (req instanceof LoadoutSpec.Item item && item.worn()) {
                int id = item.item().id();
                if (gate.stream()
                        .noneMatch(g -> pf.serverData().displaces(g, id)
                                || pf.serverData().displaces(id, g))) {
                    want.add(id);
                }
            }
        }
        List<Integer> onTheBody = new ArrayList<>();
        for (int id : want) {
            if (wearing.contains(id)) {
                onTheBody.add(id);
                continue;
            }
            if (pack.count(id) > 0
                    && onTheBody.stream().noneMatch(w -> pf.serverData().displaces(id, w))) {
                // Equipping takes off whatever the new piece displaces:
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L550
                wearing.removeIf(w -> pf.serverData().displaces(id, w));
                wearing.add(id);
                return new Wear(id, true);
            }
        }
        if (!loadout.keepsUnnamed()) {
            for (int id : List.copyOf(wearing)) {
                if (!want.contains(id)) {
                    wearing.remove(id);
                    return new Wear(id, false);
                }
            }
        }
        wearing.removeIf(id -> pack.count(id) == 0);
        return null;
    }

    private static Action walk(List<Point> chunk) {
        return new WalkToPointPacket(chunk.subList(1, chunk.size()));
    }

    private void click(@Nullable Transport t, Inventory holding, View v, Point pos) {
        if (!(t instanceof DeclarativeTransport d)) {
            unsent = t == null ? "an unknown crossing" : "a scripted crossing";
            return;
        }
        TransportClick.Click c = TransportClick.of(d.engageSpec(), holding, pf.serverData(), v.npcs());
        if (c.packet() == null) {
            unsent = c.missing();
            return;
        }
        EngageSpec.Target target = d.engageSpec().target();
        boolean world = !(target instanceof EngageSpec.Target.Self);
        send = StayWalk.ahead(world && v.inCombat(), target instanceof EngageSpec.Target.Scenery)
                ? Action.of(StayWalk.at(pos), c.packet())
                : c.packet();
    }

    private static @Nullable InventorySlot slotOf(Inventory pack, int item, boolean on) {
        for (InventorySlot slot : pack.slots()) {
            if (slot.id() == item && slot.equipped() != on) {
                return slot;
            }
        }
        return null;
    }

    private Action takeOff(InventorySlot slot) {
        // With an equipment tab a worn item has left the inventory, so it comes off by its equipment
        // slot:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Equipment.java#L383
        if (!pf.serverData().world().rules().equipmentTab()) {
            return new ItemUnequipPacket(slot.slot());
        }
        ItemDef def = pf.serverData().item(slot.id());
        return new ItemUnequipFromEquipmentPacket(def == null ? -1 : def.wearSlot());
    }

    private static @Nullable Yield.Sighting blocker(
            List<? extends Yield.Sighting> npcs, Point tile, Berth berth, NpcBlocking blocking, int combatLevel) {
        if (!berth.blocks(tile.x(), tile.y())) {
            return null;
        }
        for (Yield.Sighting n : npcs) {
            if (n.x() == tile.x() && n.z() == tile.y() && blocking.blocks(n.def(), combatLevel)) {
                return n;
            }
        }
        return null;
    }

    private static final int LOOKAHEAD = MAX_WAYPOINTS;

    private static boolean threatened(List<Point> path, Point pos, Berth berth) {
        int at = path.indexOf(pos);
        if (at < 0) {
            return false;
        }
        for (int i = at + 1; i < path.size() && i <= at + LOOKAHEAD; i++) {
            Point t = path.get(i);
            if (berth.blocks(t.x(), t.y()) || berth.inReach(t.x(), t.y())) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable List<Point> onPath(
            @Nullable List<Point> path, @Nullable Rect lastTarget, Point pos, Rect target) {
        if (path == null || !target.equals(lastTarget)) {
            return null;
        }
        int at = path.indexOf(pos);
        return at < 0 || at == path.size() - 1 ? null : path.subList(at, path.size());
    }
}
