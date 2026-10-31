import {Slider} from "@base-ui/react/slider";
import {memo, useEffect, useMemo, useState} from "react";
import type {
    ItemNames,
    PlanCacheValues,
    PlanPackItem,
    PlannerCacheKey,
    PlannerHost,
    NavPolicy,
    PlanRequest,
    PlanResponse,
    PlanStep,
    PlanTimings,
    PlannerAccount,
    PlannerInputs,
} from "./plannerApi";
import {getItemAtlas, ItemIcon, type ItemAtlasIndex} from "../ItemIcon";
import {
    combatLevelOf,
    freshLevels,
    type Levels,
    minCombatLevel,
    nextStop,
    SKILL_MAX,
    SKILL_MIN,
    snapNearest,
    requestLevels,
    sliderStops,
    snapDown,
} from "./plannerModel";
import {FloatingWindow} from "../FloatingWindow";
import {Info, LoadoutTable, loadoutOf, type LoadoutRows, OpeningList, type PlannerRow, rowKey} from "./LoadoutTable";
import "./planner.css";

/**
 * Everything the planner's editor holds. `levels` are the skills the gates read; attack, strength,
 * defense and hits are synthesized from `combatLevel`. `cache` holds the player-cache values the
 * account is known to have, by key; a key left out is unknown.
 */
export interface PlannerRequestState extends LoadoutRows {
    levels: Levels;
    combatLevel: number;
    inventory: Record<string, number>;
    bank: Record<string, number>;
    quests: Record<number, number>;
    cache: Record<string, boolean | number>;
    policy: NavPolicy;
}

const DEFAULT_POLICY: NavPolicy = {
    // Off, unlike nav's default: the plain shortest walk is the baseline a plan is compared against.
    danger: "OFF",
    teleportSpells: false,
    teleportJewellery: false,
    costOverrides: {},
    assumeAllEligible: false,
    avoidSkulled: false,
    berth: {berthByDefault: true, ids: [], indexes: []},
};

const POLICY_FLAGS: Partial<
    Record<
        string,
        {
            get: (p: NavPolicy) => boolean;
            set: (p: NavPolicy, on: boolean) => NavPolicy;
        }
    >
> = {
    danger: {
        get: (p) => p.danger === "ON",
        set: (p, on) => ({...p, danger: on ? "ON" : "OFF"}),
    },
    berth: {
        get: (p) => p.berth.berthByDefault,
        set: (p, on) => ({...p, berth: {berthByDefault: on, ids: [], indexes: []}}),
    },
    teleportSpells: {
        get: (p) => p.teleportSpells,
        set: (p, on) => ({...p, teleportSpells: on}),
    },
    teleportJewellery: {
        get: (p) => p.teleportJewellery,
        set: (p, on) => ({...p, teleportJewellery: on}),
    },
};

/** `raw` over the default policy; also reads an `avoidDanger` boolean in place of `danger`. */
export function policyOf(raw: unknown): NavPolicy {
    if (typeof raw !== "object" || raw == null) return DEFAULT_POLICY;
    const o = raw as Partial<NavPolicy> & {avoidDanger?: boolean};
    const out: NavPolicy = {...DEFAULT_POLICY, ...o};
    if (o.danger == null && typeof o.avoidDanger === "boolean") {
        out.danger = o.avoidDanger ? "ON" : "OFF";
    }
    delete (out as {avoidDanger?: boolean}).avoidDanger;
    out.berth = {...DEFAULT_POLICY.berth, ...(o.berth ?? {})};
    return out;
}

// The rows every editor starts with, each with a little banked so the first plan has a reason to bank:
// coins, a knife, a lit candle, a bronze axe and a machette.
const STANDING_ROWS: {item: number; bank: number}[] = [
    {item: 10, bank: 100},
    {item: 13, bank: 1},
    {item: 601, bank: 1},
    {item: 87, bank: 1},
    {item: 1172, bank: 1},
];

/** The standing rows whose items some gate in `inputs` reads, in order; empty until it arrives. */
function defaultRows(inputs: PlannerInputs | null): PlannerRow[] {
    if (!inputs) return [];
    return STANDING_ROWS.filter((want) => inputs.items.some((i) => i.id === want.item)).map((want) => ({
        kind: "item",
        id: want.item,
    }));
}

/** Whether `row` is a standing row; those cannot be removed. */
function isStandingRow(row: PlannerRow, inputs: PlannerInputs | null): boolean {
    const key = rowKey(row);
    return defaultRows(inputs).some((r) => rowKey(r) === key);
}

export function freshState(inputs: PlannerInputs | null): PlannerRequestState {
    const levels = freshLevels(inputs);
    const rows = defaultRows(inputs);
    const bank: Record<string, number> = {};
    for (const row of rows) {
        bank[rowKey(row)] = STANDING_ROWS.find((want) => want.item === row.id)?.bank ?? 0;
    }
    return {
        levels,
        combatLevel: combatLevelOf(levels),
        rows,
        inventory: {},
        bank,
        arrive: {},
        equip: {},
        quests: {},
        cache: {},
        policy: DEFAULT_POLICY,
    };
}

/** `state` with every skill at 99, combat 123 and every quest a gate reads complete. */
export function maxedState(state: PlannerRequestState, inputs: PlannerInputs | null): PlannerRequestState {
    const levels = {...state.levels};
    for (const name of Object.keys(levels)) levels[name] = SKILL_MAX;
    const quests: Record<number, number> = {};
    for (const q of inputs?.quests ?? []) quests[q.id] = -1;
    return {...state, levels, combatLevel: 123, quests};
}

