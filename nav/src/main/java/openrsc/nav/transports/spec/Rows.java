package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;

/**
 * The vocabulary registry rows are written in, for {@code import static}: tiles, gates, and the
 * options a row factory takes. Each factory accepts the options its mechanic can honour and throws
 * {@link IllegalArgumentException} on any other.
 */
public final class Rows {

    private Rows() {}

    public sealed interface Option
            permits ClickAt,
                    When,
                    Pays,
                    Answers,
                    SecondCommand,
                    AwaitsDialog,
                    Sequenced,
                    Note,
                    UsesItem,
                    Approach,
                    Spawned,
                    OpenedWith,
                    LandsAt,
                    ArmCost,
                    CanKillAt {}

    /** The object stands on {@code tile}, not on the tile it is clicked from. */
    public record ClickAt(Point tile) implements Option {}

    /** Only for an account {@code gate} admits. */
    public record When(Prereq gate) implements Option {}

    /** {@code count} of {@code itemId}: carrying them is part of the gate, and the crossing spends them. */
    public record Pays(int itemId, int count) implements Option {}

    /** The answer to one menu: the option matching any of {@code keywords}. One per menu, in order. */
    public record Answers(String... keywords) implements Option {}

    /** The object's second command, not its first. */
    public record SecondCommand() implements Option {}

    /** The click starts a conversation that runs before the player is moved; it is not clicked again. */
    public record AwaitsDialog() implements Option {}

    /** The menus repeat the same options, so menu N is answered by the Nth {@link Answers}. */
    public record Sequenced() implements Option {}

    /** A caveat for route listings that the gate cannot express. */
    public record Note(String text) implements Option {}

    /**
     * The crossing is fired by using {@code itemId} on the object, which spends {@code spend} of it (0:
     * none). Carrying one use's worth is part of the gate.
     */
    public record UsesItem(int itemId, int spend) implements Option {}

    /**
     * Only entries inside these inclusive bounds fire this arm, for an object whose landing depends on
     * the side it is approached from. Several intersect.
     */
    public record Approach(int xMin, int xMax, int yMin, int yMax) implements Option {}

    /** The landscape does not place the object; the handler spawns it, facing {@code direction}. */
    public record Spawned(int direction) implements Option {}

    /**
     * A locked door's key: an arm whose gate asks for {@code itemId} uses it on the door, and keeps it.
     * Any other arm opens with a click.
     */
    public record OpenedWith(int itemId) implements Option {}

    /** Where a door's arm lands, when its handler moves the player beyond the far flank. */
    public record LandsAt(boolean lowToHigh, Point tile) implements Option {}

    /** One arm of a door at {@code cost} instead of {@link Door#COST}. */
    public record ArmCost(boolean lowToHigh, int cost) implements Option {}

    /**
     * One use can kill a player on {@code hits} current Hits or fewer: a failed roll, a fall or a trap
     * at its worst. Folded into the gate as {@link #survives}.
     */
    public record CanKillAt(int hits) implements Option {}

    /** Where the player stands to fire the crossing. */
    public static Point from(int x, int z) {
        return Point.of(x, z);
    }

    /** Where the server puts the player. */
    public static Point to(int x, int z) {
        return Point.of(x, z);
    }

    public static List<Point> tiles(Point... tiles) {
        return List.of(tiles);
    }

    /**
     * A handler's test of the player's own coordinate against an absolute bound, as in
     * {@code player.getX() <= 425}, which is {@code playerXAtMost(425)}.
     */
    public record PlayerSide(boolean onX, int bound, boolean atMost) {
        /** Whether the test holds for a player on {@code tile}. */
        public boolean holds(Point tile) {
            int v = onX ? tile.x() : tile.y();
            return atMost ? v <= bound : v >= bound;
        }
    }

    public static PlayerSide playerXAtMost(int x) {
        return new PlayerSide(true, x, true);
    }

    public static PlayerSide playerXAtLeast(int x) {
        return new PlayerSide(true, x, false);
    }

    public static PlayerSide playerYAtMost(int z) {
        return new PlayerSide(false, z, true);
    }

