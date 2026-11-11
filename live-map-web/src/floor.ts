import {useCallback, useRef, useState, useEffect, useLayoutEffect} from "react";

type FloorIndex = 0 | 1 | 2 | 3;

const clampFloor = (f: number): FloorIndex => Math.max(0, Math.min(3, Math.trunc(f))) as FloorIndex;

export type FloorSurface = "map" | "world";

type FloorOwner = FloorSurface | "chrome";

export type FloorSetter = (next: number, reason?: string) => void;

interface FloorTransition {
    at: number;
    owner: FloorOwner;
    reason: string;
    from: FloorIndex;
    to: FloorIndex;
    outcome: "applied" | "noop" | "rejected-hidden";
}

export interface FloorController {
    floor: FloorIndex;
    setterFor: (owner: FloorOwner) => FloorSetter;
    journal: () => FloorTransition[];
}

const JOURNAL_CAP = 64;

export function useFloorController(activeSurface: FloorSurface | null, initialFloor = 0): FloorController {
    const [floor, setFloor] = useState<FloorIndex>(() => clampFloor(initialFloor));

    const activeRef = useRef(activeSurface);
    const floorRef = useRef<FloorIndex>(floor);
    useLayoutEffect(() => {
        activeRef.current = activeSurface;
        floorRef.current = floor;
    });
    const journalRef = useRef<FloorTransition[]>([]);

    const request = useCallback((owner: FloorOwner, next: number, reason: string) => {
        const to = clampFloor(next);
        const from = floorRef.current;
        const note = (outcome: FloorTransition["outcome"]) => {
            const j = journalRef.current;
            j.push({at: Date.now(), owner, reason, from, to, outcome});
            if (j.length > JOURNAL_CAP) j.shift();
        };
        if (owner !== "chrome" && owner !== activeRef.current) {
            note("rejected-hidden");
            return;
        }
        if (to === from) {
            note("noop");
            return;
        }
        note("applied");
        floorRef.current = to;
        setFloor(to);
    }, []);

    const settersRef = useRef(new Map<FloorOwner, FloorSetter>());
    const setterFor = useCallback(
        (owner: FloorOwner): FloorSetter => {
            let s = settersRef.current.get(owner);
            if (!s) {
                s = (next: number, reason = "") => request(owner, next, reason);
                settersRef.current.set(owner, s);
            }
            return s;
        },
        [request],
    );

    const journal = useCallback(() => journalRef.current.slice(), []);

    useEffect(() => {
        (window as unknown as {__floorJournal?: () => FloorTransition[]}).__floorJournal = journal;
    }, [journal]);

    return {floor, setterFor, journal};
}
