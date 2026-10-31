package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner.Nearest;
import openrsc.nav.Planner.StaticTargets;
import openrsc.nav.RealDefs;
import openrsc.nav.TravelTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("builds label sets over the whole map: alone, so its peak shares no heap")
class NearestParityTest {

    private record Loc(Targetable.Kind kind, int id, int x, int z, int dir) implements Targetable {}

    private record Candidate(Loc loc, Dest dest) {}

    @Test
    void theNearestIsTheLightestOfEveryCandidateTheBatchWeighs() {
        ServerData env = RealDefs.serverData();
        Pathfinder cachedPf = RealDefs.cachedPathfinder();
        Engine near = (Engine) cachedPf.nav();
        Engine batch = (Engine) RealDefs.pathfinder().nav();
        CollisionMap map = env.collisionMap();
        StaticLocIndex index = map.staticLocIndex();
        int grid = env.world().liveViewGridRadius();
        int[] gateItems = cachedPf.eligibilityItemIds();
        int[] gateQuests = cachedPf.eligibilityQuestIds();

        List<int[]> sceneryIdSets = List.of(idsWithCount(index, true, 40, 400, 3), idsWithCount(index, true, 5, 30, 2));
        List<int[]> boundaryIdSets = List.of(idsWithCount(index, false, 20, 200, 2));
        SplittableRandom rnd = new SplittableRandom(11);
        List<String> mismatches = new ArrayList<>();
        int asked = 0;
        int staticWins = 0;
        int liveWins = 0;
        int boxedAsks = 0;
        for (int trial = 0; trial < 36; trial++) {
            boolean scenery = trial % 3 != 2;
            Targetable.Kind kind = scenery ? Targetable.Kind.SCENERY : Targetable.Kind.BOUNDARY;
            List<int[]> sets = scenery ? sceneryIdSets : boundaryIdSets;
            int[] ids = sets.get(rnd.nextInt(sets.size()));
            List<Candidate> statics = candidates(map, index, kind, ids);
            // Half the asks stand beside a candidate, where the local window prices the nearest; the rest
            // anywhere, where only the hierarchy reaches them.
            Point from = trial % 2 == 0
                    ? standableNear(
                            map, rnd, statics.get(rnd.nextInt(statics.size())).loc())
                    : standable(map, rnd);
            if (from == null) {
                continue;
            }
            AccountFacts account =
                    PrereqProgramTest.account(env, rnd, gateItems, gateQuests).at(from.x(), from.y());
            ChunkBox except = rnd.nextBoolean() ? ChunkBox.around(from.x(), from.y(), grid) : ChunkBox.NONE;
            List<Dest> live = new ArrayList<>();
            if (rnd.nextBoolean()) {
                for (int i = 0; i < 3; i++) {
                    Point p = standableAround(map, rnd, from, 20);
                    if (p != null) {
                        live.add(p);
                    }
                }
            }
            StaticTargets st = new StaticTargets(kind, ids, except, new long[0]);

            List<Dest> all = new ArrayList<>(live);
            List<Loc> locs = new ArrayList<>();
            for (Candidate c : statics) {
                if (!st.excepts(c.loc().x(), c.loc().z(), c.loc().dir())) {
                    all.add(c.dest());
                    locs.add(c.loc());
                }
            }
            long[] weighed = batch.weighBatch(account, from, all, NavPolicy.DEFAULT);
            long least = -1;
            for (long w : weighed) {
                if (w >= 0 && (least < 0 || w < least)) {
                    least = w;
                }
            }

            Nearest got = near.nearest(account, from, live, st, NavPolicy.DEFAULT);
            asked++;
            if (got != null && got.live() >= 0) {
                liveWins++;
            } else if (got != null) {
                staticWins++;
            }
            if (locs.size() < statics.size()) {
                boxedAsks++;
            }
            String at = "trial " + trial + " " + kind + " " + java.util.Arrays.toString(ids) + " from " + from
                    + (except.isEmpty() ? "" : " box") + " live " + live.size();
            if (least < 0) {
                if (got != null) {
                    mismatches.add(at + ": nothing is reachable, nearest said " + got);
                }
                continue;
            }
            if (got == null) {
                mismatches.add(at + ": nearest found nothing, the batch's least weighs " + (least >>> 32));
                continue;
            }
            int index0 = got.live() >= 0 ? got.live() : live.size() + indexOf(locs, got);
            long w = index0 < live.size() + locs.size() && index0 >= 0 ? weighed[index0] : -2;
            if (w >>> 32 != least >>> 32) {
                mismatches.add(at + ": nearest chose " + got + " weighing " + (w >>> 32) + ", the least weighs "
                        + (least >>> 32));
            } else if (got.time().deci() != (int) w) {
                mismatches.add(at + ": nearest reports " + got.time() + ", its batch price is " + (int) w);
            }

            // Asked again, the kept labels answer: no set is searched a second time.
            long builds = near.targetLabelBuilds();
            near.nearest(account, from, live, st, NavPolicy.DEFAULT);
            if (near.targetLabelBuilds() != builds) {
                mismatches.add(at + ": a repeated ask built its labels again");
            }
        }
        assertEquals(List.of(), mismatches);
        assertEquals(true, asked >= 24, "enough asks landed on standable tiles: " + asked);
        assertEquals(
                true,
                staticWins > 0 && liveWins > 0 && boxedAsks > 0,
                "static wins " + staticWins + ", live wins " + liveWins + ", asks with locs excepted " + boxedAsks);
    }

