import {useCallback, useEffect, useMemo, useState} from "react";
import type {NavPolicy, PlannerHost, PlannerInputs, PlanResponse} from "./plannerApi";
import {freshState, maxedState, type PlannerRequestState, toPlanRequest} from "./PlannerPanel";
import {SupersededError} from "./latestSlot";

export interface Tile {
    x: number;
    z: number;
}

/** A bot the planner can plan as: where it stands, null while unknown, and whether it is walking. */
export interface PlannerSessionBot {
    name: string;
    tile: Tile | null;
    walking: boolean;
}

interface PlanAsk {
    host: PlannerHost;
    start: Tile;
    end: Tile;
    state: PlannerRequestState;
    as: string | null;
    nonce: number;
}

interface PlanAnswer {
    ask: PlanAsk;
    plan: PlanResponse | null;
    error: string | null;
}

/** Which endpoint the next map click sets, and whether setting the start then arms the end. */
export interface PlannerPick {
    which: "start" | "end";
    chain: boolean;
}

// How long a bot stands on a tile before the plan starts from it, so a bot on the move is not planned for at
// every step.
const SETTLE_MS = 1000;

/**
 * One planner's state: the editor's options and contents, the two endpoints, the armed endpoint and
 * the plan. The plan is asked again whenever the endpoints, the editor or the host change, or on
 * `replan`; the previous plan stays shown meanwhile. A map click (`tilePicked`) does nothing unless
 * an endpoint is armed. `copyFrom` takes `"fresh"` or `"maxed"`, and keeps the routing.
 *
 * With a `bot`, the planner plans as it (`as`) until `setAsBot(false)`: from the tile it last stood still on,
 * which is not set while it walks, with its own account; only the end is picked. `policy` is the routing the
 * editor starts with. While `asking` is false, as for a closed planner, nothing is asked and there is no plan;
 * the editor and the endpoints are kept.
 */
