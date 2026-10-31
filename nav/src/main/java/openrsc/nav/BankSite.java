package openrsc.nav;

import openrsc.gamedata.geometry.Point;

/**
 * A bank the planner may route a stop to.
 *
 * @param name the key a {@link NavPolicy} re-prices a stop here by
 * @param tile where the stop is planned
 * @param cost what a stop adds to the walk, in deci-ticks (10 to a tick), from 0 to {@link #MAX_COST}
 */
public record BankSite(String name, Point tile, int cost) {

    /**
     * The dearest a stop may be priced, 100,000 ticks: low enough that a route's int weight cannot
     * overflow with two stops on it.
     */
    public static final int MAX_COST = 1_000_000;

    public BankSite {
        if (!validCost(cost)) {
            throw new IllegalArgumentException("bank " + name + " costs " + cost + " — " + COST_RULE);
        }
    }

    public static final String COST_RULE = "a bank stop's cost is a price from 0 to " + MAX_COST + " deci-ticks";

    public static boolean validCost(int cost) {
        return cost >= 0 && cost <= MAX_COST;
    }
}
