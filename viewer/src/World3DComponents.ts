import * as THREE from "three";
import {versioned} from "./api";
import {drapeGate} from "./World3DOverlays";
import type {OverlayContext, OverlayPoint, ViewerOverlay} from "./World3DOverlays";
import type {Ribbon} from "./World3DSight";
import type {TagPool} from "./World3DTags";
import {need} from "./need";

/**
 * One floor's walking component ids: tiles of one component are connected on foot, tiles of
 * different ones are not. `ids` is row-major over `width` by `height` tiles, z floor-local, 0 for
 * blocked ground; `count` is how many components there are.
 */
export interface FloorComponents {
    width: number;
    height: number;
    count: number;
    ids: Uint16Array;
}

// One floor's border runs (absolute z, on tile edges), for each component the runs that border it,
// and its label tile nearest its middle with more of its tiles on a coarse grid to fall back on.
interface FloorShapes {
    floor: number;
    data: FloorComponents;
    chains: {pts: {x: number; z: number}[]; closed: boolean}[];
    byId: Map<number, number[]>;
    labels: {id: number; x: number; z: number; samples: Int32Array}[];
}

const FLOOR_HEIGHT = 944;
const BASE = "rgb(126, 231, 135)";
const LIT = "rgb(200, 255, 208)";
// Border runs are cut this short so they follow the terrain between their ends.
const SEGMENT_TILES = 4;
const SAMPLE_TILES = 6;
// How far in from the screen's edge, in NDC, a label spot must be.
const SCREEN_INSET = 0.94;

const loaded = new Map<number, Promise<FloorComponents | null>>();

/** `floor`'s component ids, fetched once; null where there are none to fetch. */
export function fetchFloorComponents(floor: number): Promise<FloorComponents | null> {
    let p = loaded.get(floor);
    if (!p) {
        p = versioned(`/api/map/floor/${floor}.components.bin`)
            .then((url) => fetch(url))
            .then(async (r) => {
                if (!r.ok) return null;
                const buf = await r.arrayBuffer();
                const head = new DataView(buf, 0, 12);
                const width = head.getInt32(0, true);
                const height = head.getInt32(4, true);
                const count = head.getInt32(8, true);
                if (buf.byteLength < 12 + width * height * 2) return null;
                // Copied out, so the array is aligned whatever the buffer's offset.
                const ids = new Uint16Array(buf.slice(12, 12 + width * height * 2));
                return {width, height, count, ids};
            })
            .catch((e: unknown) => {
                console.warn(`[components] floor ${floor}:`, e);
                return null;
            });
        loaded.set(floor, p);
    }
    return p;
}

/**
 * Outlines every walking component on the floor in view, wherever two neighbouring tiles differ
 * (blocked ground included), with its id at its middle, or at the spot of it on screen nearest the
 * view's middle. Its data filters: null shows every component, a set only those.
 */
export function componentsOverlay(): ViewerOverlay<ReadonlySet<number> | null> {
    let ribbon: Ribbon | null = null;
    let lit: Ribbon | null = null;
    let labels: TagPool | null = null;
    const stale = drapeGate();
    const shapes = new Map<number, FloorShapes | null>();
    let hovered: number | null = null;
    let litFor: number | null = null;
    let view: {shown: FloorShapes | null; only: ReadonlySet<number> | null; chains: FloorShapes["chains"]} | null =
        null;
    let filter: ReadonlySet<number> | null = null;
    return {
        mount(ctx: OverlayContext) {
            ribbon = ctx.ribbon({color: 0x7ee787, opacity: 0.55, ghostOpacity: 0.15});
            lit = ctx.ribbon({color: 0xc8ffd0, opacity: 1, ghostOpacity: 0.4, band: "aboveGround"});
            litFor = null;
            labels = ctx.tags({
                css: "font:600 12px monospace;color:#7ee787;" + "text-shadow:0 1px 2px rgba(0,0,0,.9);",
                anchor: "centre",
                interactive: false,
            });
        },
        frame(ctx: OverlayContext, only: ReadonlySet<number> | null) {
            const floor = ctx.floor;
            filter = only;
            if (!shapes.has(floor)) {
                shapes.set(floor, null);
                void fetchFloorComponents(floor).then((d) => shapes.set(floor, d ? shapesOf(d, floor) : null));
            }
            const shown = shapes.get(floor) ?? null;
            if (view?.shown !== shown || view.only !== only) {
                view = {shown, only, chains: filtered(shown, only)};
            }
            const redrape = stale(ctx, view);
            if (redrape) {
                ribbon?.extrude(view.chains, ctx.toWorld);
            }
            if (redrape || hovered !== litFor) {
                litFor = hovered;
                const own = hovered == null ? undefined : shown?.byId.get(hovered);
                lit?.extrude(own && shown ? own.map((i) => shown.chains[i]) : [], ctx.toWorld);
            }
            labels?.begin(ctx.view, ctx.camera);
            for (const l of shown?.labels ?? []) {
                if (only && !only.has(l.id)) continue;
                const at = labelSpot(ctx, l);
                if (!at) continue;
                const tag = labels?.put(String(l.id), at, `${l.id}`);
                const colour = l.id === hovered ? LIT : BASE;
                if (tag && tag.style.color !== colour) tag.style.color = colour;
            }
            labels?.end();
        },
        hitTest(at: OverlayPoint): string | null {
            const d = shapes.get(at.floor)?.data;
            if (!d) return null;
            const x = Math.round(at.x);
            const z = Math.round(at.z) - at.floor * FLOOR_HEIGHT;
            if (x < 0 || z < 0 || x >= d.width || z >= d.height) return null;
            const id = d.ids[z * d.width + x];
            return id === 0 || (filter && !filter.has(id)) ? null : String(id);
        },
        hover(hit: string | null) {
            hovered = hit == null ? null : Number(hit);
        },
    };
}

