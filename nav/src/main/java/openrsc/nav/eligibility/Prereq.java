package openrsc.nav.eligibility;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import openrsc.gamedata.defs.ItemDefs;
import openrsc.nav.eligibility.EligibilityInputs.Fact;

/**
 * A condition on an account, built from {@link Fact}s with and, or and not, that says whether a
 * transport may be taken. It is data rather than an opaque predicate, so a caller can also ask which
 * facts it reads ({@link #facts()}, {@link #inputs()}) and so what would satisfy it.
 *
 * <p>Every factory interns: equal structure gives the same instance, and equality is identity.
 * Instances are immutable and the factories are safe to call from any thread.
 *
 * <p>The evaluators agree with each other: {@link #test} reads the account, {@link #testBanked} the
 * account as a bank stop could leave it, {@link #testFacts} given truth values, and {@link #run} a
 * {@link #compile}d program over a bitset of them.
 */
public final class Prereq {

    private static final int OP_TRUE = 0;
    private static final int OP_FALSE = 1;
    private static final int OP_FACT = 2;
    private static final int OP_NOT_FACT = 3;
    private static final int OP_ALL = 4;
    private static final int OP_ANY = 5;

    private enum Op {
        ALWAYS(OP_TRUE),
        NEVER(OP_FALSE),
        FACT(OP_FACT),
        NOT_FACT(OP_NOT_FACT),
        ALL(OP_ALL),
        ANY(OP_ANY),
        NAMED(-1);

        private final int opcode;

        Op(int opcode) {
            this.opcode = opcode;
        }
    }

    private static final AtomicInteger NEXT_INDEX = new AtomicInteger();
    private static final ConcurrentHashMap<Object, Prereq> INTERN = new ConcurrentHashMap<>();

    public static final Prereq ALWAYS = new Prereq(Op.ALWAYS, "always", null, null, new Fact[0], new Fact[0]);
    public static final Prereq NEVER = new Prereq(Op.NEVER, "never", null, null, new Fact[0], new Fact[0]);

    private final int index;
    private final String description;
    private final Op op;
    private final Fact leaf;
    private final Prereq[] children;
    private final Fact[] facts;
    private final Fact[] negatedFacts;

    private Prereq(Op op, String description, Fact leaf, Prereq[] children, Fact[] facts, Fact[] negatedFacts) {
        this.index = NEXT_INDEX.getAndIncrement();
        this.description = description;
        this.op = op;
        this.leaf = leaf;
        this.children = children;
        this.facts = facts;
        this.negatedFacts = negatedFacts;
    }

    /** Holds when {@code f} does. */
    public static Prereq fact(Fact f) {
        return INTERN.computeIfAbsent(
                List.of("fact", f), k -> new Prereq(Op.FACT, f.describe(), f, null, new Fact[] {f}, new Fact[0]));
    }

    /**
     * Holds when {@code f} does not. {@code f} still counts among {@link #facts()}, and is also one of
     * the {@link #negatedFacts()}.
     */
    public static Prereq notFact(Fact f) {
        return INTERN.computeIfAbsent(
                List.of("not", f),
                k -> new Prereq(Op.NOT_FACT, "not (" + f.describe() + ")", f, null, new Fact[] {f}, new Fact[] {f}));
    }

    /** Holds when every one of {@code ps} does, and so when {@code ps} is empty. */
    public static Prereq all(Prereq... ps) {
        Prereq[] children = ps.clone();
        return INTERN.computeIfAbsent(
                compositeKey("all", children),
                k -> new Prereq(
                        Op.ALL,
                        joinedDescription(children, " and ", Op.ANY),
                        null,
                        children,
                        factUnion(children, false),
                        factUnion(children, true)));
    }

    /** Holds when at least one of {@code ps} does; never when {@code ps} is empty. */
    public static Prereq any(Prereq... ps) {
        Prereq[] children = ps.clone();
        return INTERN.computeIfAbsent(
                compositeKey("any", children),
                k -> new Prereq(
                        Op.ANY,
                        joinedDescription(children, " or ", Op.ALL),
                        null,
                        children,
                        factUnion(children, false),
                        factUnion(children, true)));
    }