/** A skill's enum name (`WOODCUTTING`) as a label (`Woodcutting`). */
export function skillLabel(skill: string): string {
    return skill.charAt(0) + skill.slice(1).toLowerCase();
}

// The stages a gate tests, plus not started (0) and complete (-1, sorted last).
function questStages(quest: {atLeast: {at: number}[]; exact: {at: number}[]}): number[] {
    const stages = new Set<number>([0, -1]);
    for (const t of quest.atLeast) stages.add(t.at);
    for (const t of quest.exact) stages.add(t.at);
    return [...stages].sort((a, b) => (a === -1 ? 1 : b === -1 ? -1 : a - b));
}

function stageLabel(stage: number): string {
    return stage === -1 ? "done" : stage === 0 ? "not started" : `stage ${stage}`;
}

/** What a failed plan blames: item rows by {@link rowKey}, quests by id, skills by name, cache keys. */
export interface Blamed {
    rows: ReadonlySet<string>;
    quests: ReadonlySet<number>;
    skills: ReadonlySet<string>;
    cache: ReadonlySet<string>;
}

/** The editor rows a failed plan's blockers name. */
function blockedRows(plan: PlanResponse | null, rows: PlannerRow[]): Blamed {
    const blamed: Blamed = {rows: new Set(), quests: new Set(), skills: new Set(), cache: new Set()};
    if (!plan || plan.found || !plan.why) return blamed;
    const items = new Set<number>();
    const quests = blamed.quests as Set<number>;
    const skills = blamed.skills as Set<string>;
    const cache = blamed.cache as Set<string>;
    for (const f of plan.why.fixes) {
        for (const b of f.blockers) {
            for (const id of b.items) items.add(id);
            for (const q of b.quests ?? []) quests.add(q);
            for (const k of b.skills ?? []) skills.add(k);
            for (const k of b.cache ?? []) cache.add(k);
        }
    }
    for (const row of rows) {
        if (items.has(row.id)) (blamed.rows as Set<string>).add(rowKey(row));
    }
    return blamed;
}

function cacheValuesOf(cache: Record<string, boolean | number>): PlanCacheValues {
    const out: PlanCacheValues = {bools: {}, ints: {}};
    for (const [key, v] of Object.entries(cache)) {
        if (typeof v === "boolean") out.bools[key] = v;
        else out.ints[key] = v;
    }
    return out;
}

/** The editor as a plan request. A non-null `as` plans as that named account instead of the one described. */
export function toPlanRequest(
    state: PlannerRequestState,
    from: {x: number; z: number},
    to: {x: number; z: number},
    as: string | null = null,
): PlanRequest {
    const inventory: Record<number, number> = {};
    const bank: Record<number, number> = {};
    for (const row of state.rows) {
        const key = rowKey(row);
        const inv = state.inventory[key] ?? 0;
        const banked = state.bank[key] ?? 0;
        if (inv > 0) inventory[row.id] = inv;
        if (banked > 0) bank[row.id] = banked;
    }
    return {
        from,
        to,
        as,
        account: {
            skills: requestLevels(state.levels, state.combatLevel),
            quests: state.quests,
            inventory,
            bank,
            cache: cacheValuesOf(state.cache),
        },
        loadout: loadoutOf(state),
        policy: policyOf(state.policy),
    };
}

/** Whether the host can answer: ready, still starting, or failed with a reason. */
export type PlannerHostState = "ready" | "loading" | {failed: string};

/** A named account the editor can be filled from; `value` is passed to `onCopyFrom`. */
export interface PlannerCopySource {
    value: string;
    label: string;
}

/**
 * `hostProgress` is what a loading host is doing. `picking` means map clicks set endpoints, and
 * `pickTarget` the endpoint singled out for the next one. `follow` and `onFollowChange` offer the
 * camera-follow checkbox when given. `onCopyFrom` takes `"fresh"`, `"maxed"` or a copy source's
 * `value`. Without `onReachable` the reachable toggle is not offered; with it, it receives the
 * walking components reachable from the start while the toggle is on, and null otherwise.
 */
export interface PlannerPanelProps {
    host: PlannerHost;
    hostState?: PlannerHostState;
    hostProgress?: string | null;
    inputs: PlannerInputs | null;
    copySources: PlannerCopySource[];
    itemDefs: ItemNames | null;
    wearableItems?: ReadonlySet<number> | null;
    start: {x: number; z: number} | null;
    end: {x: number; z: number} | null;
    picking: boolean;
    onClearRoute: () => void;
    onReplan: () => void;
    follow?: boolean;
    onFollowChange?: (on: boolean) => void;
    pickTarget: "start" | "end" | null;
    onArmEndpoint: (which: "start" | "end") => void;
    onSetEndpoint: (which: "start" | "end", tile: {x: number; z: number}) => void;
    state: PlannerRequestState;
    onStateChange: (next: PlannerRequestState) => void;
    plan: PlanResponse | null;
    loading: boolean;
    error: string | null;
    onHoverStep: (tile: {x: number; z: number} | null) => void;
    onFocusStep: (tile: {x: number; z: number}) => void;
    onCopyFrom: (choice: string) => void;
    onClose: () => void;
    onReachable?: (ids: ReadonlySet<number> | null) => void;
}

