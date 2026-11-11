import {useEffect, useSyncExternalStore} from "react";

/** "watched": every detail field, each change; "listed": the first line of the description only. */
export type InterestTier = "watched" | "listed";

export interface BotInterest {
    watched: readonly string[];
    listed: readonly string[];
}

const entries = new Map<number, {tier: InterestTier; names: readonly string[]}>();
const listeners = new Set<() => void>();
let nextId = 0;
let queued = false;
let current: BotInterest = {watched: [], listed: []};
let currentKey = "";

function recompute() {
    queued = false;
    const watched = new Set<string>();
    const listed = new Set<string>();
    for (const e of entries.values()) {
        for (const n of e.names) (e.tier === "watched" ? watched : listed).add(n);
    }
    for (const n of watched) listed.delete(n);
    const next = {watched: [...watched].sort(), listed: [...listed].sort()};
    const key = next.watched.join("\n") + "\u0000" + next.listed.join("\n");
    if (key === currentKey) return;
    currentKey = key;
    current = next;
    for (const l of listeners) l();
}

function schedule() {
    if (queued) return;
    queued = true;
    queueMicrotask(recompute);
}

/** Declares the bots a mounted view shows, for as long as it shows them. */
export function useBotInterest(names: readonly string[], tier: InterestTier): void {
    const key = names.join("\n");
    useEffect(() => {
        const id = nextId++;
        entries.set(id, {tier, names: key ? key.split("\n") : []});
        schedule();
        return () => {
            entries.delete(id);
            schedule();
        };
    }, [key, tier]);
}

function subscribe(l: () => void): () => void {
    listeners.add(l);
    return () => listeners.delete(l);
}

/** Every bot some mounted view shows, by tier; a bot both watched and listed is watched. */
export function useBotInterests(): BotInterest {
    return useSyncExternalStore(subscribe, () => current);
}
