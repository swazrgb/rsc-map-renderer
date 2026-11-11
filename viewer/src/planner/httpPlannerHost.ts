import {latestSlot} from "./latestSlot";
import type {PlannerAccount, PlannerHost, PlannerInputs, PlannerReach, PlanRequest, PlanResponse} from "./plannerApi";

async function post<T>(path: string, req: PlanRequest): Promise<T> {
    const r = await fetch(path, {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(req),
    });
    if (!r.ok) throw new Error(`${path} → ${r.status}`);
    return (await r.json()) as T;
}

export async function fetchPlannerInputs(): Promise<PlannerInputs> {
    const r = await fetch("/api/pathfinder/inputs");
    if (!r.ok) throw new Error(`/api/pathfinder/inputs → ${r.status}`);
    return (await r.json()) as PlannerInputs;
}

export function planRoute(req: PlanRequest): Promise<PlanResponse> {
    return post("/api/pathfinder/plan", req);
}

/** The route planner of a server that answers `/api/pathfinder`. */
export const httpPlannerHost: PlannerHost = {
    inputs: fetchPlannerInputs,
    plan: planRoute,
    preview: latestSlot((req: PlanRequest) => post<PlanResponse>("/api/pathfinder/preview", req)),
    account: (req) => post<PlannerAccount>("/api/pathfinder/account", req),
    reachable: (req) => post<PlannerReach>("/api/pathfinder/reachable", req),
};
