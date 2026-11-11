package openrsc.sessionproxy;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import openrsc.bot.api.Action;
import openrsc.bot.api.ActionExecuteContext;
import openrsc.bot.api.GameState;
import openrsc.bot.api.Packet;
import openrsc.bot.api.StayWalk;
import openrsc.bot.api.TickKey;
import openrsc.bot.api.behavior.DoorOps;
import openrsc.bot.api.codec.OpenRscCodec;
import openrsc.bot.api.codec.WireLayout;
import openrsc.bot.api.store.BotKey;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.planner.PathfinderPlans;
import openrsc.nav.probe.NavProbe;
import openrsc.nav.probe.WalkSession;
import openrsc.nav.transports.DeclarativeTransport;
import org.jetbrains.annotations.Nullable;

/**
 * A walk the live map asked for: the account walks to a tile one tick at a time, as {@link WalkSession} plans it from
 * what the session sees, opening the doors it finds closed ({@link DoorOps}) and taking every crossing that takes one
 * click, such as a ladder. A crossing that opens a conversation is answered from its keywords ({@link Crossing}), and a
 * bank stop its {@link LoadoutSpec} needs is made at the bank ({@link BankVisit}). Its routing rules out the crossings
 * it cannot take ({@link ProxyRouting}). A walk for a {@link MapAction} heads for a tile it can act from, and ends by
 * taking it once its target is in reach. It stops at an NPC that blocks the way, and after {@value #STILL_TICKS}
 * ticks without a step. The pathfinder is asked for on the first step. Each step re-plans under the walk's
 * {@link NavPolicy} and loadout, and leaves the route it planned to be drawn ({@link #planned}).
 *
 * <p>The session hands it each tick's state from its own thread ({@link #offer}); the walk's thread takes the newest
 * ({@link #await}), skipping any that came while it was still deciding.
 */
final class MapWalk {

    /** One tick of the walk: the frames to send the server, then {@code end} when the walk is over. */
    record Step(List<ByteBuf> frames, @Nullable String end) {}

    private static final int STILL_TICKS = 30;

    private final Supplier<Pathfinder> pathfinder;
    private volatile Point goal;
    private final @Nullable MapAction action;
    private final @Nullable NavPolicy policy;
    private final LoadoutSpec loadout;
    private final OpenRscCodec codec;
    private @Nullable Pathfinder pf;
    private @Nullable WalkSession walk;
    private @Nullable Point lastPos;
    private int stillTicks;
    private final AtomicReference<GameState> latest = new AtomicReference<>();
    private final Semaphore ticked = new Semaphore(0);
    private volatile boolean stopped;
    private volatile Session.@Nullable WalkRoute planned;
    private @Nullable BankVisit visit;
    private @Nullable Crossing crossing;
    private final Set<String> missed = new HashSet<>();
    private final Set<String> stopsMade = new HashSet<>();

    /**
     * @param policy the policy the walk plans under; null for the account's default
     * @param loadout what the player arrives with
     * @param action what the walk ends by doing, once its target is in reach; null to end on {@code goal}
     */
    MapWalk(
            Supplier<Pathfinder> pathfinder,
            Point goal,
            @Nullable NavPolicy policy,
            LoadoutSpec loadout,
            @Nullable MapAction action,
            Supplier<WireLayout> layout) {
        this.pathfinder = pathfinder;
        this.goal = goal;
        this.action = action;
        this.policy = policy;
        this.loadout = loadout;
        this.codec = new OpenRscCodec(layout);
    }

    Point goal() {
        return goal;
    }

    /** The route the last step planned; null before the first, or when it found none. */
    Session.@Nullable WalkRoute planned() {
        return planned;
    }

    /** Hands the walk a tick's state. */
    void offer(GameState state) {
        latest.set(state);
        ticked.release();
    }

    /** Ends the walk: {@link #await} returns null from now on. */
    void stop() {
        stopped = true;
        ticked.release();
    }

    /** The newest state handed over, waiting for one; null once the walk is stopped. */
    @Nullable
    GameState await() throws InterruptedException {
        ticked.acquire();
        ticked.drainPermits();
        return stopped ? null : latest.get();
    }