// The reachable toggle's answer, asked again whenever the start or the account changes while on.
function useReachable(props: PlannerPanelProps, on: boolean) {
    const {host, start, state, onReachable} = props;
    const ask = useMemo(
        () => (onReachable && on && start ? {host, start, state} : null),
        [host, start, state, on, onReachable],
    );
    const [answer, setAnswer] = useState<{
        ask: NonNullable<typeof ask>;
        info: {count: number; total: number; ms: number} | null;
        error: string | null;
    } | null>(null);
    if (!ask && answer) setAnswer(null);
    useEffect(() => {
        if (!onReachable) return;
        if (!ask) {
            onReachable(null);
            return;
        }
        let alive = true;
        ask.host.reachable(toPlanRequest(ask.state, ask.start, ask.start)).then(
            (r) => {
                if (!alive) return;
                setAnswer({ask, info: {count: r.reachable.length, total: r.components, ms: r.ms}, error: null});
                onReachable(new Set(r.reachable));
            },
            (e: unknown) => {
                if (!alive) return;
                const error = e instanceof Error ? e.message : String(e);
                setAnswer((prev) => ({ask, info: prev?.info ?? null, error}));
                onReachable(null);
            },
        );
        return () => {
            alive = false;
        };
    }, [ask, onReachable]);
    useEffect(() => () => onReachable?.(null), [onReachable]);
    return {
        info: answer?.info ?? null,
        error: answer?.error ?? null,
        loading: ask !== null && answer?.ask !== ask,
    };
}

/**
 * The route planner window: an account's levels, quests, items and cache values, the routing choices
 * and the two endpoints, with the plan, or why there is none, at the top.
 */
