/**
 * Answers the route planner: over HTTP, or with nav's planner compiled to WebAssembly in a worker.
 * The wire types in this file mirror the records of nav's `openrsc.nav.planner` package
 * (PathfinderInputs, PathfinderPlans, PathfinderAccount, PathfinderReach); a null field is left out.
 */
export interface PlannerHost {
    /** What the editor may offer; fixed per world. */
    inputs(): Promise<PlannerInputs>;
    plan(req: PlanRequest): Promise<PlanResponse>;
    /**
     * A cheaper {@link PlannerHost.plan} for the live preview while an endpoint is placed: the walk and
     * the engine's route only, without the estimate, derived read-outs or a diagnosis.
     */
    preview?(req: PlanRequest): Promise<PlanResponse>;
    account(req: PlanRequest): Promise<PlannerAccount>;
    /** The walking components the request's account can reach from `from`; `to` is ignored. */
    reachable(req: PlanRequest): Promise<PlannerReach>;
}

/**
 * Walking component ids reachable from a start, on every floor, ascending. `components` is how
 * many components the map has; `ms` how long the search took.
 */
export interface PlannerReach {
    from: {x: number; z: number} | null;
    reachable: number[];
    components: number;
    ms: number;
}

/** Item display names by item id. */
export type ItemNames = Record<string, string>;

export interface PlanPackItem {
    slot: number;
    id: number;
    amount: number;
    equipped: boolean;
}

/** A transport, with the tile it starts from; no coordinates for one with no place, such as a spell. */
export interface PlannerOpening {
    name: string;
    x?: number;
    z?: number;
}

/** A value at which some transport gate's answer changes, and how many transports reaching it opens. */
export interface PlannerThreshold {
    at: number;
    transports: number;
    opens: PlannerOpening[];
}

/** A skill the gates read, with their breakpoints; none when it only counts towards combat level. */
export interface PlannerSkill {
    skill: string;
    combat: boolean;
    breakpoints: PlannerThreshold[];
}

export interface PlannerItemRef {
    id: number;
    name: string;
}

/** Items some gate accepts interchangeably. */
export interface PlannerItemGroup {
    label: string;
    items: PlannerItemRef[];
    transports: number;
}

export interface PlannerItem {
    id: number;
    name: string;
    transports: number;
    carry: PlannerThreshold[];
    /** Counts some gate refuses above; `at` is the most it tolerates. */
    mustNotCarry: PlannerThreshold[];
    worn: boolean;
}

export interface PlannerQuest {
    id: number;
    name: string;
    transports: number;
    atLeast: PlannerThreshold[];
    exact: PlannerThreshold[];
    done?: PlannerThreshold;
}

/** A server player-cache key some gate reads; `kind` is `"bool"` or `"int"`. */
export interface PlannerCacheKey {
    key: string;
    kind: string;
    transports: number;
    targets: PlannerThreshold[];
}

export interface PlannerPolicyFlag {
    key: string;
    label: string;
    defaultValue: boolean;
}

export interface PlannerTotals {
    transports: number;
    gated: number;
    never: number;
    gates: number;
    facts: number;
}

export interface PlannerInputs {
    totals: PlannerTotals;
    skills: PlannerSkill[];
    combatSkills: string[];
    itemGroups: PlannerItemGroup[];
    items: PlannerItem[];
    quests: PlannerQuest[];
    cache: PlannerCacheKey[];
    policy: PlannerPolicyFlag[];
    derived: string[];
}

/**
 * One arrival band: an exact `item`, or any of `anyOf` counted in pack slots. `min` defaults to 0,
 * `target` to `min` and `max` to `target`; `max` -1 means unbounded, and a family's is capped at 30.
 */
export interface PlanWant {
    item?: number;
    anyOf?: number[];
    min?: number;
    target?: number;
    max?: number;
    worn?: boolean;
}

/**
 * An account described by hand. A skill left out is a fresh account's level (1, Hits 10), and levels
 * are clamped to 0..99. `quests` maps quest id to stage, -1 being complete; a quest left out is not
 * started. Item maps are amount by item id.
 */
export interface PlanAccountInput {
    skills: Record<string, number>;
    quests: Record<number, number>;
    inventory: Record<number, number>;
    bank: Record<number, number>;
    cache?: PlanCacheValues;
}

export interface PlanCacheValues {
    bools: Record<string, boolean>;
    ints: Record<string, number>;
}

/** `keepEverythingElse` (default true) carries what the wants do not name; false banks it. */
export interface PlanLoadoutInput {
    keepEverythingElse: boolean;
    bankWhenFull: boolean;
    wants: PlanWant[];
}

/**
 * nav's NavPolicy. `danger` routes around aggressive NPCs; `costOverrides` prices transports by name,
 * 2147483647 meaning never take it. `assumeAllEligible` treats every transport as open to the account.
 */
export interface NavPolicy {
    danger: "ON" | "OFF";
    teleportSpells: boolean;
    teleportJewellery: boolean;
    costOverrides: Record<string, number>;
    assumeAllEligible: boolean;
    avoidSkulled: boolean;
    berth: BerthPolicy;
}

