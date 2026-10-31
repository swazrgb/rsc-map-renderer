package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.transports.AllTransports;
import openrsc.nav.transports.Banks;
import org.junit.jupiter.api.Test;

class EngineReleaseTest {

    private static final long MB = 1L << 20;

    @Test
    void anEngineThatPlannedOnTwoThreadsIsCollectedOnceDropped() throws Exception {
        ServerData env = RealDefs.serverData();
        // A thread that planned and lives on, beside the test's own.
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            WeakReference<Engine> dropped = planAndDrop(env, worker);
            assertNull(collected(dropped), "a dropped engine is still reachable from a thread's memos");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void aGraphIsSharedAcrossACollectionWhileHeldAndReleasedAfter() {
        ServerData env = RealDefs.serverData();
        // A list of its own: no other engine asks with this identity, so nothing else holds the graph.
        List<Transport> transports = new ArrayList<>(AllTransports.defaultRegistry(env));
        ClusterGraph first = ClusterGraph.build(env.collisionMap(), transports, Banks.on(env));
        WeakReference<ClusterGraph> held = new WeakReference<>(first);
        System.gc();
        assertSame(first, ClusterGraph.build(env.collisionMap(), transports, Banks.on(env)), "built again after a GC");
        first = null;
        assertNull(collected(held), "a graph no engine holds is kept by the cache");
    }

    private static WeakReference<Engine> planAndDrop(ServerData env, ExecutorService worker) throws Exception {
        Pathfinder pf = Pathfinder.load(env, new CaffeineNavCaches(new NavCacheBudget(16 * MB, 4 * MB, 16 * MB)));
        Engine engine = (Engine) pf.nav();
        int[] gateItems = pf.eligibilityItemIds();
        int[] gateQuests = pf.eligibilityQuestIds();
        SplittableRandom rnd = new SplittableRandom(31);
        for (int a = 0; a < 4; a++) {
            AccountFacts account = PrereqProgramTest.account(env, rnd, gateItems, gateQuests);
            Point from = Point.of(122 + a, 647);
            PlanRequest request = new PlanRequest(
                    account.at(from.x(), from.y()), from, Point.of(220, 640), LoadoutSpec.any(), NavPolicy.DEFAULT);
            engine.plan(request);
            worker.submit(() -> engine.plan(request)).get();
            engine.estimate(account.at(from.x(), from.y()), from, Point.of(310, 545), NavPolicy.DEFAULT);
        }
        return new WeakReference<>(engine);
    }

    private static <T> T collected(WeakReference<T> ref) {
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.get();
    }
}