export const PlannerPanel = memo(function PlannerPanel(props: PlannerPanelProps) {
    const {inputs, copySources, itemDefs, start, end, picking, state, onStateChange, plan} = props;
    const hostState = props.hostState ?? "ready";
    const [reachOn, setReachOn] = useState(false);
    const reach = useReachable(props, reachOn);

    const set = (patch: Partial<PlannerRequestState>) => onStateChange({...state, ...patch});
    const nameOf = (id: number) => itemDefs?.[String(id)] ?? `item#${id}`;
    const blocked = blockedRows(plan, state.rows);

    const gateSkills = useMemo(() => (inputs?.skills ?? []).filter((s) => s.breakpoints.length > 0), [inputs]);
    const combatFloor = minCombatLevel(state.levels);

    return (
        <FloatingWindow
            id="planner"
            title="route planner"
            className="planner"
            resizable
            anchor={{top: 8, left: 236}}
            onClose={props.onClose}
        >
            <div className="planner-body">
                {typeof hostState === "object" ? (
                    <div className="planner-status err">the pathfinder failed to start: {hostState.failed}</div>
                ) : hostState === "loading" || !inputs ? (
                    <div className="planner-loading">
                        <span className="planner-spinner" />
                        <span>{hostState === "loading" ? "Initializing pathfinder…" : "Loading…"}</span>
                        {hostState === "loading" && props.hostProgress && (
                            <span className="planner-loading-stage">{props.hostProgress}…</span>
                        )}
                    </div>
                ) : (
                    <>
                        {/* A one-time copy: picking fills the editor and the select returns to "plan as". */}
                        <select
                            className="planner-copy"
                            value=""
                            onChange={(e) => {
                                if (e.target.value) props.onCopyFrom(e.target.value);
                                e.target.value = "";
                            }}
                        >
                            <option value="">plan as…</option>
                            <option value="fresh">a fresh account</option>
                            <option value="maxed">a maxed account</option>
                            {copySources.length > 0 && (
                                <optgroup label="copy from an account">
                                    {copySources.map((s) => (
                                        <option key={s.value} value={s.value}>
                                            {s.label}
                                        </option>
                                    ))}
                                </optgroup>
                            )}
                        </select>

                        <div className="planner-ends">
                            <Endpoint
                                which="start"
                                tile={start}
                                armed={props.pickTarget === "start" || (picking && !props.pickTarget && !start)}
                                onArm={() => props.onArmEndpoint("start")}
                                onCommit={(t) => props.onSetEndpoint("start", t)}
                            />
                            <Endpoint
                                which="end"
                                tile={end}
                                armed={props.pickTarget === "end" || (picking && !props.pickTarget && !!start && !end)}
                                onArm={() => props.onArmEndpoint("end")}
                                onCommit={(t) => props.onSetEndpoint("end", t)}
                            />
                            <div className="planner-ends-actions">
                                {props.onFollowChange && (
                                    <label
                                        className="planner-follow"
                                        title="keep the camera on the bot walking the plan; panning lets go"
                                    >
                                        <input
                                            type="checkbox"
                                            checked={props.follow ?? false}
                                            onChange={(e) => props.onFollowChange?.(e.target.checked)}
                                        />
                                        follow
                                    </label>
                                )}
                                <button
                                    type="button"
                                    className={`planner-act-pick${picking ? " active" : ""}`}
                                    onClick={props.onClearRoute}
                                >
                                    pick
                                </button>
                                <button
                                    type="button"
                                    className="planner-act-replan"
                                    disabled={!start || !end || props.loading}
                                    title="plan the same route again"
                                    onClick={props.onReplan}
                                >
                                    replan
                                </button>
                                {props.onReachable && (
                                    <button
                                        type="button"
                                        className={`planner-act-reachable${reachOn ? " active" : ""}`}
                                        disabled={!start}
                                        title={
                                            reach.error ??
                                            "show only the walking components this account can reach from the start"
                                        }
                                        onClick={() => setReachOn((v) => !v)}
                                    >
                                        reachable
                                        {reachOn && reach.info && !reach.loading
                                            ? ` ${reach.info.count}/${reach.info.total}`
                                            : reachOn && reach.loading
                                              ? " …"
                                              : ""}
                                    </button>
                                )}
                            </div>
                        </div>

                        <Result
                            host={props.host}
                            plan={plan}
                            loading={props.loading}
                            error={props.error}
                            nameOf={nameOf}
                            onHoverStep={props.onHoverStep}
                            onFocusStep={props.onFocusStep}
                            accountRequest={start && end ? toPlanRequest(state, start, end) : null}
                        />

                        <details className="planner-section" open>
                            <summary>levels</summary>
                            <div className="planner-row">
                                <span>combat</span>
                                <LevelSlider
                                    min={3}
                                    max={123}
                                    live={[combatFloor, 123]}
                                    value={Math.max(state.combatLevel, combatFloor)}
                                    onChange={(v) =>
                                        set({
                                            combatLevel: Math.max(combatFloor, v),
                                        })
                                    }
                                />
                                <button
                                    type="button"
                                    className="planner-val"
                                    title={state.combatLevel < 123 ? "set to 123" : `set to ${combatFloor}`}
                                    onClick={() =>
                                        set({
                                            combatLevel: state.combatLevel < 123 ? 123 : combatFloor,
                                        })
                                    }
                                >
                                    {Math.max(state.combatLevel, combatFloor)}
                                </button>
                                <Info
                                    title="combat level"
                                    lines={[
                                        "no gate reads it directly — it picks the danger rung, so it " +
                                            "decides which aggressive NPCs the route steers around",
                                        `its floor (${combatFloor}) follows from the levels below`,
                                    ]}
                                />
                            </div>
                            {gateSkills.map((s) => {
                                const stops = sliderStops(s.breakpoints);
                                const level = state.levels[s.skill] ?? SKILL_MIN;
                                const at = snapDown(level, stops);
                                const next = nextStop(level, stops);
                                const gain = next == null ? null : s.breakpoints.find((b) => b.at === next);
                                return (
                                    <div
                                        key={s.skill}
                                        className={`planner-row${
                                            blocked.skills.has(s.skill) ? " planner-item-blocked" : ""
                                        }`}
                                    >
                                        <span>{skillLabel(s.skill)}</span>
                                        <LevelSlider
                                            min={SKILL_MIN}
                                            max={SKILL_MAX}
                                            value={level}
                                            stops={stops}
                                            live={[s.breakpoints[0].at, s.breakpoints[s.breakpoints.length - 1].at]}
                                            onChange={(v) =>
                                                set({
                                                    levels: {
                                                        ...state.levels,
                                                        [s.skill]: snapNearest(v, stops),
                                                    },
                                                })
                                            }
                                        />
                                        <button
                                            type="button"
                                            className="planner-val"
                                            title={level < SKILL_MAX ? `set to ${SKILL_MAX}` : `set to ${SKILL_MIN}`}
                                            onClick={() =>
                                                set({
                                                    levels: {
                                                        ...state.levels,
                                                        [s.skill]: level < SKILL_MAX ? SKILL_MAX : SKILL_MIN,
                                                    },
                                                })
                                            }
                                        >
                                            {level}
                                        </button>
                                        <Info title={gain ? usableLabel(gain.transports) : "all transports usable"}>
                                            {gain && (
                                                <OpeningList
                                                    list={gain.opens}
                                                    total={gain.transports}
                                                    onFocus={props.onFocusStep}
                                                />
                                            )}
                                            {at !== level && <div className="planner-tip-note">counts as {at}</div>}
                                        </Info>
                                    </div>
                                );
                            })}
                        </details>

                        <details className="planner-section" open>
                            <summary>items</summary>
                            <LoadoutTable
                                inputs={inputs}
                                itemDefs={itemDefs}
                                wearableItems={props.wearableItems}
                                state={state}
                                onChange={set}
                                columns={[
                                    {
                                        label: "pack",
                                        title: "carried now",
                                        value: (key) => state.inventory[key] ?? 0,
                                        onChange: (key, n) => set({inventory: {...state.inventory, [key]: n}}),
                                    },
                                    {
                                        label: "bank",
                                        title: "owned, but banked",
                                        value: (key) => state.bank[key] ?? 0,
                                        onChange: (key, n) => set({bank: {...state.bank, [key]: n}}),
                                    },
                                ]}
                                removable={(row) => !isStandingRow(row, inputs)}
                                blocked={blocked.rows}
                                onAdd={(row) => ({inventory: {...state.inventory, [rowKey(row)]: 1}})}
                                onFocus={props.onFocusStep}
                            />
                        </details>

                        {/* Opened when a plan blames a quest, so the highlighted row is not hidden. */}
                        <details className="planner-section" open={blocked.quests.size > 0 || undefined}>
                            <summary>quests ({Object.values(state.quests).filter((s) => s !== 0).length} set)</summary>
                            {inputs.quests.map((q) => {
                                const stages = questStages(q);
                                const numbered = stages.filter((s) => s !== -1);
                                // Complete is -1 on the wire; on the slider it sits one past the highest stage.
                                const donePos = (numbered.length ? Math.max(...numbered) : 0) + 1;
                                const positions = [...numbered, donePos];
                                const stage = state.quests[q.id] ?? 0;
                                return (
                                    <div
                                        key={q.id}
                                        className={`planner-quest${
                                            blocked.quests.has(q.id) ? " planner-item-blocked" : ""
                                        }`}
                                    >
                                        <span>
                                            {q.name}{" "}
                                            <Info title={`${q.name} · ${q.transports} transports`}>
                                                {q.atLeast.map((t) => (
                                                    <OpeningList
                                                        key={`ge${t.at}`}
                                                        list={t.opens}
                                                        total={t.transports}
                                                        prefix={`stage ≥ ${t.at} · `}
                                                        onFocus={props.onFocusStep}
                                                    />
                                                ))}
                                                {q.exact.map((t) => (
                                                    <OpeningList
                                                        key={`eq${t.at}`}
                                                        list={t.opens}
                                                        total={t.transports}
                                                        prefix={`stage ${t.at} exactly · `}
                                                        onFocus={props.onFocusStep}
                                                    />
                                                ))}
                                                {q.done && (
                                                    <OpeningList
                                                        list={q.done.opens}
                                                        total={q.done.transports}
                                                        prefix="done · "
                                                        onFocus={props.onFocusStep}
                                                    />
                                                )}
                                            </Info>
                                        </span>
                                        <LevelSlider
                                            min={0}
                                            max={donePos}
                                            value={stage === -1 ? donePos : stage}
                                            stops={positions}
                                            onChange={(p) => {
                                                const at = snapNearest(p, positions);
                                                set({
                                                    quests: {
                                                        ...state.quests,
                                                        [q.id]: at === donePos ? -1 : at,
                                                    },
                                                });
                                            }}
                                        />
                                        <button
                                            type="button"
                                            className="planner-quest-val"
                                            title={stage === -1 ? "back to not started" : "mark done"}
                                            onClick={() =>
                                                set({
                                                    quests: {
                                                        ...state.quests,
                                                        [q.id]: stage === -1 ? 0 : -1,
                                                    },
                                                })
                                            }
                                        >
                                            {stageLabel(stage)}
                                        </button>
                                    </div>
                                );
                            })}
                        </details>

                        <details className="planner-section" open={blocked.cache.size > 0 || undefined}>
                            <summary>player cache ({Object.keys(state.cache).length} known)</summary>
                            <div className="planner-tip-note">unknown values let their gates through</div>
                            {inputs.cache.map((entry) => (
                                <CacheRow
                                    key={entry.key}
                                    entry={entry}
                                    value={state.cache[entry.key]}
                                    blocked={blocked.cache.has(entry.key)}
                                    onFocus={props.onFocusStep}
                                    onChange={(v) => {
                                        const cache = Object.fromEntries(
                                            Object.entries(state.cache).filter(([k]) => k !== entry.key),
                                        );
                                        if (v !== undefined) cache[entry.key] = v;
                                        set({cache});
                                    }}
                                />
                            ))}
                        </details>

                        <details className="planner-section" open>
                            <summary>routing</summary>
                            <div className="planner-checks">
                                {inputs.policy.map((flag) => {
                                    const knob = POLICY_FLAGS[flag.key];
                                    if (!knob) return null;
                                    const policy = policyOf(state.policy);
                                    return (
                                        <label key={flag.key} className="planner-check">
                                            <input
                                                type="checkbox"
                                                checked={knob.get(policy)}
                                                onChange={(e) =>
                                                    set({
                                                        policy: knob.set(policy, e.target.checked),
                                                    })
                                                }
                                            />
                                            {flag.label}
                                        </label>
                                    );
                                })}
                            </div>
                        </details>
                    </>
                )}
            </div>
        </FloatingWindow>
    );
});

