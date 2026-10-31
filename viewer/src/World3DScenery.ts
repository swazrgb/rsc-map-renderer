import * as THREE from "three";
import {asset} from "./api";

/** A scenery object definition: its model, footprint `w` by `h` tiles at direction 0, and `lift` in engine units. */
export interface LibObject {
    id: number;
    model: number;
    w: number;
    h: number;
    lift: number;
    name: string;
    /** Its menu commands; absent when it has none. */
    cmd1?: string;
    cmd2?: string;
}

// One texture's part of a model: `n` vertices at direction 0, anchor-local in engine units; UVs
// times 512; and a vertex shade per direction.
interface LibModelGroup {
    tex: number;
    n: number;
    pos: Int16Array;
    base: Uint8Array;
    uv: Int16Array | null;
    shades: Uint8Array[];
}

/**
 * The baked scenery model library, from which cells are assembled, so a live override at one tile
 * rebuilds only its cell. `anims` maps an animated object to its frame model ids; `translucent`
 * holds the models the client draws blended with what is behind them.
 */
export class ObjectLibrary {
    readonly objects = new Map<number, LibObject>();
    readonly anims = new Map<number, number[]>();
    readonly translucent = new Set<number>();
    private readonly models = new Map<number, LibModelGroup[]>();

    /** The library of bake `baked`; null when it cannot be loaded. */
    static async fetch(baked: number): Promise<ObjectLibrary | null> {
        try {
            const idxR = await fetch(asset(`/api/world3d/objlib.json?v=${baked}`));
            if (!idxR.ok) return null;
            const idx = (await idxR.json()) as {
                models: {model: number; off: number; len: number}[];
                objects: LibObject[];
                anims?: {id: number; frames: number[]}[];
                translucent?: number[];
            };
            const binR = await fetch(asset(`/api/world3d/objlib.bin?v=${baked}`));
            if (!binR.ok) return null;
            const bin = await binR.arrayBuffer();
            const lib = new ObjectLibrary();
            for (const o of idx.objects) lib.objects.set(o.id, o);
            for (const a of idx.anims ?? []) lib.anims.set(a.id, a.frames);
            for (const m of idx.translucent ?? []) lib.translucent.add(m);
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

    isTranslucent(pl: ResolvedPlacement): boolean {
        const model = pl.model ?? this.objects.get(pl.id)?.model;
        return model != null && this.translucent.has(model);
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

/** A placement to assemble, on a floor-local tile; `model` overrides the object's own, as for an animation frame. */
export interface ResolvedPlacement {
    id: number;
    dir: number;
    x: number;
    z: number;
    model?: number;
}

/**
 * One cell's placements as merged geometries, one per texture and translucency, in three space
 * (x mirrored, y up). `idFor` gives a per-placement pick id, baked into a `pickId` attribute.
 */
export function assembleCell(
    lib: ObjectLibrary,
    placements: ResolvedPlacement[],
    worldWidthUnits: number,
    heightAt: (x: number, z: number) => number,
    idFor?: (pl: ResolvedPlacement) => number,
): {tex: number; translucent: boolean; geometry: THREE.BufferGeometry}[] {
    interface Acc {
        pos: number[];
        shade: number[];
        base: number[];
        uv: number[];
        pid: number[];
    }
    const acc = new Map<string, Acc>();
    for (const pl of placements) {
        const obj = lib.objects.get(pl.id);
        const groups = pl.model != null ? lib.groupsForModel(pl.model) : lib.groupsFor(pl.id);
        if (!obj || !groups) continue;
        const pid = idFor ? idFor(pl) : 0;
        const pr = pid & 255,
            pg = (pid >> 8) & 255,
            pb = (pid >> 16) & 255,
            pa = (pid >> 24) & 255;
        // The footprint turns with the direction; the model's anchor is its centre.
        const w = pl.dir === 0 || pl.dir === 4 ? obj.w : obj.h;
        const h = pl.dir === 0 || pl.dir === 4 ? obj.h : obj.w;
        const cxUnits = (pl.x * 2 + w) * 64;
        const czUnits = (pl.z * 2 + h) * 64;
        const groundY = heightAt(cxUnits / 128 - 0.5, czUnits / 128 - 0.5) + obj.lift;
        const yawR = (pl.dir * 32 * 2 * Math.PI) / 256;
        const cos = Math.cos(yawR);
        const sin = Math.sin(yawR);
        const translucent = lib.isTranslucent(pl);
        for (const g of groups) {
            const key = `${g.tex}|${translucent ? 1 : 0}`;
            let a = acc.get(key);
            if (!a) acc.set(key, (a = {pos: [], shade: [], base: [], uv: [], pid: []}));
            const sh = g.shades[pl.dir & 7];
            for (let i = 0; i < g.n; i++) {
                const mx = g.pos[i * 3];
                const my = g.pos[i * 3 + 1];
                const mz = g.pos[i * 3 + 2];
                const rx = mx * cos + mz * sin;
                const rz = mz * cos - mx * sin;
                const ex = cxUnits + rx;
                const ez = czUnits + rz;
                a.pos.push(worldWidthUnits - ex, -my + groundY, ez);
                a.shade.push(sh[i]);
                a.base.push(g.base[i * 3], g.base[i * 3 + 1], g.base[i * 3 + 2]);
                if (g.uv) a.uv.push(g.uv[i * 2] / 512, g.uv[i * 2 + 1] / 512);
                a.pid.push(pr, pg, pb, pa);
            }
        }
    }
    const out: {tex: number; translucent: boolean; geometry: THREE.BufferGeometry}[] = [];
    for (const [key, a] of acc) {
        const tex = parseInt(key, 10);
        const translucent = key.endsWith("|1");
        const geo = new THREE.BufferGeometry();
        geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(a.pos), 3));
        geo.setAttribute("shade", new THREE.BufferAttribute(new Float32Array(a.shade), 1));
        geo.setAttribute("base", new THREE.BufferAttribute(new Uint8Array(a.base), 3, true));
        if (tex >= 0) {
            geo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(a.uv), 2));
        }
        if (idFor) {
            geo.setAttribute("pickId", new THREE.BufferAttribute(new Uint8Array(a.pid), 4, true));
        }
        out.push({tex, translucent, geometry: geo});
    }
    return out;
}

/** Where a placement's model origin lands in three space, the point the client turns it around. */
export function sceneryHub(
    lib: ObjectLibrary,
    pl: ResolvedPlacement,
    worldWidthUnits: number,
    heightAt: (x: number, z: number) => number,
): THREE.Vector3 | null {
    const obj = lib.objects.get(pl.id);
    if (!obj) return null;
    const w = pl.dir === 0 || pl.dir === 4 ? obj.w : obj.h;
    const h = pl.dir === 0 || pl.dir === 4 ? obj.h : obj.w;
    const cxUnits = (pl.x * 2 + w) * 64;
    const czUnits = (pl.z * 2 + h) * 64;
    const groundY = heightAt(cxUnits / 128 - 0.5, czUnits / 128 - 0.5) + obj.lift;
    return new THREE.Vector3(worldWidthUnits - cxUnits, groundY, czUnits);
}
