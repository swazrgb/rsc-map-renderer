import * as THREE from "three";
import {asset} from "./api";

/**
 * Client-side scenery assembly for the 3D world view.
 *
 * Scenery is no longer baked into the cell meshes: the viewer downloads the
 * object-model LIBRARY (dir-0 anchor-local geometry + per-direction vertex
 * shades) and assembles one merged mesh per 48-tile cell per texture from the
 * static placements — which is what makes LIVE OVERRIDES possible: when a bot
 * observes a different object at a tile (mined-out rock, cut tree, opened
 * chest), that tile's id is swapped and only the affected cell is rebuilt.
 */

export interface LibObject {
    id: number;
    model: number;
    w: number;
    h: number;
    lift: number;
    name: string;
    /** Menu command names from the def (absent = no such action). */
    cmd1?: string;
    cmd2?: string;
}

interface LibModelGroup {
    tex: number;
    n: number;
    pos: Int16Array;      // n*3, dir-0 anchor-local engine units
    base: Uint8Array;     // n*3
    uv: Int16Array | null; // n*2 (*512)
    shades: Uint8Array[]; // [8] of n
}

export class ObjectLibrary {
    readonly objects = new Map<number, LibObject>();
    /** Animated scenery: object id -> frame MODEL ids (fires, torches...),
     *  cycled every 120ms like the client's mudclient_animate_objects. */
    readonly anims = new Map<number, number[]>();
    private readonly models = new Map<number, LibModelGroup[]>();

    static async fetch(baked: number): Promise<ObjectLibrary | null> {
        try {
            const idxR = await fetch(asset("/api/world3d/objlib.json"));
            if (!idxR.ok) return null;
            const idx = await idxR.json() as {
                models: {model: number; off: number; len: number}[];
                objects: LibObject[];
                anims?: {id: number; frames: number[]}[];
            };
            const binR = await fetch(asset(`/api/world3d/objlib.bin?v=${baked}`));
            if (!binR.ok) return null;
            const bin = await binR.arrayBuffer();
            const lib = new ObjectLibrary();
            for (const o of idx.objects) lib.objects.set(o.id, o);
            for (const a of idx.anims ?? []) lib.anims.set(a.id, a.frames);
            for (const m of idx.models) {
                lib.models.set(m.model, parseModel(new DataView(bin, m.off, m.len)));
            }
            return lib;
        } catch {
            return null;
        }
    }

    groupsFor(objectId: number): LibModelGroup[] | null {
        const o = this.objects.get(objectId);
        if (!o) return null;
        return this.models.get(o.model) ?? null;
    }

    groupsForModel(modelId: number): LibModelGroup[] | null {
        return this.models.get(modelId) ?? null;
    }
}

function parseModel(dv: DataView): LibModelGroup[] {
    const groups: LibModelGroup[] = [];
    const ng = dv.getUint16(0);
    let off = 2;
    for (let g = 0; g < ng; g++) {
        const tex = dv.getInt16(off);
        const n = dv.getInt32(off + 2);
        off += 6;
        const pos = new Int16Array(n * 3);
        for (let i = 0; i < n * 3; i++) {
            pos[i] = dv.getInt16(off);
            off += 2;
        }
        const base = new Uint8Array(dv.buffer, dv.byteOffset + off, n * 3).slice();
        off += n * 3;
        let uv: Int16Array | null = null;
        if (tex >= 0) {
            uv = new Int16Array(n * 2);
            for (let i = 0; i < n * 2; i++) {
                uv[i] = dv.getInt16(off);
                off += 2;
            }
        }
        const shades: Uint8Array[] = [];
        for (let d = 0; d < 8; d++) {
            shades.push(new Uint8Array(dv.buffer, dv.byteOffset + off, n).slice());
            off += n;
        }
        groups.push({tex, n, pos, base, uv, shades});
    }
    return groups;
}

/** One resolved placement to assemble: static loc, possibly overridden. */
export interface ResolvedPlacement {
    id: number;
    dir: number;
    x: number; // bot tile
    z: number; // floor-local bot tile
    /** Explicit model override (animation frames: firea2, torcha3...). */
    model?: number;
}

/**
 * Assemble merged BufferGeometries (grouped by texture) for one cell's
 * placements. Coordinates: engine-space model → three.js (x mirrored, y up),
 * matching parseCell's convention exactly.
 */
