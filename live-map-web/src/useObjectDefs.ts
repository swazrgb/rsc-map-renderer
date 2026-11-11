import {useEffect, useState} from "react";
import type {ObjectDef} from "./api";

const EMPTY = new Map<number, ObjectDef>();

export function useObjectDefs(): Map<number, ObjectDef> {
    const [defs, setDefs] = useState<Map<number, ObjectDef>>(EMPTY);
    useEffect(() => {
        const controller = new AbortController();
        fetch("/api/world3d/objlib.json", {signal: controller.signal})
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`objlib ${r.status}`))))
            .then((idx: {objects?: ObjectDef[]}) => {
                if (controller.signal.aborted) return;
                const m = new Map<number, ObjectDef>();
                for (const o of idx.objects ?? []) m.set(o.id, o);
                setDefs(m);
            })
            .catch(() => {
                // Defs stay empty (or the view went away); consumers fall back to "#<id>".
            });
        return () => controller.abort();
    }, []);
    return defs;
}