/**
 * Which aggressive NPCs a walk keeps its distance from: all of them when `berthByDefault`, else none,
 * with the NPC ids in `ids` and the server indexes in `indexes` the exceptions.
 */
export interface BerthPolicy {
    berthByDefault: boolean;
    ids: number[];
    indexes: number[];
}

/** A non-null `as` plans as that named account and ignores `account`. */
export interface PlanRequest {
    from: {x: number; z: number};
    to: {x: number; z: number};
    as: string | null;
    account: PlanAccountInput;
    loadout: PlanLoadoutInput;
    policy: NavPolicy;
}

/**
 * One step of the simulated walk. A hop's `cost` is in tenths of a tick. A bank step's maps are pack
 * targets by item id, not amounts moved: `withdraw` raises a count to at least its value, `deposit`
 * lowers it to at most its value. `because` says what the stop is for.
 */
export interface PlanStep {
    type: "walk" | "hop" | "bank";
    x: number;
    z: number;
    name?: string;
    cost?: number;
    req?: string;
    withdraw?: Record<number, number>;
    deposit?: Record<number, number>;
    wears: number[];
    because: string[];
    /** The pack once the step is done: a hop's fare already paid, a bank stop's withdrawals made. */
    pack: PlanPackItem[];
}

/** Read-outs computed from the request. */
export interface PlanDerived {
    combatLevel: number;
    questPoints: number;
    wildernessLevel: number;
    usableTransports: number;
    totalTransports: number;
}

/**
 * Where a plan's time went, in milliseconds except for the counts. `botWeightSetsBuilt` and
 * `botLegPlanesBuilt` count what this plan had to build afresh; 0 means earlier plans' work served.
 */
export interface PlanTimings {
    totalMs: number;
    estimateMs: number;
    botPlanMs: number;
    botWeightSetsBuilt: number;
    botLegPlanesBuilt: number;
    simulatedTicks: number;
    simulatedPlans: number;
    simulatedWalks: number;
    simulationPlanMs: number;
    simulationWalkMs: number;
    derivedMs: number;
    diagnosisMs: number;
}

/**
 * One element of the engine's own route: a `cross` is the seam span (x, z) to (x2, z2) it crosses,
 * a `hop` the transport `name` from its entry (x, z) to its landing (x2, z2), a `bank` the booth,
 * and `arrive` the destination's bounds.
 */
export interface EnginePlanStep {
    type: "cross" | "hop" | "bank" | "arrive";
    x: number;
    z: number;
    x2?: number;
    z2?: number;
    name?: string;
}

/**
 * Why a plan failed, as nav's Diagnosis.Kind: the checks run in this order, so a later kind means
 * every earlier one was ruled out. `UNDIAGNOSED` means the diagnosis itself threw.
 */
export type NoRouteKind =
    | "DESTINATION"
    | "QUESTS_UNKNOWN"
    | "LOADOUT"
    | "SHED"
    | "PACK"
    | "ELIGIBILITY"
    | "POLICY"
    | "GEOGRAPHY"
    | "UNDIAGNOSED";

/**
 * One thing in the way and the change that removes it. `items` are the item ids it covers;
 * `quests`, `skills` and `cache` name the rest of what a failed gate waits on, null when there is none.
 */
export interface NoRouteBlocker {
    what: string;
    why: string;
    fix: string;
    items: number[];
    quests: number[] | null;
    skills: string[] | null;
    cache: string[] | null;
    /** The one hop to blame, when there is one. */
    transport?: string;
}

/** One set of blockers that, removed together, lets the trip route. `hops` are that route's transports. */
export interface NoRouteFix {
    kind: NoRouteKind;
    summary: string;
    blockers: NoRouteBlocker[];
    hops: string[];
}

/** Every fix found, any one of which will do; `complete` is false when the search stopped early. */
export interface NoRouteWhy {
    kind: NoRouteKind;
    summary: string;
    fixes: NoRouteFix[];
    complete: boolean;
}

/**
 * `ticks` is the route's length in game ticks. `plan` is empty and `ticks` 0 when nothing was found;
 * `diagnosis` is then the one-line form of `why`. `account` is absent when the account could not be
 * built or the response is a preview.
 */
export interface PlanResponse {
    found: boolean;
    ticks: number;
    steps: PlanStep[];
    plan?: EnginePlanStep[];
    account?: PlanDerived;
    diagnosis?: string;
    why?: NoRouteWhy;
    timings: PlanTimings;
}

/**
 * Every fact and distinct rule the transport gates read, most-read first, each with whether it holds
 * and how many transports read it; the `usable` transports by name, out of `total`; and the cache keys
 * the account holds no value for, on which a gate's fact is assumed to hold.
 */
export interface PlannerAccount {
    facts: {fact: string; holds: boolean; transports: number}[];
    rules: {rule: string; passes: boolean; transports: number}[];
    usable: PlannerOpening[];
    total: number;
    cacheAssumed: string[];
}
