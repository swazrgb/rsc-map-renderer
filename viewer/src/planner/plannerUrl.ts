import {useEffect, useMemo, useRef} from "react";
import type {PlannerInputs} from "./plannerApi";
import {itemRows} from "./LoadoutTable";
import {freshState, type PlannerRequestState, policyOf} from "./PlannerPanel";
import type {Tile} from "./usePlannerSession";

/** The planner's state as the `?plan=` URL parameter carries it; a share of another `v` is rejected. */
interface PlannerShare {
    v: 1;
    from: Tile | null;
    to: Tile | null;
    /** The named account planned as, when the host plans as one. */
    as: string | null;
    state: PlannerRequestState;
}

const PLAN_PARAM = "plan";

/** `value` as base64url-encoded JSON, unpadded: a URL parameter that needs no escaping. */
export function encodeUrlJson(value: unknown): string {
    const bytes = new TextEncoder().encode(JSON.stringify(value));
    let bin = "";
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The JSON value {@link encodeUrlJson} wrote into `s`; throws when `s` is not one. */
export function decodeUrlJson(s: string): unknown {
    const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
    const bytes = Uint8Array.from(bin, (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes));
}

/**
 * Sets the URL parameter `key` to `value`, or removes it for null, keeping every other parameter.
 * Replaces the history entry rather than pushing one.
 */
export function writeUrlParam(key: string, value: string | null): void {
    const p = new URLSearchParams(location.search);
    if (p.get(key) === value) return;
    if (value != null) p.set(key, value);
    else p.delete(key);
    const query = p.toString();
    history.replaceState(null, "", `${location.pathname}${query ? "?" + query : ""}${location.hash}`);
}

/** The share as base64url-encoded JSON, unpadded. */
function encodePlannerShare(share: PlannerShare): string {
    return encodeUrlJson(share);
}

/** The share `s` encodes, or null for anything that is not a well-formed share of this version. */
function decodePlannerShare(s: string): PlannerShare | null {
    try {
        const v = decodeUrlJson(s);
        if (!isObject(v) || v.v !== 1 || !isObject(v.state)) return null;
        const state = v.state;
        if (!isObject(state.levels) || !Array.isArray(state.rows) || !isObject(state.policy)) {
            return null;
        }
        if (
            // The panel indexes these directly, so a hand-edited null would break it on restore.
            (state.equip !== undefined && !isObject(state.equip)) ||
            (state.cache !== undefined && !isObject(state.cache))
        ) {
            return null;
        }
        return {
            v: 1,
            from: tile(v.from),
            to: tile(v.to),
            as: typeof v.as === "string" ? v.as : null,
            state: {...(state as unknown as PlannerRequestState), rows: itemRows(state.rows)},
        };
    } catch {
        return null;
    }
}

function readPlannerShare(): PlannerShare | null {
    const raw = new URLSearchParams(location.search).get(PLAN_PARAM);
    return raw ? decodePlannerShare(raw) : null;
}

/**
 * Sets `?plan=` to `share`, or removes it for null, keeping every other parameter. Replaces the
 * history entry rather than pushing one.
 */
function writePlannerShare(share: PlannerShare | null): void {
    writeUrlParam(PLAN_PARAM, share ? encodePlannerShare(share) : null);
}

/** `inputs` is null until the editor's options have loaded; a share is restored only after that. */
export interface PlannerUrlSyncArgs {
    open: boolean;
    setOpen: (open: boolean) => void;
    inputs: PlannerInputs | null;
    state: PlannerRequestState;
    setState: (state: PlannerRequestState) => void;
    start: Tile | null;
    end: Tile | null;
    setEndpoint: (which: "start" | "end", tile: Tile) => void;
    as?: string | null;
    setAs?: (as: string | null) => void;
}

/**
 * Keeps the planner and `?plan=` in step. A share present at load opens the panel and, once `inputs`
 * arrive, is restored over a fresh editor; from then on every change rewrites the parameter while
 * the panel is open, and closing it removes the parameter.
 */
export function usePlannerUrlSync(args: PlannerUrlSyncArgs): void {
    const {open, setOpen, inputs, state, setState, start, end, setEndpoint, as, setAs} = args;
    const shared = useMemo(() => readPlannerShare(), []);
    const restoredRef = useRef(shared == null);
    useEffect(() => {
        if (shared) setOpen(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    useEffect(() => {
        if (restoredRef.current || !shared || !inputs) return;
        restoredRef.current = true;
        setState({...freshState(inputs), ...shared.state, policy: policyOf(shared.state.policy)});
        if (shared.from) setEndpoint("start", shared.from);
        if (shared.to) setEndpoint("end", shared.to);
        // After the endpoints, in case setting an endpoint clears the account planned as.
        if (setAs && shared.as != null) setAs(shared.as);
    }, [shared, inputs, setState, setEndpoint, setAs]);
    useEffect(() => {
        if (!open) {
            writePlannerShare(null);
            return;
        }
        if (!restoredRef.current || !inputs) return;
        writePlannerShare({v: 1, from: start, to: end, as: as ?? null, state});
    }, [open, inputs, state, start, end, as]);
}

function isObject(v: unknown): v is Record<string, unknown> {
    return typeof v === "object" && v !== null && !Array.isArray(v);
}

function tile(v: unknown): Tile | null {
    return isObject(v) && typeof v.x === "number" && typeof v.z === "number" ? {x: v.x, z: v.z} : null;
}
