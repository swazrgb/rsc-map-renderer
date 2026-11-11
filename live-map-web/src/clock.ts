import {useSyncExternalStore} from "react";
import {dayKey} from "./format";

interface Clock {
    now: number;
    listeners: Set<() => void>;
    timer: number | undefined;
    subscribe: (onTick: () => void) => () => void;
    snapshot: () => number;
}

const clocks = new Map<number, Clock>();

function clockFor(periodMs: number): Clock {
    let clock = clocks.get(periodMs);
    if (clock) return clock;
    const c: Clock = {
        now: Date.now(),
        listeners: new Set(),
        timer: undefined,
        subscribe: (onTick) => {
            c.listeners.add(onTick);
            if (c.timer === undefined) {
                c.now = Date.now();
                c.timer = window.setInterval(() => {
                    c.now = Date.now();
                    for (const l of c.listeners) l();
                }, periodMs);
            }
            return () => {
                c.listeners.delete(onTick);
                if (c.listeners.size === 0) {
                    window.clearInterval(c.timer);
                    c.timer = undefined;
                }
            };
        },
        snapshot: () => c.now,
    };
    clock = c;
    clocks.set(periodMs, clock);
    return clock;
}

let serverSkewMs = 0;

/** How far the server's clock is ahead of this browser's. */
export function setServerSkew(ms: number): void {
    serverSkewMs = ms;
}

/** The server's wall clock, as {@link useNow} ticks. */
export function useServerNow(periodMs: number): number {
    return useNow(periodMs) + serverSkewMs;
}

export function useNow(periodMs: number): number {
    const clock = clockFor(periodMs);
    return useSyncExternalStore(clock.subscribe, clock.snapshot);
}

// A timeout stops counting while the machine sleeps, so a long one can wake hours past midnight.
const MIDNIGHT_RECHECK_MS = 60_000;

function subscribeMidnight(onMidnight: () => void): () => void {
    let timer = 0;
    const arm = () => {
        const now = Date.now();
        const today = new Date(now);
        const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
        timer = window.setTimeout(
            () => {
                onMidnight();
                arm();
            },
            Math.min(midnight.getTime() - now, MIDNIGHT_RECHECK_MS),
        );
    };
    arm();
    return () => window.clearTimeout(timer);
}

function todaySnapshot(): string {
    return dayKey(Date.now());
}

/** This browser's local date, as {@link dayKey} writes it; re-renders at local midnight. */
export function useToday(): string {
    return useSyncExternalStore(subscribeMidnight, todaySnapshot);
}
