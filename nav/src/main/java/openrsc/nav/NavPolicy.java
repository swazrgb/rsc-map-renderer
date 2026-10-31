package openrsc.nav;

import java.util.HashMap;
import java.util.Map;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;

/**
 * The caller's choices for a plan, beside the account's own facts. A policy is a value: equal
 * policies plan alike, and the planner caches by value.
 *
 * <pre>{@code
 * NavPolicy.DEFAULT.prohibit(DOOR_MINING_GUILD);              // neither way
 * NavPolicy.DEFAULT.cost(DOOR_MINING_GUILD.lowToHigh(), 500); // one way, repriced
 * NavPolicy.DEFAULT.cost(bank, 200);                          // a stop at one bank
 * }</pre>
 *
 * @param teleportSpells whether routes may cast teleport spells
 * @param teleportJewellery whether routes may rub teleport jewellery
 * @param costOverrides transport key or bank name to its cost in deci-ticks, or {@link #PROHIBITIVE}
 *     or {@link #LIFTED}; built with {@link #cost}, {@link #prohibit} and {@link #lift}. An unknown
 *     key makes a plan under this policy throw {@link IllegalArgumentException}.
 * @param assumeAllEligible whether every transport's gate is taken as passed; see {@link #withAllEligible()}
 * @param avoidSkulled whether a follower should steer round skulled players; the planner does not read it
 * @param berth which NPCs a follower keeps its distance from
 */