const BOOL_STATES: {label: string; value: boolean | undefined}[] = [
    {label: "unknown", value: undefined},
    {label: "set", value: true},
    {label: "unset", value: false},
];

// One player-cache key: unknown, or the value the account has.
function CacheRow({
    entry,
    value,
    blocked,
    onChange,
    onFocus,
}: {
    entry: PlannerCacheKey;
    blocked: boolean;
    value: boolean | number | undefined;
    onChange: (value: boolean | number | undefined) => void;
    onFocus: (tile: {x: number; z: number}) => void;
}) {
    const int = entry.kind === "int";
    const label = (at: number) => (int ? `≥ ${at} · ` : at === 1 ? "set · " : "unset · ");
    return (
        <div className={`planner-cache${blocked ? " planner-item-blocked" : ""}`}>
            <span>{entry.key}</span>
            {int ? (
                <span className="planner-seg">
                    <button
                        type="button"
                        className={value === undefined ? "active" : ""}
                        onClick={() => onChange(undefined)}
                    >
                        unknown
                    </button>
                    <input
                        type="number"
                        min={0}
                        placeholder="value"
                        value={value === undefined ? "" : Number(value)}
                        onChange={(e) =>
                            onChange(e.target.value === "" ? undefined : Math.max(0, parseInt(e.target.value, 10) || 0))
                        }
                    />
                </span>
            ) : (
                <span className="planner-seg">
                    {BOOL_STATES.map((s) => (
                        <button
                            key={s.label}
                            type="button"
                            className={value === s.value ? "active" : ""}
                            onClick={() => onChange(s.value)}
                        >
                            {s.label}
                        </button>
                    ))}
                </span>
            )}
            <Info title={`${entry.key} · ${entry.transports} transports`}>
                {entry.targets.map((t) => (
                    <OpeningList
                        key={t.at}
                        list={t.opens}
                        total={t.transports}
                        prefix={label(t.at)}
                        onFocus={onFocus}
                    />
                ))}
            </Info>
        </div>
    );
}

