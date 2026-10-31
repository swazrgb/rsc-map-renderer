package openrsc.nav.probe;

import java.util.Arrays;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner;

/**
 * The engine's danger field as one account of a given combat level sees it, read tile by tile for
 * display. Values are raw penalties: not scaled by any avoidance policy, and zero wherever no NPC
 * would be aggressive to that account.
 */
public final class NavDangerExport {

    private final Planner nav;
    private final AccountFacts facts;
    private final int combatLevel;

    private NavDangerExport(Planner nav, AccountFacts facts, int combatLevel) {
        this.nav = nav;
        this.facts = facts;
        this.combatLevel = combatLevel;
    }

    /**
     * An export for an account of {@code combatLevel}, or of the lowest level above it that equal
     * melee stats reach; when none does, of an account with every skill at 99.
     */
    public static NavDangerExport build(Pathfinder pathfinder, int combatLevel) {
        return new NavDangerExport(
                pathfinder.nav(), factsAtCombatLevel(pathfinder.serverData(), combatLevel), combatLevel);
    }

    public int combatLevel() {
        return combatLevel;
    }

    /** The raw penalty at tile {@code (x, absZ)}, {@code absZ} counting every floor; 0 for none. */
    public int rawPenaltyAt(int x, int absZ) {
        return nav.dangerAt(facts, NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.ON), x, absZ);
    }

    /** The largest raw penalty any tile can carry, for normalising a gradient. */
    public int rawCeiling() {
        return nav.dangerCeiling();
    }

    /**
     * One floor's danger as parallel columns, one row per tile with a penalty above zero: {@code z} is
     * floor-local, {@code v} the raw penalty and {@code ceiling} the {@link NavDangerExport#rawCeiling()}.
     */
    public record FloorTiles(int floor, int combatLevel, int ceiling, int[] x, int[] z, int[] v) {}

    public FloorTiles tiles(int floor) {
        int floorBase = floor * CollisionMap.FLOOR_HEIGHT;
        int n = 0;
        int[] x = new int[1024];
        int[] z = new int[1024];
        int[] v = new int[1024];
        for (int zLocal = 0; zLocal < CollisionMap.FLOOR_HEIGHT; zLocal++) {
            for (int tx = 0; tx < CollisionMap.WIDTH; tx++) {
                int p = rawPenaltyAt(tx, floorBase + zLocal);
                if (p <= 0) {
                    continue;
                }
                if (n == x.length) {
                    x = Arrays.copyOf(x, n * 2);
                    z = Arrays.copyOf(z, n * 2);
                    v = Arrays.copyOf(v, n * 2);
                }
                x[n] = tx;
                z[n] = zLocal;
                v[n] = p;
                n++;
            }
        }
        return new FloorTiles(
                floor, combatLevel, rawCeiling(), Arrays.copyOf(x, n), Arrays.copyOf(z, n), Arrays.copyOf(v, n));
    }

    static AccountFacts factsAtCombatLevel(ServerData defs, int cl) {
        MutableStats stats = new MutableStats();
        for (int base = 1; base <= 99; base++) {
            // Combat level only rises with the melee stats, so the first base that reaches cl is the lowest.
            for (Skill sk : new Skill[] {Skill.ATTACK, Skill.DEFENSE, Skill.STRENGTH, Skill.HITS}) {
                stats.setSkill(sk.ordinal(), base, base, 0);
            }
            AccountFacts probe = new AccountFacts.Snapshot(
                    defs,
                    new MutableInventory().snapshot(),
                    new MutableBank().snapshot(),
                    stats.snapshot(),
                    new MutableQuests().snapshot(),
                    Point.of(0, 0));
            if (probe.stats().combatLevel() >= cl) {
                return probe;
            }
        }
        MutableStats maxed = new MutableStats();
        for (Skill sk : Skill.values()) {
            maxed.setSkill(sk.ordinal(), 99, 99, 0);
        }
        return new AccountFacts.Snapshot(
                defs,
                new MutableInventory().snapshot(),
                new MutableBank().snapshot(),
                maxed.snapshot(),
                new MutableQuests().snapshot(),
                Point.of(0, 0));
    }
}