export function usePlannerSession(
    host: PlannerHost | null,
    bot: PlannerSessionBot | null = null,
    policy?: NavPolicy,
    asking = true,
) {
    const [inputs, setInputs] = useState<PlannerInputs | null>(null);
    const [state, setState] = useState<PlannerRequestState>(() => freshState(null, policy));
    const [ownStart, setOwnStart] = useState<Tile | null>(null);
    const [end, setEnd] = useState<Tile | null>(null);
    const [ownPick, setOwnPick] = useState<PlannerPick | null>({which: "start", chain: true});
    const [nonce, setNonce] = useState(0);
    const [inputsError, setInputsError] = useState<string | null>(null);
    const [asBotChosen, setAsBotChosen] = useState(true);
    const as = asBotChosen && bot ? bot.name : null;
    const botStart = useSettledTile(bot);

    useEffect(() => {
        if (!host) return;
        let alive = true;
        host.inputs().then(
            (i) => {
                if (!alive) return;
                setInputs(i);
                setState((s) => freshState(i, s.policy));
            },
            (e: unknown) => alive && setInputsError(e instanceof Error ? e.message : String(e)),
        );
        return () => {
            alive = false;
        };
    }, [host]);

    const start = as ? botStart : ownStart;
    // Planning as the bot, only the end is ever picked.
    const endPicked = as != null && (ownPick?.which === "end" || (ownPick != null && !end));
    const pick = useMemo<PlannerPick | null>(
        () => (as == null ? ownPick : endPicked ? {which: "end", chain: false} : null),
        [as, ownPick, endPicked],
    );

    const ask = useMemo<PlanAsk | null>(
        () => (asking && host && start && end ? {host, start, end, state, as, nonce} : null),
        [asking, host, start, end, state, as, nonce],
    );
    const [answer, setAnswer] = useState<PlanAnswer | null>(null);
    useEffect(() => {
        if (!ask) return;
        let cancelled = false;
        ask.host.plan(toPlanRequest(ask.state, ask.start, ask.end, ask.as)).then(
            (r) => {
                if (!cancelled) setAnswer({ask, plan: r, error: null});
            },
            (e: unknown) => {
                if (cancelled || e instanceof SupersededError) return;
                setAnswer({ask, plan: null, error: e instanceof Error ? e.message : String(e)});
            },
        );
        return () => {
            cancelled = true;
        };
    }, [ask]);
    const answered = answer?.ask === ask;
    const loading = ask !== null && !answered;
    // Without a plan to ask, the last answer is for endpoints or an account no longer in play.
    const plan = ask ? (answer?.plan ?? null) : null;
    const error = ask ? (answered ? answer.error : null) : inputsError;

    const setEndpoint = useCallback(
        (which: "start" | "end", tile: Tile) => {
            if (which === "start") {
                if (as) return;
                setOwnStart(tile);
            } else {
                setEnd(tile);
            }
            setOwnPick((p) => (p?.which === which ? null : p));
        },
        [as],
    );

    const armEndpoint = useCallback(
        (which: "start" | "end") => {
            if (which === "start" && as) return;
            setOwnPick({which, chain: false});
        },
        [as],
    );

    const tilePicked = useCallback(
        (tile: Tile) => {
            if (!pick) return;
            if (pick.which === "start") setOwnStart(tile);
            else setEnd(tile);
            setOwnPick(pick.chain && pick.which === "start" ? {which: "end", chain: true} : null);
        },
        [pick],
    );

    const clear = useCallback(() => {
        if (!as) setOwnStart(null);
        setEnd(null);
        setAnswer(null);
        setInputsError(null);
        setOwnPick(as ? {which: "end", chain: false} : {which: "start", chain: true});
    }, [as]);

    const copyFrom = useCallback(
        (choice: string) => {
            if (choice === "fresh") {
                setState((s) => freshState(inputs, s.policy));
            } else if (choice === "maxed") {
                setState((s) => maxedState(s, inputs));
            }
        },
        [inputs],
    );

    const replan = useCallback(() => setNonce((n) => n + 1), []);

    // A custom account with no start yet has the next clicks pick both endpoints.
    const setAsBot = useCallback(
        (on: boolean) => {
            setAsBotChosen(on);
            if (!on && !ownStart) setOwnPick({which: "start", chain: true});
        },
        [ownStart],
    );

    return {
        inputs,
        state,
        setState,
        start,
        end,
        pick,
        plan,
        loading,
        error,
        as,
        setAsBot,
        setEndpoint,
        armEndpoint,
        tilePicked,
        clear,
        copyFrom,
        replan,
    };
}

/**
 * The tile a bot has stood on for a second while not walking, so a bot on the move is not planned for at every
 * step; a newly given bot's at once. Null without a bot, or before its tile is known.
 */
export function useSettledTile(bot: PlannerSessionBot | null): Tile | null {
    const [settled, setSettled] = useState<{name: string; tile: Tile} | null>(null);
    const name = bot?.name ?? null;
    const x = bot?.tile?.x;
    const z = bot?.tile?.z;
    const walking = bot?.walking ?? false;
    // A bot given again after none, or before its tile was known, starts afresh too.
    const [givenFor, setGivenFor] = useState<string | null>(null);
    const given = name != null && x != null && z != null ? name : null;
    if (given !== givenFor) {
        setGivenFor(given);
        if (given != null && x != null && z != null) setSettled({name: given, tile: {x, z}});
    }
    useEffect(() => {
        if (name == null || x == null || z == null || walking) return;
        const t = setTimeout(() => {
            setSettled((s) => (s?.name === name && s.tile.x === x && s.tile.z === z ? s : {name, tile: {x, z}}));
        }, SETTLE_MS);
        return () => clearTimeout(t);
    }, [name, x, z, walking]);
    return name != null && settled?.name === name ? settled.tile : null;
}