function parseTile(text: string): {x: number; z: number} | null {
    const m = /(-?\d+)\s*[,\s]\s*(-?\d+)/.exec(text);
    return m ? {x: Number(m[1]), z: Number(m[2])} : null;
}

// One endpoint's coordinate: a click arms it for the next map click and opens it for typing; Enter
// commits a typed "x, z", and Escape or leaving the field drops it.
function Endpoint(props: {
    which: "start" | "end";
    tile: {x: number; z: number} | null;
    armed: boolean;
    onArm: () => void;
    onCommit: (tile: {x: number; z: number}) => void;
}) {
    const [editing, setEditing] = useState(false);
    const [draft, setDraft] = useState("");
    const text = props.tile ? `${props.tile.x}, ${props.tile.z}` : "";
    const begin = () => {
        setDraft(text);
        setEditing(true);
        props.onArm();
    };
    return (
        <div className={`planner-end${props.armed ? " planner-end-armed" : ""}`} onClick={begin}>
            <span className={`path-label path-label-${props.which}`}>{props.which}</span>
            {editing ? (
                <input
                    className="planner-end-tile"
                    type="text"
                    autoFocus
                    value={draft}
                    onFocus={(e) => e.target.select()}
                    onChange={(e) => setDraft(e.target.value)}
                    onKeyDown={(e) => {
                        if (e.key === "Enter") {
                            const tile = parseTile(draft);
                            if (tile) props.onCommit(tile);
                            setEditing(false);
                        } else if (e.key === "Escape") {
                            setEditing(false);
                        }
                    }}
                    onBlur={() => setEditing(false)}
                />
            ) : (
                <span className="planner-end-tile">{text || (props.armed ? "click the map" : "—")}</span>
            )}
        </div>
    );
}

function usableLabel(n: number): string {
    return `+${n} transport${n === 1 ? "" : "s"} usable`;
}