    /** One tick's step; called from the walk's one thread. */
    Step step(GameState state) {
        Pathfinder pf = this.pf;
        if (pf == null) {
            pf = this.pf = pathfinder.get();
        }
        // A bank stop or a crossing's conversation, once begun, runs before the walk goes on; standing still is its
        // due. After either, the walk starts afresh from what the player holds: the slots a click names are the
        // server's, which the walk's own projection of the pack need not match.
        if (visit != null) {
            stillTicks = 0;
            lastPos = state.pos();
            BankVisit.Next n = visit.step(state);
            if (n.failed() != null) {
                return end(n.failed());
            }
            if (n.done()) {
                visit = null;
                this.walk = null;
            }
            return new Step(frames(n.packets()), null);
        }
        if (crossing != null) {
            stillTicks = 0;
            lastPos = state.pos();
            Crossing.Next n = crossing.step(state);
            if (n.failed() != null) {
                return end(n.failed());
            }
            if (!n.over()) {
                return new Step(n.answer() == null ? List.of() : frames(List.of(n.answer())), null);
            }
            // One that left the player elsewhere may have landed it somewhere else; the walk plans on from there, and
            // ends if it asks for the same crossing again.
            if (!state.pos().equals(crossing.landing()) && !missed.add(crossing.name())) {
                return end(crossing.name() + " did not take the player across");
            }
            crossing = null;
            this.walk = null;
        }
        if (action != null) {
            Targetable target = action.target(state);
            if (target == null) {
                return end("out of sight");
            }
            if (state.canEngage(target)) {
                return act(state, action);
            }
            // The walk heads for the nearest tile it can act from, which moves with an NPC or a player.
            Point from = state.closestEngageTile(state.pos(), target);
            Point aim = from == null ? target.point() : from;
            if (!aim.equals(goal)) {
                goal = aim;
                this.walk = null;
            }
        }
        WalkSession walk = this.walk;
        if (walk == null) {
            walk = this.walk = new WalkSession(
                    pf,
                    state,
                    ProxyRouting.limit(pf, policy),
                    loadout,
                    state.pos(),
                    goal,
                    new NavProbe.SimulationStats());
        }
        stillTicks = state.pos().equals(lastPos) ? stillTicks + 1 : 0;
        lastPos = state.pos();
        if (stillTicks >= STILL_TICKS) {
            return end("no step for " + STILL_TICKS + " ticks at " + state.pos().x() + ","
                    + state.pos().y());
        }
        WalkSession.Tick tick = walk.tick(new WalkSession.View(state.pos(), state.inCombat(), state.npcs()));
        Route route = walk.route();
        List<Point> path = walk.path();
        planned = route == null
                ? null
                : new Session.WalkRoute(
                        state.pos(), PathfinderPlans.planOf(route), path == null ? List.of() : List.copyOf(path));
        Action door = null;
        switch (tick.move()) {
            case WalkSession.Walk w -> {
                DoorOps.Ahead ahead = DoorOps.ahead(state, state.pos(), w.chunk());
                door = ahead == null ? null : ahead.click();
            }
            case WalkSession.Arrived a -> {
                return action == null ? end("arrived") : act(state, action);
            }
            case WalkSession.Stuck s -> {
                return end(s.why());
            }
            case WalkSession.Bank b -> {
                // A stop planned again as it was made did not settle the pack as planned, and would be made forever.
                String stop = state.pos() + " " + b.step().withdraw() + b.step().deposit() + b.notes();
                if (!stopsMade.add(stop)) {
                    return end("the bank stop did not leave the pack as planned");
                }
                visit = new BankVisit(state.pos(), b.step().withdraw(), b.step().deposit(), b.notes());
                return step(state);
            }
            case WalkSession.Attack a -> {
                return end("an NPC blocks the way");
            }
            case WalkSession.Hop h -> {
                Transport t = transport(pf, h.step().hopName());
                if (tick.send() != null
                        && t instanceof DeclarativeTransport d
                        && d.engageSpec().awaitsDialog()) {
                    crossing = new Crossing(h.step().hopName(), d.engageSpec(), h.landing());
                }
            }
            default -> {}
        }
        if (tick.send() == null) {
            return tick.unsent() != null ? end("the walk cannot go on: " + tick.unsent()) : new Step(List.of(), null);
        }
        return new Step(frames(Action.of(tick.send(), door)), null);
    }

    /** The frames that stop the player where it stands, dropping the path the server is walking. */
    List<ByteBuf> stay(Point at) {
        return List.of(codec.frame(StayWalk.at(at)));
    }

    private Step act(GameState state, MapAction action) {
        MapAction.Click c = action.click(state);
        return c.packets() == null ? end(c.missing()) : new Step(frames(c.packets()), "arrived");
    }

    private static @Nullable Transport transport(Pathfinder pf, String name) {
        for (Transport t : pf.transports()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private List<ByteBuf> frames(List<? extends Packet> packets) {
        List<ByteBuf> out = new ArrayList<>(packets.size());
        for (Packet p : packets) {
            out.add(codec.frame(p));
        }
        return out;
    }

    private List<ByteBuf> frames(Action action) {
        List<ByteBuf> out = new ArrayList<>();
        action.execute(new ActionExecuteContext() {
            @Override
            public void describe(String message) {}

            @Override
            public void writePacket(Packet packet) {
                out.add(codec.frame(packet));
            }

            @Override
            public <T> void store(BotKey<T> key, T value) {}

            @Override
            public <T> void storeDelete(BotKey<T> key) {}

            @Override
            public <T> void note(TickKey<T> key, T value) {}
        });
        return out;
    }

    private static Step end(String why) {
        return new Step(List.of(), why);
    }
}