export function assembleCell(
    lib: ObjectLibrary,
    placements: ResolvedPlacement[],
    worldWidthUnits: number,
    heightAt: (x: number, z: number) => number,
    // Optional GPU-pick id per placement (see World3DView's id-pick). When
    // present, every vertex carries the placement's packed id as a `pickId`
    // vec4-u8 attribute, so an offscreen id pass reads back exactly which
    // object's pixel is under the cursor. Baked here (not a registry) so cell
    // rebuilds can never leave stale ids.
    idFor?: (pl: ResolvedPlacement) => number,
): {tex: number; geometry: THREE.BufferGeometry}[] {
    type Acc = {pos: number[]; shade: number[]; base: number[]; uv: number[];
        pid: number[]};
    const acc = new Map<number, Acc>();
    for (const pl of placements) {
        const obj = lib.objects.get(pl.id);
        const groups = pl.model != null
            ? lib.groupsForModel(pl.model) : lib.groupsFor(pl.id);
        if (!obj || !groups) continue;
        const pid = idFor ? idFor(pl) : 0;
        const pr = pid & 255, pg = (pid >> 8) & 255,
              pb = (pid >> 16) & 255, pa = (pid >> 24) & 255;
        // Footprint (dir-swapped like the engine) → anchor ground point.
        const w = (pl.dir === 0 || pl.dir === 4) ? obj.w : obj.h;
        const h = (pl.dir === 0 || pl.dir === 4) ? obj.h : obj.w;
        const cxUnits = (pl.x * 2 + w) * 64;   // engine-units footprint centre
        const czUnits = (pl.z * 2 + h) * 64;
        // Ground height at the centre (three-y up) + windmill-style lift.
        const groundY = heightAt(cxUnits / 128 - 0.5, czUnits / 128 - 0.5)
            + obj.lift;
        // Engine rotation (rot256 = dir*32): same formula as MeshExporter.
        const yawR = (pl.dir * 32 * 2 * Math.PI) / 256;
        const cos = Math.cos(yawR);
        const sin = Math.sin(yawR);
        for (const g of groups) {
            let a = acc.get(g.tex);
            if (!a) acc.set(g.tex,
                a = {pos: [], shade: [], base: [], uv: [], pid: []});
            const sh = g.shades[pl.dir & 7];
            for (let i = 0; i < g.n; i++) {
                const mx = g.pos[i * 3];
                const my = g.pos[i * 3 + 1];
                const mz = g.pos[i * 3 + 2];
                const rx = mx * cos + mz * sin;
                const rz = mz * cos - mx * sin;
                const ex = cxUnits + rx;          // engine world units
                const ez = czUnits + rz;
                a.pos.push(worldWidthUnits - ex, -my + groundY, ez);
                a.shade.push(sh[i]);
                a.base.push(g.base[i * 3], g.base[i * 3 + 1], g.base[i * 3 + 2]);
                if (g.uv) a.uv.push(g.uv[i * 2] / 512, g.uv[i * 2 + 1] / 512);
                a.pid.push(pr, pg, pb, pa);
            }
        }
    }
    const out: {tex: number; geometry: THREE.BufferGeometry}[] = [];
    for (const [tex, a] of acc) {
        const geo = new THREE.BufferGeometry();
        geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(a.pos), 3));
        geo.setAttribute("shade", new THREE.BufferAttribute(new Float32Array(a.shade), 1));
        geo.setAttribute("base", new THREE.BufferAttribute(new Uint8Array(a.base), 3, true));
        if (tex >= 0) {
            geo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(a.uv), 2));
        }
        if (idFor) {
            geo.setAttribute("pickId",
                new THREE.BufferAttribute(new Uint8Array(a.pid), 4, true));
        }
        out.push({tex, geometry: geo});
    }
    return out;
}

/**
 * The three.js world position of a placement's MODEL ORIGIN (my=0 at the
 * footprint centre, after the object's lift) — i.e. the point the client
 * rotates a model around. Same coordinate mapping as {@link assembleCell}, so
 * subtracting it from that cell's baked geometry recentres the mesh on its hub.
 * Used to spin the windmill sails (id 74) in place.
 */
export function sceneryHub(
    lib: ObjectLibrary, pl: ResolvedPlacement, worldWidthUnits: number,
    heightAt: (x: number, z: number) => number,
): THREE.Vector3 | null {
    const obj = lib.objects.get(pl.id);
    if (!obj) return null;
    const w = (pl.dir === 0 || pl.dir === 4) ? obj.w : obj.h;
    const h = (pl.dir === 0 || pl.dir === 4) ? obj.h : obj.w;
    const cxUnits = (pl.x * 2 + w) * 64;
    const czUnits = (pl.z * 2 + h) * 64;
    const groundY = heightAt(cxUnits / 128 - 0.5, czUnits / 128 - 0.5) + obj.lift;
    return new THREE.Vector3(worldWidthUnits - cxUnits, groundY, czUnits);
}
