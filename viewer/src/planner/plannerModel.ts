import type {PlannerInputs, PlannerThreshold} from "./plannerApi";

/** A new account's level in every skill but Hits. */
export const FRESH_LEVEL = 1;
export const FRESH_HITS = 10;

const COMBAT = ["ATTACK", "DEFENSE", "STRENGTH", "HITS", "RANGED", "PRAYER", "MAGIC"] as const;

export type Levels = Record<string, number>;

/** Every skill the gates read, plus the combat skills, at a new account's level. */
export function freshLevels(inputs: PlannerInputs | null): Levels {
    const out: Levels = {};
    const names = new Set<string>(inputs?.skills.map((s) => s.skill) ?? []);
    for (const c of COMBAT) names.add(c);
    for (const name of names) out[name] = name === "HITS" ? FRESH_HITS : FRESH_LEVEL;
    return out;
}

function lvl(levels: Levels, skill: string): number {
    const v = levels[skill] as number | undefined;
    if (v != null) return v;
    return skill === "HITS" ? FRESH_HITS : FRESH_LEVEL;
}

/**
 * The server's player combat level, with ranged, magic and prayer all counted. A skill missing from
 * `levels` is at a new account's level.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L424 Formulae.getCombatLevel
 */
export function combatLevelOf(levels: Levels): number {
    const att = lvl(levels, "ATTACK");
    const str = lvl(levels, "STRENGTH");
    const def = lvl(levels, "DEFENSE");
    const hits = lvl(levels, "HITS");
    const range = lvl(levels, "RANGED");
    const pray = lvl(levels, "PRAYER");
    const magic = lvl(levels, "MAGIC");
    if (att + def + str + hits === 0) return 0;
    const attack = att + str;
    const defense = def + hits;
    const mage = (pray + magic) / 8;
    const level = attack < range * 1.5 ? (2 * defense + 3 * range) / 8 + mage : (attack + defense) / 4 + mage;
    return Math.floor(level);
}

/** The lowest combat level `levels` allow: attack, strength, defense and hits at a new account's. */
export function minCombatLevel(levels: Levels): number {
    return combatLevelOf({
        ...levels,
        ATTACK: FRESH_LEVEL,
        STRENGTH: FRESH_LEVEL,
        DEFENSE: FRESH_LEVEL,
        HITS: FRESH_HITS,
    });
}

/**
 * `levels` with attack, strength, defense and hits set so the combat level reaches `target`, or as
 * close below it as 99 allows. Raises defense and hits together and leaves every other skill as given.
 */
export function statsForCombatLevel(levels: Levels, target: number): Levels {
    const floor = minCombatLevel(levels);
    const out: Levels = {
        ...levels,
        ATTACK: FRESH_LEVEL,
        STRENGTH: FRESH_LEVEL,
        DEFENSE: FRESH_LEVEL,
        HITS: FRESH_HITS,
    };
    if (target <= floor) return out;
    for (let i = 0; i < 99 && combatLevelOf(out) < target; i++) {
        if (out.DEFENSE < 99) out.DEFENSE += 1;
        if (combatLevelOf(out) >= target) break;
        if (out.HITS < 99) out.HITS += 1;
        if (out.DEFENSE >= 99 && out.HITS >= 99) break;
    }
    return out;
}

/** The levels a skill slider stops at: 1, 99 and every gate breakpoint, ascending. */
export function sliderStops(bps: PlannerThreshold[]): number[] {
    const stops = new Set<number>([FRESH_LEVEL, SKILL_MAX]);
    for (const b of bps) stops.add(b.at);
    return [...stops].sort((a, b) => a - b);
}

/** The stop nearest `value`. */
export function snapNearest(value: number, stops: number[]): number {
    let best = stops[0] ?? value;
    for (const s of stops) {
        if (Math.abs(s - value) < Math.abs(best - value)) best = s;
    }
    return best;
}

/** The highest stop at or below `value`. */
export function snapDown(value: number, stops: number[]): number {
    let out = stops[0] ?? FRESH_LEVEL;
    for (const s of stops) if (s <= value) out = s;
    return out;
}

export const SKILL_MIN = 1;
export const SKILL_MAX = 99;

export function nextStop(value: number, stops: number[]): number | null {
    for (const s of stops) if (s > value) return s;
    return null;
}

/** The levels a plan request sends: {@link statsForCombatLevel} of `gateLevels` at `combatLevel`. */
export function requestLevels(gateLevels: Levels, combatLevel: number): Levels {
    return statsForCombatLevel(gateLevels, combatLevel);
}
