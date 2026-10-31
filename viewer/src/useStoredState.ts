import {useEffect, useState, type Dispatch, type SetStateAction} from "react";

/**
 * `useState` persisted in `localStorage[key]`: read on first render, written on every change. Any
 * storage or parse failure falls back to `initial`. When both the stored value and `initial` are
 * plain objects the stored one is spread over `initial`, so a field added later keeps its default.
 */
export function useStoredState<T>(key: string, initial: T): [T, Dispatch<SetStateAction<T>>] {
    const [value, setValue] = useState<T>(() => {
        try {
            const raw = localStorage.getItem(key);
            if (raw == null) return initial;
            const parsed: unknown = JSON.parse(raw);
            if (isPlainObject(initial) && isPlainObject(parsed)) {
                return {...(initial as object), ...(parsed as object)} as T;
            }
            return parsed as T;
        } catch {
            return initial;
        }
    });
    useEffect(() => {
        try {
            localStorage.setItem(key, JSON.stringify(value));
        } catch {
            // nothing to do — the in-memory value still works for this session
        }
    }, [key, value]);
    return [value, setValue];
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
    return typeof v === "object" && v !== null && !Array.isArray(v);
}