    public static PlayerSide playerYAtLeast(int z) {
        return new PlayerSide(false, z, false);
    }

    @FunctionalInterface
    public interface SidedArm {
        /** The arm fired from {@code from}, or {@code null} where the handler moves nobody from it. */
        TransportSpec arm(Point from, boolean holds);
    }

    /**
     * One arm per tile of {@code from}, made by {@code arm} with whether {@code side} holds there, for
     * a handler that picks the landing by the player's tile. {@code null} arms are left out.
     */
    public static List<TransportSpec> bySide(PlayerSide side, SidedArm arm, Point... from) {
        List<TransportSpec> out = new ArrayList<>(from.length);
        for (Point f : from) {
            TransportSpec a = arm.arm(f, side.holds(f));
            if (a != null) {
                out.add(a);
            }
        }
        return List.copyOf(out);
    }

    public static Option clickAt(int x, int z) {
        return new ClickAt(Point.of(x, z));
    }

    public static Option clickAt(Point tile) {
        return new ClickAt(tile);
    }

    public static Option when(Prereq gate) {
        return new When(gate);
    }

    public static Option pays(int itemId, int count) {
        return new Pays(itemId, count);
    }

    public static Option answers(String... keywords) {
        return new Answers(keywords);
    }

    public static Option secondCommand() {
        return new SecondCommand();
    }

    public static Option awaitsDialog() {
        return new AwaitsDialog();
    }

    public static Option sequenced() {
        return new Sequenced();
    }

    public static Option note(String text) {
        return new Note(text);
    }

    public static Option openedWith(int itemId) {
        return new OpenedWith(itemId);
    }

    public static Option lowToHighLandsAt(int x, int z) {
        return new LandsAt(true, Point.of(x, z));
    }

    public static Option highToLowLandsAt(int x, int z) {
        return new LandsAt(false, Point.of(x, z));
    }

    public static Option highToLowCost(int cost) {
        return new ArmCost(false, cost);
    }

    public static Option canKillAt(int hits) {
        return new CanKillAt(hits);
    }

    /** Current Hits above {@code hits}. */
    public static Prereq survives(int hits) {
        return level(Skill.HITS, hits + 1);
    }

    public static TransportSpec onlyIn(WorldGate world, TransportSpec spec) {
        return new OnlyIn(world, spec);
    }

    /** One constant declared {@code admitted} on the worlds {@code world} admits, {@code otherwise} elsewhere. */
    public static TransportSpec byWorld(WorldGate world, TransportSpec admitted, TransportSpec otherwise) {
        return new ByWorld(world, admitted, otherwise);
    }

    public static Option usesItem(int itemId, int spend) {
        return new UsesItem(itemId, spend);
    }

    public static Option spawned(int direction) {
        return new Spawned(direction);
    }

    /** This arm fires from {@code (x, z)} only. */
    public static Option onlyFrom(int x, int z) {
        return new Approach(x, x, z, z);
    }