    @Test
    void nearestIsTheEstimateOfTheAnyOfEveryCandidate() {
        ServerData env = RealDefs.serverData();
        Pathfinder cachedPf = RealDefs.cachedPathfinder();
        Engine near = (Engine) cachedPf.nav();
        Engine plain = (Engine) RealDefs.pathfinder().nav();
        CollisionMap map = env.collisionMap();
        StaticLocIndex index = map.staticLocIndex();
        int[] ids = idsWithCount(index, true, 5, 30, 2);
        List<String> mismatches = new ArrayList<>();
        SplittableRandom rnd = new SplittableRandom(23);
        int asked = 0;
        for (int trial = 0; trial < 16; trial++) {
            int[] one = {ids[trial % ids.length]};
            List<Candidate> statics = candidates(map, index, Targetable.Kind.SCENERY, one);
            Point from = trial % 2 == 0
                    ? standableNear(
                            map, rnd, statics.get(rnd.nextInt(statics.size())).loc())
                    : standable(map, rnd);
            if (from == null) {
                continue;
            }
            AccountFacts account = PrereqProgramTest.account(
                            env, rnd, cachedPf.eligibilityItemIds(), cachedPf.eligibilityQuestIds())
                    .at(from.x(), from.y());
            List<Area> tiles = new ArrayList<>();
            for (Candidate c : statics) {
                tiles.addAll(((Dest.AnyOf) c.dest()).options());
            }
            TravelTime whole = plain.estimate(account, from, Dest.anyOf(tiles.toArray(new Area[0])), NavPolicy.DEFAULT);
            Nearest got = near.nearest(
                    account,
                    from,
                    List.of(),
                    new StaticTargets(Targetable.Kind.SCENERY, one, ChunkBox.NONE, new long[0]),
                    NavPolicy.DEFAULT);
            asked++;
            String at = "trial " + trial + " id " + one[0] + " from " + from;
            if (!whole.isReachable()) {
                if (got != null) {
                    mismatches.add(at + ": the AnyOf is unreachable, nearest said " + got);
                }
                continue;
            }
            if (got == null) {
                mismatches.add(at + ": nearest found nothing, the AnyOf estimates " + whole);
                continue;
            }
            if (!got.time().equals(whole)) {
                mismatches.add(at + ": nearest " + got + ", the AnyOf estimates " + whole);
            }
            Candidate winner = null;
            for (Candidate c : statics) {
                if (c.loc().x() == got.x() && c.loc().z() == got.y()) {
                    winner = c;
                }
            }
            TravelTime own = winner == null
                    ? TravelTime.NO_ROUTE
                    : plain.estimate(account, from, winner.dest(), NavPolicy.DEFAULT);
            if (!own.equals(got.time())) {
                mismatches.add(at + ": the winner's own estimate is " + own + ", nearest reported " + got.time());
            }
        }
        assertEquals(List.of(), mismatches);
        assertEquals(true, asked >= 10, "enough asks landed on standable tiles: " + asked);
    }

    private static int indexOf(List<Loc> locs, Nearest n) {
        for (int i = 0; i < locs.size(); i++) {
            Loc l = locs.get(i);
            if (l.id() == n.id() && l.x() == n.x() && l.z() == n.y() && l.dir() == n.dir()) {
                return i;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static int[] idsWithCount(StaticLocIndex index, boolean scenery, int min, int max, int count) {
        int[] out = new int[count];
        int n = 0;
        for (int id = 0; id < 2000 && n < count; id++) {
            int[] handles = scenery ? index.sceneryWithId(id) : index.boundariesWithId(id);
            int standing = 0;
            for (int h : handles) {
                if (scenery ? index.sceneryStands(h) : index.boundaryStands(h)) {
                    standing++;
                }
            }
            if (standing >= min && standing <= max) {
                out[n++] = id;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static List<Candidate> candidates(CollisionMap map, StaticLocIndex index, Targetable.Kind kind, int[] ids) {
        boolean scenery = kind == Targetable.Kind.SCENERY;
        List<Candidate> out = new ArrayList<>();
        for (int id : ids) {
            for (int h : scenery ? index.sceneryWithId(id) : index.boundariesWithId(id)) {
                if (!(scenery ? index.sceneryStands(h) : index.boundaryStands(h))) {
                    continue;
                }
                Loc loc = scenery
                        ? new Loc(kind, id, index.sceneryX(h), index.sceneryY(h), index.sceneryDir(h))
                        : new Loc(kind, id, index.boundaryX(h), index.boundaryY(h), index.boundaryDir(h));
                List<Point> tiles = map.engageTiles(loc, Overlays.NONE);
                if (!tiles.isEmpty()) {
                    out.add(new Candidate(loc, Dest.anyOf(tiles.toArray(new Area[0]))));
                }
            }
        }
        return out;
    }

    private static Point standableNear(CollisionMap map, SplittableRandom rnd, Loc loc) {
        return standableAround(map, rnd, Point.of(loc.x(), loc.z()), 12);
    }

    private static Point standableAround(CollisionMap map, SplittableRandom rnd, Point c, int r) {
        for (int tries = 0; tries < 200; tries++) {
            int x = c.x() - r + rnd.nextInt(2 * r + 1);
            int y = c.y() - r + rnd.nextInt(2 * r + 1);
            if (map.inBounds(x, y) && !map.fullyBlocked(x, y)) {
                return Point.of(x, y);
            }
        }
        return null;
    }

    private static Point standable(CollisionMap map, SplittableRandom rnd) {
        for (int tries = 0; tries < 2000; tries++) {
            int x = 50 + rnd.nextInt(650);
            int y = 420 + rnd.nextInt(480);
            if (map.inBounds(x, y) && !map.fullyBlocked(x, y)) {
                return Point.of(x, y);
            }
        }
        return null;
    }
}