    /**
     * {@code gate} under a name, such as "a pickaxe" over six item facts. It evaluates, compiles and
     * declares exactly as {@code gate} does; only {@link #description()}, {@link #display} and
     * {@link #namedParts()} see the name.
     */
    public static Prereq named(String label, Prereq gate) {
        return INTERN.computeIfAbsent(
                List.of("named", label, gate.index),
                k -> new Prereq(
                        Op.NAMED, label, null, new Prereq[] {gate}, gate.facts.clone(), gate.negatedFacts.clone()));
    }

    /**
     * The condition in words, such as "agility ≥ 20 and Biohazard complete", naming items by their
     * {@link openrsc.gamedata.data.ItemId} constant. It is the same for every world.
     */
    public String description() {
        return description;
    }

    /** {@link #description()} with items named as {@code defs}' world names them. */
    public String display(ItemDefs defs) {
        return switch (op) {
            case FACT -> leaf.display(defs);
            case NOT_FACT -> "not (" + leaf.display(defs) + ")";
            case ALL -> joinedDisplay(children, " and ", Op.ANY, defs);
            case ANY -> joinedDisplay(children, " or ", Op.ALL, defs);
            case NAMED -> description;
            case ALWAYS, NEVER -> description;
        };
    }

    @Override
    public String toString() {
        return description;
    }

    /**
     * Whether the account in {@code ctx} meets this condition. Children are evaluated left to right
     * and short-circuit like {@code &&} and {@code ||}.
     */
    public boolean test(RouteContext ctx) {
        return switch (op) {
            case ALWAYS -> true;
            case NEVER -> false;
            case FACT -> leaf.test(ctx);
            case NOT_FACT -> !leaf.test(ctx);
            case ALL -> {
                for (Prereq p : children) {
                    if (!p.test(ctx)) {
                        yield false;
                    }
                }
                yield true;
            }
            case ANY -> {
                for (Prereq p : children) {
                    if (p.test(ctx)) {
                        yield true;
                    }
                }
                yield false;
            }
            case NAMED -> children[0].test(ctx);
        };
    }

    /**
     * Whether the account could meet this condition after a bank stop: {@link #test} with
     * {@link Fact#testBanked} at the leaves.
     */
    public boolean testBanked(RouteContext ctx) {
        return switch (op) {
            case ALWAYS -> true;
            case NEVER -> false;
            case FACT -> leaf.testBanked(ctx);
            case NOT_FACT -> !leaf.testBanked(ctx);
            case ALL -> {
                for (Prereq p : children) {
                    if (!p.testBanked(ctx)) {
                        yield false;
                    }
                }
                yield true;
            }
            case ANY -> {
                for (Prereq p : children) {
                    if (p.testBanked(ctx)) {
                        yield true;
                    }
                }
                yield false;
            }
            case NAMED -> children[0].testBanked(ctx);
        };
    }

    /**
     * Evaluates this condition with {@code truth} answering each leaf fact. It agrees with
     * {@link #test} whenever {@code truth} agrees with {@link Fact#test} on every fact this reads.
     */
    public boolean testFacts(Predicate<Fact> truth) {
        return switch (op) {
            case ALWAYS -> true;
            case NEVER -> false;
            case FACT -> truth.test(leaf);
            case NOT_FACT -> !truth.test(leaf);
            case ALL -> {
                for (Prereq p : children) {
                    if (!p.testFacts(truth)) {
                        yield false;
                    }
                }
                yield true;
            }
            case ANY -> {
                for (Prereq p : children) {
                    if (p.testFacts(truth)) {
                        yield true;
                    }
                }
                yield false;
            }
            case NAMED -> children[0].testFacts(truth);
        };
    }

    /**
     * This condition as a postfix program for {@link #run}, with each fact numbered by
     * {@code indexOf}. A leaf is {@code (opcode, fact number)}; a composite is
     * {@code (opcode, child count)} after its children.
     */
    public int[] compile(ToIntFunction<Fact> indexOf) {
        IntArrayList out = new IntArrayList();
        emit(out, indexOf);
        return out.toIntArray();
    }

