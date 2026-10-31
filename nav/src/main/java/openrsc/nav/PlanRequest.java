package openrsc.nav;

import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;

/**
 * One question for {@link Planner#plan}: take {@code account}, standing on {@code from}, to
 * {@code dest}, arriving with a pack {@code loadout} accepts, under {@code policy}. A {@link Route}
 * does not repeat any of this, so a caller keeps the request beside the route it answers.
 */
public record PlanRequest(AccountFacts account, Point from, Dest dest, LoadoutSpec loadout, NavPolicy policy) {}
