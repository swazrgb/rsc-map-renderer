import {useEffect, useMemo, useRef, useState} from "react";
import {createRoot} from "react-dom/client";
import type {Observer, MapEntity} from "./api";
import {World3DView} from "./World3DView";
import {asset} from "./api";

/**
 * Open-source demo entry. Loads the collision-aware wander tracks baked by
 * map2d's DemoEntityBaker (/api/demo/entities.json) and plays them back on a
 * loop: every NPC spawn walks its real roam box as a SOLID sprite (so with the
 * simulation toggle on, the whole world is alive and no ghosts show), plus a
 * handful of players around Lumbridge. Untick "simulation" to drop the observers
 * and see the raw /api/npc-spawns spawn ghosts. No live server — all static.
 */

interface DemoEntity {
    si: number;      // serverIndex (matches the spawn table, so ghosts hide)
    id?: number;     // npc def id
    a?: string;      // player appearance token
    x: number;       // start tile x
    z: number;       // start tile z (floor folded)
    d: string;       // per-tick delta string (one char = one 8-dir step or pause)
}

interface DemoData {
    tickMs: number;
    names: Record<string, string>; // npc def id -> name
    npcs: DemoEntity[];
    players: DemoEntity[];
}

/** Expand a delta string into absolute per-tick tile positions (looping). */
function expand(e: DemoEntity): Int16Array {
    const n = e.d.length;
    const p = new Int16Array(n * 2);
    let x = e.x;
    let z = e.z;
    p[0] = x;
    p[1] = z;
    for (let t = 1; t < n; t++) {
        const s = e.d.charCodeAt(t - 1) - 48; // transition (t-1) -> t
        x += Math.floor(s / 3) - 1;
        z += (s % 3) - 1;
        p[t * 2] = x;
        p[t * 2 + 1] = z;
    }
    return p;
}

/** Stable empty-observers reference: when the simulation is off there are no
 *  observers, so World3DView draws every spawn as a ghost. */
const NO_OBSERVERS: Observer[] = [];

function Demo({data}: {data: DemoData}) {
    // Precompute every entity's looping position track once.
    const tracks = useMemo(() => ({
        npcs: data.npcs.map(e => ({si: e.si, id: e.id ?? 0, pos: expand(e), n: e.d.length})),
        players: data.players.map(e => ({si: e.si, a: e.a ?? "", pos: expand(e), n: e.d.length})),
    }), [data]);

    const startRef = useRef<number>(0);
    const [tick, setTick] = useState(0);
    // "Simulation" = the live wandering NPCs + demo players. On by default; when
    // off we feed no observers and only the /api/npc-spawns ghosts remain.
    const [simulation, setSimulation] = useState(true);
    useEffect(() => {
        // Discrete server-tick cadence; World3DView lerps between ticks so the
        // walk is smooth. Paused while the simulation toggle is off.
        if (!simulation) return;
        let raf = 0;
        const loop = (t: number) => {
            if (!startRef.current) startRef.current = t;
            setTick(Math.floor((t - startRef.current) / data.tickMs));
            raf = requestAnimationFrame(loop);
        };
        raf = requestAnimationFrame(loop);
        return () => cancelAnimationFrame(raf);
    }, [data.tickMs, simulation]);

    const observers: Observer[] = useMemo(() => {
        if (!simulation) return NO_OBSERVERS;
        const npcs: MapEntity[] = tracks.npcs.map(e => {
            const t = (tick % e.n) * 2;
            return {serverIndex: e.si, id: e.id, name: data.names[e.id] ?? null,
                x: e.pos[t], z: e.pos[t + 1], inCombat: false};
        });
        const players: MapEntity[] = tracks.players.map(e => {
            const t = (tick % e.n) * 2;
            return {serverIndex: e.si, id: 0, name: null,
                x: e.pos[t], z: e.pos[t + 1], inCombat: false, appearance: e.a};
        });
        // One synthetic "observer": no position (so it draws no sprite of its
        // own), carrying the whole live view.
        return [{
            username: "demo", status: "online", scriptClass: null, description: null,
            serverTick: tick, position: null, fatiguePercent: 0,
            sleeping: false, inCombat: false, npcs, players,
        }];
    }, [tracks, tick, simulation, data.names]);

    return (
        <div style={{width: "100vw", height: "100vh"}}>
            <World3DView observers={observers} focus={{x: 122, z: 650}} hideSight
                         extraToggles={
                             <label style={{marginLeft: 8, display: "flex",
                                 alignItems: "center", gap: 4}}>
                                 <input type="checkbox" checked={simulation}
                                        onChange={e => setSimulation(e.target.checked)}/>
                                 simulation
                             </label>
                         }/>
        </div>
    );
}

function App() {
    const [data, setData] = useState<DemoData | null>(null);
    const [err, setErr] = useState<string | null>(null);
    useEffect(() => {
        fetch(asset("/api/demo/entities.json"))
            .then(r => r.ok ? r.json() : Promise.reject(new Error(`${r.status}`)))
            .then(setData)
            .catch(e => setErr(String(e)));
    }, []);
    if (err) {
        return <div style={{width: "100vw", height: "100vh"}}>
            <World3DView observers={[]} focus={{x: 122, z: 650}}/>
        </div>;
    }
    if (!data) return <div style={{padding: 16}}>Loading world…</div>;
    return <Demo data={data}/>;
}

createRoot(document.getElementById("root")!).render(<App/>);