public record NavPolicy(
        DangerAvoidance danger,
        boolean teleportSpells,
        boolean teleportJewellery,
        Map<String, Integer> costOverrides,
        boolean assumeAllEligible,
        boolean avoidSkulled,
        BerthPolicy berth) {

    /** Null {@code danger}, {@code costOverrides} and {@code berth} take the {@link #DEFAULT} values. */
    public NavPolicy {
        danger = danger == null ? DangerAvoidance.WEAK_ACCOUNTS : danger;
        costOverrides = costOverrides == null ? Map.of() : costOverrides;
        berth = berth == null ? BerthPolicy.all() : berth;
    }

    /** Which accounts' routes are charged {@link Planner#dangerCharge} for the danger they walk through. */
    public enum DangerAvoidance {
        /** None. */
        OFF,
        /** Every account. */
        ON,
        /**
         * An account whose base Defense is at most {@link #DANGER_MAX_DEFENSE}. An NPC that aggroes a
         * sturdier account costs it a short fight, which is not worth a detour.
         */
        WEAK_ACCOUNTS;

        public static DangerAvoidance of(boolean avoid) {
            return avoid ? ON : OFF;
        }
    }

    /** The highest base Defense {@link DangerAvoidance#WEAK_ACCOUNTS} charges for danger. */
    public static final int DANGER_MAX_DEFENSE = 40;

    /** Whether routes for an account of {@code stats} are charged for danger under this policy. */
    public boolean chargesDanger(Stats stats) {
        return switch (danger) {
            case OFF -> false;
            case ON -> true;
            case WEAK_ACCOUNTS -> stats.baseLevel(Skill.DEFENSE) <= DANGER_MAX_DEFENSE;
        };
    }

    /**
     * The policy an account of {@code stats} walks with: berthing only while its base Defense is at most
     * {@link #DANGER_MAX_DEFENSE}, the accounts an aggressive NPC can seriously hurt; past that it walks through.
     */
    public NavPolicy forWalker(Stats stats) {
        return stats.baseLevel(Skill.DEFENSE) <= DANGER_MAX_DEFENSE || !berth.berthsAnything()
                ? this
                : withBerth(BerthPolicy.none());
    }

    /** A cost override that keeps routes off the transport altogether. */
    public static final int PROHIBITIVE = Integer.MAX_VALUE;

    /** A cost override that opens the transport whatever its gate says, at its own cost; see {@link #lift}. */
    public static final int LIFTED = Integer.MIN_VALUE;

    public static final NavPolicy DEFAULT =
            new NavPolicy(DangerAvoidance.WEAK_ACCOUNTS, false, false, Map.of(), false, false, BerthPolicy.all());

    /** Without skulled-player avoidance, berthing every NPC ({@link BerthPolicy#all()}). */
    public NavPolicy(
            DangerAvoidance danger,
            boolean teleportSpells,
            boolean teleportJewellery,
            Map<String, Integer> costOverrides,
            boolean assumeAllEligible) {
        this(danger, teleportSpells, teleportJewellery, costOverrides, assumeAllEligible, false, BerthPolicy.all());
    }

    public NavPolicy withDanger(DangerAvoidance choice) {
        return new NavPolicy(
                choice, teleportSpells, teleportJewellery, costOverrides, assumeAllEligible, avoidSkulled, berth);
    }

    /**
     * This policy with every transport's gate taken as passed, for asking what the best possible
     * route is. Fares are still paid from the account's pack. Unlike an account that owns
     * everything, it also opens gates that forbid carrying something or want a quest mid-way.
     */
    public NavPolicy withAllEligible() {
        return new NavPolicy(danger, teleportSpells, teleportJewellery, costOverrides, true, avoidSkulled, berth);
    }

    public NavPolicy withBerth(BerthPolicy choice) {
        return new NavPolicy(
                danger, teleportSpells, teleportJewellery, costOverrides, assumeAllEligible, avoidSkulled, choice);
    }

    public NavPolicy withAvoidSkulled(boolean avoid) {
        return new NavPolicy(danger, teleportSpells, teleportJewellery, costOverrides, assumeAllEligible, avoid, berth);
    }

    public NavPolicy withTeleportJewellery(boolean allow) {
        if (allow == teleportJewellery) {
            return this;
        }
        return new NavPolicy(danger, teleportSpells, allow, costOverrides, assumeAllEligible, avoidSkulled, berth);
    }

    /**
     * This policy with {@code transport} costing {@code cost} deci-ticks instead of its own.
     *
     * @throws IllegalArgumentException for a negative cost, or {@link #PROHIBITIVE}
     */
    public NavPolicy cost(TransportRef transport, int cost) {
        if (cost < 0 || cost == PROHIBITIVE) {
            throw new IllegalArgumentException("cost " + cost + " for " + transport.key()
                    + " — a cost is a non-negative price; use prohibit() to fence a transport out");
        }
        return withOverride(transport, cost);
    }

    /**
     * This policy with a stop at {@code bank} costing {@code cost} deci-ticks instead of its own.
     *
     * @throws IllegalArgumentException for a cost {@link BankSite#validCost} refuses
     */
    public NavPolicy cost(BankSite bank, int cost) {
        if (!BankSite.validCost(cost)) {
            throw new IllegalArgumentException("cost " + cost + " for " + bank.name() + " — " + BankSite.COST_RULE);
        }
        return withOverride(bank.name(), cost);
    }

    /** This policy with routes kept off {@code transport}. */
    public NavPolicy prohibit(TransportRef transport) {
        return withOverride(transport, PROHIBITIVE);
    }

    /**
     * This policy with {@code transport}'s gate taken as passed whatever the account's facts: the
     * one-gate {@link #withAllEligible()}, for asking whether that gate is what stands in a trip's
     * way. A teleport the policy has not opted into stays shut, and a prohibit stays.
     */
    public NavPolicy lift(TransportRef transport) {
        Integer stated = costOverrides.get(transport.key());
        return stated != null && stated == PROHIBITIVE ? this : withOverride(transport, LIFTED);
    }

    private NavPolicy withOverride(TransportRef transport, int value) {
        return withOverride(transport.key(), value);
    }

    private NavPolicy withOverride(String key, int value) {
        Map<String, Integer> merged = new HashMap<>(costOverrides);
        merged.put(key, value);
        return new NavPolicy(
                danger, teleportSpells, teleportJewellery, Map.copyOf(merged), assumeAllEligible, avoidSkulled, berth);
    }
}
