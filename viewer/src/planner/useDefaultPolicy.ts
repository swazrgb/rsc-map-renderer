import {useCallback, useState} from "react";
import type {NavPolicy} from "./plannerApi";
import {DEFAULT_POLICY, policyOf} from "./PlannerPanel";

const KEY = "routePlanner.defaultPolicy";

/**
 * The policy a page's walks take unless the route planner sets one: nav's default until the viewer sets
 * another, which this browser then keeps for the page. Storage that cannot be read or written leaves nav's.
 */
export function useDefaultPolicy(): [NavPolicy, (policy: NavPolicy) => void] {
    const [policy, setPolicy] = useState<NavPolicy>(read);
    const set = useCallback((next: NavPolicy) => {
        setPolicy(next);
        try {
            localStorage.setItem(KEY, JSON.stringify(next));
        } catch {
            // Kept for this page's life only.
        }
    }, []);
    return [policy, set];
}

function read(): NavPolicy {
    try {
        const raw = localStorage.getItem(KEY);
        return raw ? policyOf(JSON.parse(raw)) : DEFAULT_POLICY;
    } catch {
        return DEFAULT_POLICY;
    }
}