    private void emit(IntArrayList out, ToIntFunction<Fact> indexOf) {
        switch (op) {
            case ALWAYS, NEVER -> out.add(op.opcode);
            case FACT, NOT_FACT -> {
                out.add(op.opcode);
                out.add(indexOf.applyAsInt(leaf));
            }
            case ALL, ANY -> {
                for (Prereq p : children) {
                    p.emit(out, indexOf);
                }
                out.add(op.opcode);
                out.add(children.length);
            }
            case NAMED -> children[0].emit(out, indexOf);
        }
    }

    /**
     * Runs a {@link #compile}d program, bit {@code i} of {@code factBits} being the truth of fact
     * number {@code i}. {@code stack} is scratch space, at least as long as {@code program}; with it
     * owned by the caller, the call allocates nothing.
     */
    public static boolean run(int[] program, long[] factBits, boolean[] stack) {
        int sp = 0;
        int pc = 0;
        while (pc < program.length) {
            switch (program[pc++]) {
                case OP_TRUE -> stack[sp++] = true;
                case OP_FALSE -> stack[sp++] = false;
                case OP_FACT -> {
                    int f = program[pc++];
                    stack[sp++] = (factBits[f >>> 6] & (1L << f)) != 0L;
                }
                case OP_NOT_FACT -> {
                    int f = program[pc++];
                    stack[sp++] = (factBits[f >>> 6] & (1L << f)) == 0L;
                }
                case OP_ALL -> {
                    int n = program[pc++];
                    boolean all = true;
                    for (int i = 0; i < n; i++) {
                        all &= stack[--sp];
                    }
                    stack[sp++] = all;
                }
                case OP_ANY -> {
                    int n = program[pc++];
                    boolean any = false;
                    for (int i = 0; i < n; i++) {
                        any |= stack[--sp];
                    }
                    stack[sp++] = any;
                }
                default -> throw new AssertionError(program[pc - 1]);
            }
        }
        return stack[0];
    }

    /** Every fact this condition reads, without duplicates, in declaration order. */
    public Fact[] facts() {
        return facts.clone();
    }

    /** The facts this condition needs to be false: those under a {@link #notFact}. */
    public Fact[] negatedFacts() {
        return negatedFacts.clone();
    }

    public boolean isNamed() {
        return op == Op.NAMED;
    }

    /**
     * The {@link #named} conditions this one is built from, itself included, outermost first and
     * without duplicates; empty when it names nothing.
     */
    public List<Prereq> namedParts() {
        List<Prereq> out = new ArrayList<>(2);
        collectNamed(out);
        return out;
    }

    private void collectNamed(List<Prereq> out) {
        if (op == Op.NAMED && !out.contains(this)) {
            out.add(this);
        }
        if (children != null) {
            for (Prereq p : children) {
                p.collectNamed(out);
            }
        }
    }

    /** The facts this condition reads, as {@link EligibilityInputs}; {@link EligibilityInputs#NONE} for none. */
    public EligibilityInputs inputs() {
        return facts.length == 0 ? EligibilityInputs.NONE : EligibilityInputs.of(facts, negatedFacts);
    }

    private static Object compositeKey(String op, Prereq[] children) {
        List<Integer> ids = new ArrayList<>(children.length);
        for (Prereq p : children) {
            ids.add(p.index);
        }
        return List.of(op, List.copyOf(ids));
    }

    private static String joinedDescription(Prereq[] children, String sep, Op parenthesize) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.length; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            boolean parens = children[i].op == parenthesize;
            sb.append(parens ? "(" : "").append(children[i].description).append(parens ? ")" : "");
        }
        return sb.toString();
    }

    private static String joinedDisplay(Prereq[] children, String sep, Op parenthesize, ItemDefs defs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < children.length; i++) {
            if (i > 0) {
                sb.append(sep);
            }
            boolean parens = children[i].op == parenthesize;
            sb.append(parens ? "(" : "").append(children[i].display(defs)).append(parens ? ")" : "");
        }
        return sb.toString();
    }

    private static Fact[] factUnion(Prereq[] ps, boolean negated) {
        LinkedHashSet<Fact> out = new LinkedHashSet<>();
        for (Prereq p : ps) {
            for (Fact f : (negated ? p.negatedFacts : p.facts)) {
                out.add(f);
            }
        }
        return out.toArray(new Fact[0]);
    }
}
