import {useEffect, useState} from "react";
import type {PlannerHost, PlannerInputs, PlanResponse} from "./plannerApi";
import {type PlannerRequestState, toPlanRequest} from "./PlannerPanel";
import type {PlannerPick, Tile} from "./usePlannerSession";
import {SupersededError} from "./latestSlot";

/** `pick` is the endpoint a click would place, null while none is armed; `plan` the route in hand. */
export interface PlannerPreviewArgs {
    host: PlannerHost | null;
    pick: PlannerPick | null;
    start: Tile | null;
    end: Tile | null;
    state: PlannerRequestState;
    inputs: PlannerInputs | null;
    as?: string | null;
    hovered: Tile | null;
    plan: PlanResponse | null;
}

/**
 * While one endpoint is being placed with the other already down, asks the host to preview the trip
 * to the hovered tile, and returns that instead of `plan`. `from` and `to` are the endpoints to draw,
 * `shown` the response to draw and `shownFrom` the start it was planned from; with nothing hovered
 * they are the plan in hand's. A host without `preview` gets no preview.
 */
export function usePlannerPreview(args: PlannerPreviewArgs) {
    const {host, pick, start, end, state, as = null, hovered, plan} = args;
    const fixed = pick ? (pick.which === "start" ? end : start) : null;
    const previewing = host?.preview != null && fixed != null;
    // Counts previews, so an answer to one that has ended is never shown.
    const [gen, setGen] = useState(0);
    const [preview, setPreview] = useState<{r: PlanResponse; from: Tile; gen: number} | null>(null);
    const [wasPreviewing, setWasPreviewing] = useState(previewing);
    if (previewing !== wasPreviewing) {
        setWasPreviewing(previewing);
        if (!previewing) {
            setPreview(null);
            setGen((g) => g + 1);
        }
    }
    const cursor = previewing ? hovered : null;
    const from = cursor && pick?.which === "start" ? cursor : start;
    const to = cursor && pick?.which === "end" ? cursor : end;
    const fromX = from?.x;
    const fromZ = from?.z;
    const toX = to?.x;
    const toZ = to?.z;
    const live = cursor != null;
    useEffect(() => {
        if (!live || !host?.preview || fromX == null || fromZ == null || toX == null || toZ == null) return;
        const at = {x: fromX, z: fromZ};
        host.preview(toPlanRequest(state, at, {x: toX, z: toZ}, as)).then(
            (r) => setPreview({r, from: at, gen}),
            (e: unknown) => {
                if (!(e instanceof SupersededError)) console.warn("[planner] preview:", e);
            },
        );
    }, [host, live, fromX, fromZ, toX, toZ, state, as, gen]);
    const current = preview?.gen === gen ? preview : null;
    return {
        previewing,
        from,
        to,
        shown: live ? (current?.r ?? null) : plan,
        shownFrom: live ? (current?.from ?? null) : start,
    };
}
