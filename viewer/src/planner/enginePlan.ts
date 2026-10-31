import type {RoutePoint} from "../api";
import type {EnginePlanStep} from "./plannerApi";

/**
 * The planner's route as a polyline: the start, the middle of each crossing and of the arrival,
 * each hop's entry then its landing (marked `hop`, so the line breaks there), and each bank stop.
 * Null when there is no plan.
 */
export function enginePlanPoints(
    start: {x: number; z: number} | null,
    plan: EnginePlanStep[] | null | undefined,
): RoutePoint[] | null {
    if (!plan || plan.length === 0) return null;
    const out: RoutePoint[] = [];
    if (start) out.push({x: start.x, z: start.z, hop: false});
    for (const s of plan) {
        switch (s.type) {
            case "cross":
            case "arrive":
                out.push({
                    x: Math.round((s.x + (s.x2 ?? s.x)) / 2),
                    z: Math.round((s.z + (s.z2 ?? s.z)) / 2),
                    hop: false,
                });
                break;
            case "hop":
                out.push({x: s.x, z: s.z, hop: false});
                out.push({x: s.x2 ?? s.x, z: s.z2 ?? s.z, hop: true});
                break;
            case "bank":
                out.push({x: s.x, z: s.z, hop: false});
                break;
        }
    }
    return out;
}
