import {useCallback, useEffect, useMemo, useState} from "react";
import type {PlannerHost, PlannerInputs, PlanResponse} from "./plannerApi";
import {freshState, maxedState, type PlannerRequestState, toPlanRequest} from "./PlannerPanel";
import {SupersededError} from "./latestSlot";

export interface Tile {
    x: number;
    z: number;
}

interface PlanAsk {
    host: PlannerHost;
    start: Tile;
    end: Tile;
    state: PlannerRequestState;
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

/**
 * One planner's state: the editor's options and contents, the two endpoints, the armed endpoint and
 * the plan. The plan is asked again whenever the endpoints, the editor or the host change, or on
 * `replan`; the previous plan stays shown meanwhile. A map click (`tilePicked`) does nothing unless
 * an endpoint is armed. `copyFrom` takes `"fresh"` or `"maxed"`.
 */
export function usePlannerSession(host: PlannerHost | null) {
    const [inputs, setInputs] = useState<PlannerInputs | null>(null);
    const [state, setState] = useState<PlannerRequestState>(() => freshState(null));
    const [start, setStart] = useState<Tile | null>(null);
    const [end, setEnd] = useState<Tile | null>(null);
    const [pick, setPick] = useState<PlannerPick | null>({which: "start", chain: true});
    const [nonce, setNonce] = useState(0);
    const [inputsError, setInputsError] = useState<string | null>(null);

    useEffect(() => {
        if (!host) return;
        let alive = true;
        host.inputs().then(
            (i) => {
                if (!alive) return;
                setInputs(i);
                setState(freshState(i));
            },
            (e: unknown) => alive && setInputsError(e instanceof Error ? e.message : String(e)),
        );
        return () => {
            alive = false;
        };
    }, [host]);

    const ask = useMemo<PlanAsk | null>(
        () => (host && start && end ? {host, start, end, state, nonce} : null),
        [host, start, end, state, nonce],
    );
    const [answer, setAnswer] = useState<PlanAnswer | null>(null);
    useEffect(() => {
        if (!ask) return;
        let cancelled = false;
        ask.host.plan(toPlanRequest(ask.state, ask.start, ask.end)).then(
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
    const plan = answer?.plan ?? null;
    const error = ask ? (answered ? answer.error : null) : inputsError;

    const setEndpoint = useCallback((which: "start" | "end", tile: Tile) => {
        if (which === "start") setStart(tile);
        else setEnd(tile);
        setPick((p) => (p?.which === which ? null : p));
    }, []);

    const armEndpoint = useCallback((which: "start" | "end") => {
        setPick({which, chain: false});
    }, []);

    const tilePicked = useCallback(
        (tile: Tile) => {
            if (!pick) return;
            if (pick.which === "start") setStart(tile);
            else setEnd(tile);
            setPick(pick.chain && pick.which === "start" ? {which: "end", chain: true} : null);
        },
        [pick],
    );

    const clear = useCallback(() => {
        setStart(null);
        setEnd(null);
        setAnswer(null);
        setInputsError(null);
        setPick({which: "start", chain: true});
    }, []);

    const copyFrom = useCallback(
        (choice: string) => {
            if (choice === "fresh") {
                setState(freshState(inputs));
            } else if (choice === "maxed") {
                setState((s) => maxedState(s, inputs));
            }
        },
        [inputs],
    );

    const replan = useCallback(() => setNonce((n) => n + 1), []);

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
        setEndpoint,
        armEndpoint,
        tilePicked,
        clear,
        copyFrom,
        replan,
    };
}
