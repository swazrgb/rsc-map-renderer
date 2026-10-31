import {useEffect, useState} from "react";
import {decodeUrlJson, encodeUrlJson, writeUrlParam} from "../planner/plannerUrl";
import {byId, DEFAULT_SETTINGS, isSleep, type CampItem, type CampSettings} from "./campApi";
import {CLIENTS, type Client} from "./generate";

/** Everything the generate window holds: the spawns to camp, by server index, and the settings. */
export interface CampState {
    targets: number[];
    settings: CampSettings;
}

/** The window's state as the `?gen=` URL parameter carries it; a share of another `v` is rejected. */
interface CampShare extends CampState {
    v: 1;
}

const CAMP_PARAM = "gen";

const STORAGE_KEY = "scriptgen";

const FRESH_CAMP: CampState = {targets: [], settings: DEFAULT_SETTINGS};

function encodeCampShare(state: CampState): string {
    return encodeUrlJson({v: 1, ...state} satisfies CampShare);
}

/**
 * The state `s` encodes, or null for anything that is not a share of this version. Fields that are
 * missing or of the wrong type take their defaults, and malformed list entries are dropped.
 */
function decodeCampShare(s: string): CampState | null {
    try {
        const v = decodeUrlJson(s);
        return isObject(v) && v.v === 1 ? campState(v) : null;
    } catch {
        return null;
    }
}

/**
 * The generate window's state and whether it is open. A `?gen=` share present at load opens the
 * window with its state; without one, the state is what this browser last had. From then on every
 * change is stored and, while the window is open, written to `?gen=`; closing removes the parameter.
 */
export function useCampSession() {
    const [open, setOpen] = useState(() => new URLSearchParams(location.search).has(CAMP_PARAM));
    const [state, setState] = useState<CampState>(() => {
        const raw = new URLSearchParams(location.search).get(CAMP_PARAM);
        return (raw ? decodeCampShare(raw) : null) ?? stored() ?? FRESH_CAMP;
    });
    useEffect(() => {
        try {
            localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
        } catch {
            // nothing to do — the in-memory state still works for this session
        }
    }, [state]);
    useEffect(() => {
        writeUrlParam(CAMP_PARAM, open ? encodeCampShare(state) : null);
    }, [open, state]);
    return {open, setOpen, state, setState};
}

function stored(): CampState | null {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        return raw == null ? null : campState(JSON.parse(raw));
    } catch {
        return null;
    }
}

function campState(v: unknown): CampState | null {
    if (!isObject(v)) return null;
    const s = isObject(v.settings) ? v.settings : {};
    const d = DEFAULT_SETTINGS;
    const client = CLIENTS.find((c) => c.id === s.client)?.id;
    return {
        targets: ints(v.targets),
        settings: byId({
            items: Array.isArray(s.items) ? s.items.flatMap(item) : d.items,
            food: Array.isArray(s.food) ? ints(s.food) : d.food,
            eat: isInt(s.eat) ? s.eat : null,
            sleep: s.sleep === null ? null : isSleep(s.sleep) ? s.sleep : d.sleep,
            style: isInt(s.style) ? s.style : d.style,
            loot: Array.isArray(s.loot) ? ints(s.loot) : d.loot,
            bury: Array.isArray(s.bury) ? ints(s.bury) : d.bury,
            client: client ?? (d.client satisfies Client),
        }),
    };
}

function item(v: unknown): CampItem[] {
    if (!isObject(v) || !isInt(v.id) || !isInt(v.min) || !isInt(v.target)) return [];
    return [{id: v.id, min: v.min, target: v.target, wear: v.wear === true}];
}

// Each id once, none negative: a recipe names each once, and a negative one indexes nothing.
function ints(v: unknown): number[] {
    return Array.isArray(v) ? [...new Set(v.filter(isInt).filter((n) => n >= 0))] : [];
}

function isInt(v: unknown): v is number {
    return typeof v === "number" && Number.isInteger(v);
}

function isObject(v: unknown): v is Record<string, unknown> {
    return typeof v === "object" && v !== null && !Array.isArray(v);
}