    public static Option xAtLeast(int x) {
        return new Approach(x, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static Option xAtMost(int x) {
        return new Approach(Integer.MIN_VALUE, x, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static Option xIn(int min, int max) {
        return new Approach(min, max, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    public static Option zAtLeast(int z) {
        return new Approach(Integer.MIN_VALUE, Integer.MAX_VALUE, z, Integer.MAX_VALUE);
    }

    public static Option zAtMost(int z) {
        return new Approach(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, z);
    }

    public static Option zIn(int min, int max) {
        return new Approach(Integer.MIN_VALUE, Integer.MAX_VALUE, min, max);
    }

    public static Prereq questComplete(QuestId quest) {
        return Prereq.fact(Fact.questComplete(quest.id()));
    }

    /** At {@code stage} or later; a completed quest counts. */
    public static Prereq questAtLeast(QuestId quest, int stage) {
        return Prereq.fact(Fact.questAtLeast(quest.id(), stage));
    }

    public static Prereq questAt(QuestId quest, int stage) {
        return Prereq.fact(Fact.questEquals(quest.id(), stage));
    }

    public static Prereq notQuestAt(QuestId quest, int stage) {
        return Prereq.notFact(Fact.questEquals(quest.id(), stage));
    }

    public static Prereq notQuestComplete(QuestId quest) {
        return Prereq.notFact(Fact.questComplete(quest.id()));
    }

    public static Prereq notQuestAtLeast(QuestId quest, int stage) {
        return Prereq.notFact(Fact.questAtLeast(quest.id(), stage));
    }

    public static Prereq cache(String key) {
        return Prereq.fact(Fact.cacheBool(key, true));
    }

    public static Prereq cacheAtLeast(String key, int min) {
        return Prereq.fact(Fact.cacheIntAtLeast(key, min));
    }

    /**
     * At least {@code level} in the current level, not the base level, as the server's skill checks
     * read it.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L65">WoodcutJungle.handleJungleWoodcut</a>
     */
    public static Prereq level(Skill skill, int level) {
        return Prereq.fact(Fact.skillAtLeast(skill, level));
    }

    public static Prereq carrying(int itemId, int count) {
        return Prereq.fact(Fact.carriedAtLeast(itemId, count));
    }

    public static Prereq wearing(int itemId) {
        return Prereq.fact(Fact.wearing(itemId));
    }

    static Prereq gateOf(Option... options) {
        java.util.List<Prereq> gates = new java.util.ArrayList<>();
        for (Option o : options) {
            if (o instanceof When w) {
                gates.add(w.gate());
            } else if (o instanceof Pays p) {
                gates.add(Prereq.fact(Fact.carriedAtLeast(p.itemId(), p.count())));
            } else if (o instanceof CanKillAt k) {
                gates.add(survives(k.hits()));
            }
        }
        return switch (gates.size()) {
            case 0 -> Prereq.ALWAYS;
            case 1 -> gates.get(0);
            default -> Prereq.all(gates.toArray(Prereq[]::new));
        };
    }

    static Approach approachOf(Option... options) {
        int xMin = Integer.MIN_VALUE;
        int xMax = Integer.MAX_VALUE;
        int yMin = Integer.MIN_VALUE;
        int yMax = Integer.MAX_VALUE;
        for (Option o : options) {
            if (o instanceof Approach a) {
                xMin = Math.max(xMin, a.xMin());
                xMax = Math.min(xMax, a.xMax());
                yMin = Math.max(yMin, a.yMin());
                yMax = Math.min(yMax, a.yMax());
            }
        }
        return new Approach(xMin, xMax, yMin, yMax);
    }

    static <T extends Option> T find(Class<T> kind, Option... options) {
        for (Option o : options) {
            if (kind.isInstance(o)) {
                return kind.cast(o);
            }
        }
        return null;
    }

    static Pays paysOf(Option... options) {
        for (Option o : options) {
            if (o instanceof Pays p) {
                return p;
            }
        }
        return null;
    }

    static List<Pays> allPaysOf(Option... options) {
        List<Pays> out = new ArrayList<>();
        for (Option o : options) {
            if (o instanceof Pays p) {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }

    static Point clickAtOf(Option... options) {
        for (Option o : options) {
            if (o instanceof ClickAt c) {
                return c.tile();
            }
        }
        return null;
    }

    static String noteOf(Option... options) {
        for (Option o : options) {
            if (o instanceof Note n) {
                return n.text();
            }
        }
        return null;
    }

    static String[][] answersOf(Option... options) {
        return java.util.Arrays.stream(options)
                .filter(Answers.class::isInstance)
                .map(o -> ((Answers) o).keywords())
                .toArray(String[][]::new);
    }

    static boolean has(Class<? extends Option> kind, Option... options) {
        for (Option o : options) {
            if (kind.isInstance(o)) {
                return true;
            }
        }
        return false;
    }

    static void allowOnly(String mechanic, Option[] options, Class<?>... allowed) {
        for (Option o : options) {
            boolean ok = false;
            for (Class<?> a : allowed) {
                ok |= a.isInstance(o);
            }
            if (!ok) {
                throw new IllegalArgumentException(mechanic + " cannot honour " + o);
            }
        }
    }
}
