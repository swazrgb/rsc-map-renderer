import type {Client} from "./generate";

/** An item to carry: below `min` the camper banks, and at the bank it withdraws up to `target`. */
export interface CampItem {
    id: number;
    min: number;
    target: number;
    wear: boolean;
}

/** What nav's `BobMapCampGenerator` compiles, for a maxed account: spawn server indexes, from which it derives the camp, and the loadout. */
export interface CampRequest {
    targets: number[];
    items: CampItem[];
    food: number[];
    eat: number | null;
    /** Fatigue in percent to sleep at; the generator adds a sleeping bag. Ignored on a world without fatigue. */
    sleep: number | null;
    style: number | null;
    loot: number[];
    bury: number[];
    title: string | null;
}

interface CampRouteTile {
    x: number;
    z: number;
    hop: boolean;
}

/** The recipe, or `error` alone when nothing compiles. */
export interface CampResponse {
    recipe?: string;
    camp?: {x: number; z: number};
    campBox?: {minX: number; minZ: number; maxX: number; maxZ: number};
    bank?: string;
    bankTile?: {x: number; z: number};
    toBank: CampRouteTile[];
    toCamp: CampRouteTile[];
    /** The most a route step can hit for; 0 or missing for none. */
    stepHits?: number;
    /** The world has fatigue, so sleeping applies. */
    fatigue?: boolean;
    warnings: string[];
    error?: string;
}

/** The loadout and fight settings, as the panel edits them. */
export interface CampSettings {
    items: CampItem[];
    food: number[];
    /** Hits to eat at; null follows the proposal, {@link proposedEat}. */
    eat: number | null;
    /** Fatigue in percent to sleep at, 1 to 100, with the sleeping bag the generator adds; null for none. */
    sleep: number | null;
    style: number;
    loot: number[];
    /** Items to pick up and bury. */
    bury: number[];
    client: Client;
}

export const DEFAULT_SETTINGS: CampSettings = {
    items: [],
    food: [],
    eat: null,
    sleep: 50,
    style: 1,
    loot: [],
    bury: [],
    client: "apos",
};

/** Whether `n` is a fatigue percent the generator takes for `sleep`. */
export function isSleep(n: unknown): n is number {
    return Number.isInteger(n) && (n as number) >= 1 && (n as number) <= 100;
}

/** `s` with its carried and looted items in item id order. */
export function byId(s: CampSettings): CampSettings {
    return {...s, items: [...s.items].sort((a, b) => a.id - b.id), loot: [...s.loot].sort((a, b) => a - b)};
}

/**
 * The hits to eat at, unless the user sets their own: three of the hardest target's hits, plus 5,
 * and at least 5 more than any route step can hit for.
 */
export function proposedEat(targetMaxHit: number, stepHits: number): number {
    return Math.min(99, Math.max(3 * targetMaxHit + 5, stepHits > 0 ? stepHits + 5 : 0));
}