function filtered(shown: FloorShapes | null, only: ReadonlySet<number> | null): FloorShapes["chains"] {
    if (!shown) return [];
    if (!only) return shown.chains;
    const keep = new Set<number>();
    for (const id of only) {
        for (const i of shown.byId.get(id) ?? []) keep.add(i);
    }
    return [...keep].sort((a, b) => a - b).map((i) => shown.chains[i]);
}

const spot = new THREE.Vector3();
const ndc = new THREE.Vector3();

function labelSpot(ctx: OverlayContext, l: FloorShapes["labels"][number]): THREE.Vector3 | null {
    if (onScreen(ctx, l.x, l.z)) return ctx.toWorld(l.x, l.z, spot);
    let best = Infinity;
    let bx = 0;
    let bz = 0;
    for (let i = 0; i < l.samples.length; i += 2) {
        const x = l.samples[i];
        const z = l.samples[i + 1];
        if (!onScreen(ctx, x, z)) continue;
        const d = ndc.x * ndc.x + ndc.y * ndc.y;
        if (d < best) {
            best = d;
            bx = x;
            bz = z;
        }
    }
    return best < Infinity ? ctx.toWorld(bx, bz, spot) : null;
}

function onScreen(ctx: OverlayContext, x: number, z: number): boolean {
    ctx.toWorld(x, z, ndc).project(ctx.camera);
    return ndc.z >= -1 && ndc.z <= 1 && Math.abs(ndc.x) <= SCREEN_INSET && Math.abs(ndc.y) <= SCREEN_INSET;
}

// An edge is a border where the ids on its two sides differ; edges along one grid line merge into a
// run while the pair of ids stays the same, so each run borders exactly two things.
function shapesOf(d: FloorComponents, floor: number): FloorShapes {
    const {width: w, height: h} = d;
    const owner = d.ids;
    const base = floor * FLOOR_HEIGHT;
    const chains: FloorShapes["chains"] = [];
    const byId = new Map<number, number[]>();
    const push = (a: number, b: number, x0: number, z0: number, x1: number, z1: number) => {
        const len = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        const pieces = Math.max(1, Math.ceil(len / SEGMENT_TILES));
        const pts: {x: number; z: number}[] = [];
        for (let k = 0; k <= pieces; k++) {
            pts.push({x: x0 + ((x1 - x0) * k) / pieces, z: base + z0 + ((z1 - z0) * k) / pieces});
        }
        const index = chains.length;
        chains.push({pts, closed: false});
        for (const id of [a, b]) {
            if (id === 0) continue;
            let list = byId.get(id);
            if (!list) byId.set(id, (list = []));
            list.push(index);
        }
    };
    for (let x = 0; x + 1 < w; x++) {
        let start = -1;
        let ra = 0;
        let rb = 0;
        for (let z = 0; z <= h; z++) {
            const a = z < h ? owner[z * w + x] : 0;
            const b = z < h ? owner[z * w + x + 1] : 0;
            const on = z < h && a !== b;
            if (start >= 0 && (!on || a !== ra || b !== rb)) {
                push(ra, rb, x + 0.5, start - 0.5, x + 0.5, z - 0.5);
                start = -1;
            }
            if (on && start < 0) {
                start = z;
                ra = a;
                rb = b;
            }
        }
    }
    for (let z = 0; z + 1 < h; z++) {
        let start = -1;
        let ra = 0;
        let rb = 0;
        for (let x = 0; x <= w; x++) {
            const a = x < w ? owner[z * w + x] : 0;
            const b = x < w ? owner[(z + 1) * w + x] : 0;
            const on = x < w && a !== b;
            if (start >= 0 && (!on || a !== ra || b !== rb)) {
                push(ra, rb, start - 0.5, z + 0.5, x - 0.5, z + 0.5);
                start = -1;
            }
            if (on && start < 0) {
                start = x;
                ra = a;
                rb = b;
            }
        }
    }
    const sums = new Map<number, {sx: number; sz: number; n: number}>();
    for (let i = 0; i < d.ids.length; i++) {
        const id = d.ids[i];
        if (id === 0) continue;
        const s = sums.get(id) ?? {sx: 0, sz: 0, n: 0};
        s.sx += i % w;
        s.sz += Math.floor(i / w);
        s.n++;
        sums.set(id, s);
    }
    const best = new Map<number, {x: number; z: number; dist: number}>();
    const grid = new Map<number, number[]>();
    for (let i = 0; i < d.ids.length; i++) {
        const id = d.ids[i];
        if (id === 0) continue;
        const s = need(sums.get(id), "component centre sums");
        const x = i % w;
        const z = Math.floor(i / w);
        const dist = (x - s.sx / s.n) ** 2 + (z - s.sz / s.n) ** 2;
        const b = best.get(id);
        if (!b || dist < b.dist) best.set(id, {x, z, dist});
        if (x % SAMPLE_TILES === 0 && z % SAMPLE_TILES === 0) {
            let g = grid.get(id);
            if (!g) grid.set(id, (g = []));
            g.push(x, base + z);
        }
    }
    const labels = [...best].map(([id, b]) => ({
        id,
        x: b.x,
        z: base + b.z,
        samples: Int32Array.from(grid.get(id) ?? []),
    }));
    return {floor, data: d, chains, byId, labels};
}