// The account's usable transports, facts and rules, asked once per mount: the tip mounts it afresh
// each time it opens.
function AccountBreakdown({
    host,
    request,
    onFocus,
}: {
    host: PlannerHost;
    request: PlanRequest;
    onFocus: (tile: {x: number; z: number}) => void;
}) {
    const [data, setData] = useState<PlannerAccount | null>(null);
    const [error, setError] = useState<string | null>(null);
    useEffect(() => {
        let alive = true;
        host.account(request)
            .then((d) => alive && setData(d))
            .catch((e: unknown) => alive && setError(e instanceof Error ? e.message : String(e)));
        return () => {
            alive = false;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    if (error) return <div className="planner-result err">{error}</div>;
    if (!data) return <div className="planner-pack-empty">reading the account…</div>;
    const holding = data.facts.filter((f) => f.holds).length;
    const passing = data.rules.filter((r) => r.passes).length;
    return (
        <div className="planner-breakdown">
            <details>
                <summary>
                    {data.usable.length} of {data.total} transports usable
                </summary>
                <OpeningList list={data.usable} onFocus={onFocus} />
            </details>
            <details>
                <summary>
                    {data.facts.length} facts · {holding} hold
                </summary>
                <ul>
                    {data.facts.map((f, i) => (
                        <li key={i} className={f.holds ? "ok" : "no"}>
                            {f.holds ? "✓" : "✗"} {f.fact} <em>· {f.transports}</em>
                        </li>
                    ))}
                </ul>
            </details>
            <details>
                <summary>
                    {data.rules.length} gate rules · {passing} pass
                </summary>
                <ul>
                    {data.rules.map((r, i) => (
                        <li key={i} className={r.passes ? "ok" : "no"}>
                            {r.passes ? "✓" : "✗"} {r.rule} <em>· {r.transports}</em>
                        </li>
                    ))}
                </ul>
            </details>
            {data.cacheAssumed.length > 0 && (
                <details>
                    <summary>{data.cacheAssumed.length} player-cache values assumed passable</summary>
                    <ul>
                        {data.cacheAssumed.map((k) => (
                            <li key={k}>{k}</li>
                        ))}
                    </ul>
                </details>
            )}
        </div>
    );
}

const NO_STOPS: number[] = [];

// Ticks at irregular `stops`, which the slider's fixed `step` cannot express, so callers snap in
// `onChange`. `live` is the range in which a gate answer can change; outside it the track is dim.
function LevelSlider(props: {
    min: number;
    max: number;
    value: number;
    stops?: number[];
    live?: [number, number];
    onChange: (value: number) => void;
}) {
    const {min, max, stops = NO_STOPS} = props;
    const pct = (level: number) => ((level - min) / (max - min)) * 100;
    const live = props.live ? {left: `${pct(props.live[0])}%`, right: `${100 - pct(props.live[1])}%`} : null;
    return (
        <Slider.Root
            className="planner-slider"
            min={min}
            max={max}
            value={props.value}
            onValueChange={(v) => props.onChange(typeof v === "number" ? v : v[0])}
        >
            <Slider.Control className="planner-slider-control">
                <Slider.Track className="planner-slider-track">
                    {live && <span className="planner-slider-live" style={live} />}
                    {stops.map((s) => (
                        <span key={s} className="planner-slider-tick" style={{left: `${pct(s)}%`}} />
                    ))}
                    <Slider.Indicator className="planner-slider-indicator" />
                    <Slider.Thumb className="planner-slider-thumb" />
                </Slider.Track>
            </Slider.Control>
        </Slider.Root>
    );
}

function Result(props: {
    host: PlannerHost;
    plan: PlanResponse | null;
    loading: boolean;
    error: string | null;
    nameOf: (id: number) => string;
    onHoverStep: (tile: {x: number; z: number} | null) => void;
    onFocusStep: (tile: {x: number; z: number}) => void;
    accountRequest: PlanRequest | null;
}) {
    const {plan, nameOf} = props;
    if (props.error) return <div className="planner-result err">error: {props.error}</div>;
    if (!plan) {
        return <div className="planner-result">{props.loading ? "planning…" : "pick a start and an end tile"}</div>;
    }
    if (!plan.found) {
        const why = plan.why;
        const listed = why?.fixes.filter((f) => f.blockers.length > 0) ?? [];
        const tried = why?.fixes.find((f) => f.hops.length > 0);
        return (
            <div className="planner-result err">
                <div className="planner-norecap">
                    no route{listed.length === 0 && <> — {why?.summary ?? plan.diagnosis}</>}
                </div>
                {listed.map((f, n) => (
                    <div key={n} className="planner-fix">
                        {n > 0 && <div className="planner-fix-head">or</div>}
                        <ul className="planner-blockers">
                            {f.blockers.map((b, i) => (
                                <li key={i}>{b.why}</li>
                            ))}
                        </ul>
                    </div>
                ))}
                {tried && listed.length === 0 && (
                    <div className="planner-blocker-hops">the route it tried: {tried.hops.join(" → ")}</div>
                )}
                {why && !why.complete && (
                    <div className="planner-blocker-hops">…and possibly more — the search stopped here</div>
                )}
                <Timings timings={plan.timings} loading={props.loading} />
            </div>
        );
    }
    const rows = collapseWalks(plan.steps);
    return (
        <div className={`planner-result${props.loading ? " planner-busy" : ""}`}>
            <div className="planner-cost">
                <strong>{plan.ticks} ticks</strong>
                <span>
                    {plan.account?.usableTransports} of {plan.account?.totalTransports} transports{" "}
                    {props.accountRequest && (
                        <Info wide>
                            <AccountBreakdown
                                host={props.host}
                                request={props.accountRequest}
                                onFocus={props.onFocusStep}
                            />
                        </Info>
                    )}
                </span>
            </div>
            <ol className="planner-steps">
                {rows.map((row, i) => (
                    <li
                        key={i}
                        className={`planner-step planner-step-${row.step.type}`}
                        onMouseEnter={() => props.onHoverStep({x: row.step.x, z: row.step.z})}
                        onMouseLeave={() => props.onHoverStep(null)}
                        onClick={() => props.onFocusStep({x: row.step.x, z: row.step.z})}
                        title={`show (${row.step.x}, ${row.step.z}) on the map`}
                    >
                        {row.step.type === "walk" && (
                            <span>
                                walk ×{row.walks} → ({row.step.x}, {row.step.z})
                            </span>
                        )}
                        {row.step.type === "hop" && (
                            <span>
                                {row.step.name} <em>{row.step.cost ?? 0}</em>
                                {row.step.wears.length > 0 && (
                                    <div className="planner-wears">wears: {row.step.wears.map(nameOf).join(", ")}</div>
                                )}
                                {row.step.req && <div className="hop-req">{row.step.req}</div>}
                                <PackDiff changes={row.delta} nameOf={nameOf} />
                            </span>
                        )}
                        {row.step.type === "bank" && (
                            <span>
                                bank stop ({row.step.x}, {row.step.z})
                                {row.step.because.length > 0 && <em> for {row.step.because.join(", ")}</em>}
                                <PackDiff changes={row.delta} nameOf={nameOf} />
                            </span>
                        )}
                        {/* Opening the pack does not also move the map. */}
                        <span className="planner-step-pack" onClick={(e) => e.stopPropagation()}>
                            <Info>
                                <PackGrid pack={row.step.pack} nameOf={nameOf} />
                            </Info>
                        </span>
                    </li>
                ))}
            </ol>
            <Timings timings={plan.timings} loading={props.loading} />
        </div>
    );
}

function builtLine(t: PlanTimings): string {
    const built: string[] = [];
    if (t.botWeightSetsBuilt > 0) {
        built.push(
            `${t.botWeightSetsBuilt} routing-weight set(s) · first plan for an account with` +
                " these usable transports and this danger level",
        );
    }
    if (t.botLegPlanesBuilt > 0) {
        built.push(`${t.botLegPlanesBuilt} distance table(s) · first plan through that part of the map`);
    }
    return built.length === 0
        ? "…answered entirely from what earlier plans built"
        : "…had to build " + built.join("; ");
}

// What drawing the answer cost beyond a bot's own estimate and plan.
function diagMs(t: PlanTimings): number {
    return Math.round((t.totalMs - t.estimateMs - t.botPlanMs) * 100) / 100;
}

function Timings({timings: t, loading}: {timings: PlanTimings; loading: boolean}) {
    const lines = [
        `a bot's estimate: ${t.estimateMs} ms`,
        `a bot's first plan: ${t.botPlanMs} ms`,
        builtLine(t),
        `drawing the walk: ${t.simulatedTicks} ticks · ${t.simulatedPlans} plans` +
            ` + ${t.simulatedWalks} walks in ${t.simulationPlanMs} ms · ` +
            `${t.simulationWalkMs} ms tile search`,
        `counting usable transports: ${t.derivedMs} ms`,
    ];
    if (t.diagnosisMs > 0) {
        lines.push(`no-route diagnosis: ${t.diagnosisMs} ms`);
    }
    return (
        <div className="planner-timings">
            {loading ? "planning… · " : ""}est {t.estimateMs} ms · plan {t.botPlanMs} ms · diag {diagMs(t)} ms{" "}
            <Info title="where the time went" lines={lines} />
        </div>
    );
}

interface PackChange {
    id: number;
    delta: number;
    after: number;
}

function packTotals(pack: PlanPackItem[] | undefined): Map<number, number> {
    const out = new Map<number, number>();
    for (const it of pack ?? []) out.set(it.id, (out.get(it.id) ?? 0) + it.amount);
    return out;
}

function packDelta(before: PlanPackItem[] | undefined, after: PlanPackItem[] | undefined): PackChange[] {
    const a = packTotals(before);
    const b = packTotals(after);
    const out: PackChange[] = [];
    for (const id of new Set([...a.keys(), ...b.keys()])) {
        const delta = (b.get(id) ?? 0) - (a.get(id) ?? 0);
        if (delta !== 0) out.push({id, delta, after: b.get(id) ?? 0});
    }
    return out.sort((x, y) => y.delta - x.delta);
}

function PackDiff({changes, nameOf}: {changes: PackChange[]; nameOf: (id: number) => string}) {
    if (changes.length === 0) return null;
    return (
        <div className="planner-diff">
            {changes.map((c) => {
                const total = c.delta > 0 ? c.after !== c.delta : c.after !== 0;
                return (
                    <span key={c.id} className={c.delta > 0 ? "planner-take" : "planner-give"}>
                        {nameOf(c.id)} {c.delta > 0 ? "+" : "-"}
                        {Math.abs(c.delta)}
                        {total ? ` → ${c.after}` : ""}
                    </span>
                );
            })}
        </div>
    );
}

// One tile per item and worn state: a non-stackable takes a slot per unit.
function collapsePack(pack: PlanPackItem[]): PlanPackItem[] {
    const groups = new Map<string, PlanPackItem>();
    for (const it of pack) {
        const key = `${it.id}:${it.equipped}`;
        const seen = groups.get(key);
        if (seen) {
            seen.amount += it.amount;
        } else {
            groups.set(key, {...it});
        }
    }
    return [...groups.values()];
}

function PackGrid({pack, nameOf}: {pack: PlanPackItem[] | undefined; nameOf: (id: number) => string}) {
    const [atlas, setAtlas] = useState<ItemAtlasIndex | null>(null);
    useEffect(() => {
        let alive = true;
        void getItemAtlas().then((a) => alive && setAtlas(a));
        return () => {
            alive = false;
        };
    }, []);
    if (!pack) return <div className="planner-pack-empty">pack not reported</div>;
    if (pack.length === 0) return <div className="planner-pack-empty">empty pack</div>;
    return (
        <div className="planner-pack">
            {collapsePack(pack).map((it) => (
                <div
                    key={it.slot}
                    className={`planner-pack-slot${it.equipped ? " worn" : ""}`}
                    title={`${nameOf(it.id)}${it.amount > 1 ? ` ×${it.amount}` : ""}` + (it.equipped ? " (worn)" : "")}
                >
                    <ItemIcon atlas={atlas} id={it.id} box={26} />
                    {it.amount > 1 && <span className="planner-pack-n">{it.amount}</span>}
                </div>
            ))}
        </div>
    );
}

function mergeChanges(changes: PackChange[]): PackChange[] {
    const byId = new Map<number, PackChange>();
    for (const c of changes) {
        const prev = byId.get(c.id);
        byId.set(c.id, prev ? {id: c.id, delta: prev.delta + c.delta, after: c.after} : c);
    }
    return [...byId.values()].filter((c) => c.delta !== 0).sort((x, y) => y.delta - x.delta);
}

// Consecutive walk steps fold into one row, and consecutive bank steps at the same booth into one
// visit, since the engine settles one reason at a time.
function collapseWalks(steps: PlanStep[]): {step: PlanStep; walks: number; delta: PackChange[]}[] {
    const out: {step: PlanStep; walks: number; delta: PackChange[]}[] = [];
    steps.forEach((step, i) => {
        const delta = i === 0 ? [] : packDelta(steps[i - 1].pack, step.pack);
        const last = out.at(-1);
        if (step.type === "walk" && last?.step.type === "walk") {
            last.step = step;
            last.walks += 1;
            if (delta.length) last.delta = [...last.delta, ...delta];
        } else if (
            step.type === "bank" &&
            last?.step.type === "bank" &&
            last.step.x === step.x &&
            last.step.z === step.z
        ) {
            last.step = {
                ...step,
                because: [...new Set([...last.step.because, ...step.because])],
            };
            last.delta = mergeChanges([...last.delta, ...delta]);
        } else {
            out.push({step, walks: step.type === "walk" ? 1 : 0, delta});
        }
    });
    return out;
}
