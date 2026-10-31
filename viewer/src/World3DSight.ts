import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import {uploadQuads} from "./bufferUpload";
import {need as needed} from "./need";

// The server shows NPCs and players within (int) sqrt(dx² + dz²) <= 15 tiles, view_distance 2 times 8
// less 1: a circle of radius sqrt(255).
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L216
const VIEW_R = Math.sqrt(255);
// Circle polyline step, in radians.
const STEP = Math.PI / 36;
// Box edges are cut at most this long, in tiles, so the ribbon drapes.
const BOX_SEG_TILES = 4;
// Ribbon half-width in tiles: 5 world units.
const HALF_T = 5 / 128;
export const RIBBON_HALF_WIDTH = HALF_T;
// Floor on the cosine of a corner's half angle, so a miter is at most 2.5 times the width.
const MITER_COS_MIN = 0.4;
const LIFT = 6;

interface Pt {
    x: number;
    z: number;
}

export interface Chain {
    pts: Pt[];
    closed: boolean;
}

/** A draped outline: a solid pass, and a faint pass where something hides it, over shared geometry. */
export class Ribbon {
    readonly solid: THREE.Mesh;
    readonly ghost: THREE.Mesh;
    private readonly geo: THREE.BufferGeometry;
    private cap = 0;

    constructor(
        private readonly parent: THREE.Object3D,
        color: number,
        opacity: number,
        ghostOpacity: number,
        name = "",
    ) {
        this.geo = new THREE.BufferGeometry();
        this.grow(4096);
        this.solid = new THREE.Mesh(
            this.geo,
            new THREE.MeshBasicMaterial({
                color,
                transparent: true,
                opacity,
                polygonOffset: true,
                polygonOffsetFactor: -2,
                polygonOffsetUnits: -2,
                depthWrite: false,
                side: THREE.DoubleSide,
            }),
        );
        this.solid.renderOrder = 9;
        this.ghost = new THREE.Mesh(
            this.geo,
            new THREE.MeshBasicMaterial({
                color,
                transparent: true,
                opacity: ghostOpacity,
                depthFunc: THREE.GreaterDepth,
                depthWrite: false,
                side: THREE.DoubleSide,
            }),
        );
        this.ghost.renderOrder = 9;
        // Named so a ribbon can be found in the scene graph by a headless check.
        this.solid.name = name;
        this.ghost.name = name ? name + "Xray" : "";
        for (const m of [this.solid, this.ghost]) {
            m.frustumCulled = false;
            m.userData.noPick = true;
            parent.add(freezeTransform(m));
        }
    }

    set visible(v: boolean) {
        this.solid.visible = v;
        this.ghost.visible = v;
    }

    /** Restyles in place, without rebuilding the geometry. */
    setStyle(color: number, opacity: number, ghostOpacity: number): void {
        const solid = this.solid.material as THREE.MeshBasicMaterial;
        const ghost = this.ghost.material as THREE.MeshBasicMaterial;
        solid.color.setHex(color);
        ghost.color.setHex(color);
        solid.opacity = opacity;
        ghost.opacity = ghostOpacity;
    }

    /**
     * The draw band, as renderOrder in the transparent pass: 9 is ground decoration, under the sprites at 12 and 13.
     */
    set band(order: number) {
        this.solid.renderOrder = order;
        this.ghost.renderOrder = order;
    }

    private grow(quads: number) {
        this.cap = quads;
        this.geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(quads * 4 * 3), 3));
        const idx = new Uint32Array(quads * 6);
        for (let i = 0; i < quads; i++) {
            idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        this.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        this.geo.setDrawRange(0, 0);
    }

    /**
     * Rebuilds the ribbon along `chains` (tile coordinates), `halfWidth` tiles either side, mitered at
     * the corners and draped through `toWorld` at every fold of the terrain it crosses. The buffer grows
     * as needed and never shrinks.
     */
    extrude(chains: Chain[], toWorld: (x: number, z: number) => THREE.Vector3, halfWidth = HALF_T) {
        const prepared = chains.map((c) => ({
            pts: alongGround(distinct(c.pts, c.closed), c.closed),
            closed: c.closed,
        }));
        const need = prepared.reduce((n, c) => n + c.pts.length, 0);
        if (need > this.cap) {
            this.grow(Math.ceil(need * 1.5));
        }
        const pos = this.geo.getAttribute("position") as THREE.BufferAttribute;
        const at = pos.array as Float32Array;
        let q = 0;
        for (const chain of prepared) {
            const pts = chain.pts;
            const n = pts.length;
            if (n < (chain.closed ? 3 : 2)) continue;
            const left: THREE.Vector3[] = [];
            const right: THREE.Vector3[] = [];
            for (let k = 0; k < n; k++) {
                const hasPrev = chain.closed || k > 0;
                const hasNext = chain.closed || k < n - 1;
                const prev = pts[(k - 1 + n) % n];
                const next = pts[(k + 1) % n];
                const back = hasPrev ? unit(pts[k].x - prev.x, pts[k].z - prev.z) : null;
                const fwd = hasNext ? unit(next.x - pts[k].x, next.z - pts[k].z) : null;
                const d1 = needed(back ?? fwd, "sight ribbon segment direction");
                const d2 = fwd ?? d1;
                const n1 = {x: -d1.z, z: d1.x};
                const n2 = {x: -d2.z, z: d2.x};
                const m = unit(n1.x + n2.x, n1.z + n2.z) ?? n1;
                const cosHalf = Math.max(MITER_COS_MIN, Math.abs(m.x * n1.x + m.z * n1.z));
                const s = halfWidth / cosHalf;
                const l = toWorld(pts[k].x + m.x * s, pts[k].z + m.z * s);
                const r = toWorld(pts[k].x - m.x * s, pts[k].z - m.z * s);
                l.y += LIFT;
                r.y += LIFT;
                left.push(l);
                right.push(r);
            }
            const segs = chain.closed ? n : n - 1;
            for (let s = 0; s < segs && q < this.cap; s++) {
                const a = s;
                const b = (s + 1) % n;
                at.set(
                    [
                        left[a].x,
                        left[a].y,
                        left[a].z,
                        right[a].x,
                        right[a].y,
                        right[a].z,
                        right[b].x,
                        right[b].y,
                        right[b].z,
                        left[b].x,
                        left[b].y,
                        left[b].z,
                    ],
                    q * 12,
                );
                q++;
            }
        }
        uploadQuads(this.geo, q, "position");
        this.geo.setDrawRange(0, q * 6);
    }

    dispose() {
        for (const m of [this.solid, this.ghost]) {
            this.parent.remove(m);
            (m.material as THREE.Material).dispose();
        }
        this.geo.dispose();
    }
}

/**
 * What the server streams to the given bots: NPCs and players within a circle of radius sqrt(255)
 * tiles round each, drawn brighter; scenery, wall objects and ground items within 2 cells of 8 by
 * 8 tiles, a grid-aligned box that jumps 8 tiles as a bot crosses a cell edge, drawn fainter.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L222 Point.withinGridRange
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/uranium.conf#L104
 */
export class SightLayer {
    private readonly mob: Ribbon;
    private readonly grid: Ribbon;

    constructor(scene: THREE.Scene) {
        this.mob = new Ribbon(scene, 0x9fd8ff, 0.42, 0.1, "sightMob");
        this.grid = new Ribbon(scene, 0x9fd8ff, 0.18, 0.05, "sightGrid");
    }

    set visible(v: boolean) {
        this.mob.visible = v;
        this.grid.visible = v;
    }

    /** Rebuilds both outlines for the bots on the shown floor, in floor-local tiles. */
    update(bots: {x: number; z: number}[], toWorld: (x: number, z: number) => THREE.Vector3) {
        this.mob.extrude(buildCircleChains(bots), toWorld);
        this.grid.extrude(buildGridChains(bots), toWorld);
    }

    dispose() {
        this.mob.dispose();
        this.grid.dispose();
    }
}

/** One player's view of mobs, the circle {@link SightLayer} draws round each bot, in a colour of its own. */
export class WatchedSightLayer {
    private readonly ring: Ribbon;

    constructor(scene: THREE.Scene, color: number) {
        this.ring = new Ribbon(scene, color, 0.55, 0.12, "sightWatched");
    }

    set visible(v: boolean) {
        this.ring.visible = v;
    }

    /** Rebuilds the circle round `at`, in floor-local tiles; null draws nothing. */
    update(at: {x: number; z: number} | null, toWorld: (x: number, z: number) => THREE.Vector3) {
        this.ring.extrude(at ? buildCircleChains([at]) : [], toWorld);
    }

    dispose() {
        this.ring.dispose();
    }
}

// Without consecutive repeats, and closed, without a last point equal to the first: a vertex needs a
// direction from at least one neighbour.
function distinct(pts: {x: number; z: number}[], closed: boolean): {x: number; z: number}[] {
    const out: {x: number; z: number}[] = [];
    for (const p of pts) {
        const last = out.at(-1);
        if (last?.x !== p.x || last.z !== p.z) out.push(p);
    }
    if (closed && out.length > 1) {
        const first = out[0];
        const last = out[out.length - 1];
        if (first.x === last.x && first.z === last.z) out.pop();
    }
    return out;
}

const MAX_KINKS = 64;

// A point added wherever a segment crosses a fold in the ground: a tile edge or either diagonal,
// where the corner-space coordinates u, v, u + v or u - v are whole. At most MAX_KINKS per segment.
function alongGround(pts: {x: number; z: number}[], closed: boolean): {x: number; z: number}[] {
    const n = pts.length;
    if (n < 2) return pts;
    const out: {x: number; z: number}[] = [];
    const ts: number[] = [];
    const segs = closed ? n : n - 1;
    for (let i = 0; i < segs; i++) {
        const a = pts[i];
        const b = pts[(i + 1) % n];
        out.push(a);
        const ua = a.x + 0.5;
        const va = a.z + 0.5;
        const du = b.x - a.x;
        const dv = b.z - a.z;
        ts.length = 0;
        crossings(ua, du, ts);
        crossings(va, dv, ts);
        crossings(ua + va, du + dv, ts);
        crossings(ua - va, du - dv, ts);
        if (ts.length === 0) continue;
        ts.sort((p, q) => p - q);
        const stride = Math.max(1, Math.ceil(ts.length / MAX_KINKS));
        let last = 0;
        for (let k = 0; k < ts.length; k += stride) {
            const t = ts[k];
            if (t - last < 1e-6 || 1 - t < 1e-6) continue;
            out.push({x: a.x + du * t, z: a.z + dv * t});
            last = t;
        }
    }
    if (!closed) out.push(pts[n - 1]);
    return out;
}

function crossings(start: number, delta: number, out: number[]): void {
    if (Math.abs(delta) < 1e-9) return;
    const end = start + delta;
    const lo = Math.floor(Math.min(start, end)) + 1;
    const hi = Math.ceil(Math.max(start, end)) - 1;
    for (let k = lo; k <= hi && out.length < 16 * MAX_KINKS; k++) {
        out.push((k - start) / delta);
    }
}

function unit(x: number, z: number): {x: number; z: number} | null {
    const l = Math.hypot(x, z);
    return l < 1e-9 ? null : {x: x / l, z: z / l};
}

// The union of equal circles: each loses, for every neighbour nearer than 2r, the arc of half angle
// acos(d / 2r) facing it; the arcs left are chained into loops at their shared ends.
function buildCircleChains(bots: {x: number; z: number}[]): Chain[] {
    const arcs: {pts: Pt[]; used: boolean}[] = [];
    for (let i = 0; i < bots.length; i++) {
        const b = bots[i];
        let duplicate = false;
        const covered: [number, number][] = [];
        for (let j = 0; j < bots.length && !duplicate; j++) {
            if (j === i) continue;
            const dx = bots[j].x - b.x;
            const dz = bots[j].z - b.z;
            const d = Math.hypot(dx, dz);
            if (d < 1e-6) {
                duplicate = j < i;
                continue;
            }
            if (d >= 2 * VIEW_R) continue;
            const a = Math.atan2(dz, dx);
            const w = Math.acos(d / (2 * VIEW_R));
            covered.push([a - w, a + w]);
        }
        if (duplicate) continue;
        for (const [from, to] of complement(covered)) {
            const steps = Math.max(2, Math.ceil((to - from) / STEP));
            const pts: Pt[] = [];
            for (let s = 0; s <= steps; s++) {
                const t = from + ((to - from) * s) / steps;
                pts.push({x: b.x + VIEW_R * Math.cos(t), z: b.z + VIEW_R * Math.sin(t)});
            }
            arcs.push({pts, used: false});
        }
    }

    const key = (p: Pt) => `${p.x.toFixed(5)},${p.z.toFixed(5)}`;
    const byStart = new Map<string, number[]>();
    arcs.forEach((a, i) => {
        const k = key(a.pts[0]);
        let arr = byStart.get(k);
        if (!arr) byStart.set(k, (arr = []));
        arr.push(i);
    });

    const chains: Chain[] = [];
    for (const arc of arcs) {
        if (arc.used) continue;
        arc.used = true;
        const pts = [...arc.pts];
        let closed = false;
        for (let guard = 0; guard <= arcs.length; guard++) {
            const endK = key(pts[pts.length - 1]);
            if (endK === key(pts[0])) {
                closed = true;
                pts.pop();
                break;
            }
            const nextIdx = (byStart.get(endK) ?? []).find((c) => !arcs[c].used);
            if (nextIdx == null) break;
            arcs[nextIdx].used = true;
            pts.push(...arcs[nextIdx].pts.slice(1));
        }
        chains.push({pts, closed});
    }
    return chains;
}

// The union of the grid boxes, traced over the 8-tile cell grid as clockwise boundary edges chained into
// loops. At a corner two boxes touch diagonally, the sharpest right turn keeps the loops apart.
function buildGridChains(bots: {x: number; z: number}[]): Chain[] {
    const covered = new Set<string>();
    for (const b of bots) {
        const cx = Math.floor(b.x) >> 3;
        const cz = Math.floor(b.z) >> 3;
        for (let i = -2; i <= 2; i++) {
            for (let j = -2; j <= 2; j++) {
                covered.add(`${cx + i},${cz + j}`);
            }
        }
    }

    interface Edge {
        x0: number;
        z0: number;
        x1: number;
        z1: number;
        used: boolean;
    }
    const edges: Edge[] = [];
    const byStart = new Map<string, Edge[]>();
    const addEdge = (x0: number, z0: number, x1: number, z1: number) => {
        const e = {x0, z0, x1, z1, used: false};
        edges.push(e);
        const k = `${x0},${z0}`;
        let arr = byStart.get(k);
        if (!arr) byStart.set(k, (arr = []));
        arr.push(e);
    };
    for (const cell of covered) {
        const [x, z] = cell.split(",").map(Number);
        if (!covered.has(`${x},${z - 1}`)) addEdge(x, z, x + 1, z);
        if (!covered.has(`${x + 1},${z}`)) addEdge(x + 1, z, x + 1, z + 1);
        if (!covered.has(`${x},${z + 1}`)) addEdge(x + 1, z + 1, x, z + 1);
        if (!covered.has(`${x - 1},${z}`)) addEdge(x, z + 1, x, z);
    }

    const chains: Chain[] = [];
    for (const start of edges) {
        if (start.used) continue;
        start.used = true;
        const corners: Pt[] = [
            {x: start.x0, z: start.z0},
            {x: start.x1, z: start.z1},
        ];
        let dx = start.x1 - start.x0;
        let dz = start.z1 - start.z0;
        for (let guard = 0; guard <= edges.length; guard++) {
            const at = corners[corners.length - 1];
            if (at.x === corners[0].x && at.z === corners[0].z) {
                corners.pop();
                break;
            }
            const outs = (byStart.get(`${at.x},${at.z}`) ?? []).filter((e) => !e.used);
            if (outs.length === 0) break;
            const prefs = [
                [-dz, dx],
                [dx, dz],
                [dz, -dx],
            ];
            let next = outs[0];
            outer: for (const [px, pz] of prefs) {
                for (const e of outs) {
                    if (e.x1 - e.x0 === px && e.z1 - e.z0 === pz) {
                        next = e;
                        break outer;
                    }
                }
            }
            next.used = true;
            corners.push({x: next.x1, z: next.z1});
            dx = next.x1 - next.x0;
            dz = next.z1 - next.z0;
        }
        const pts: Pt[] = [];
        const n = corners.length;
        for (let k = 0; k < n; k++) {
            const prev = corners[(k - 1 + n) % n];
            const cur = corners[k];
            const nxt = corners[(k + 1) % n];
            const straight = cur.x - prev.x === nxt.x - cur.x && cur.z - prev.z === nxt.z - cur.z;
            if (!straight) pts.push(cur);
        }
        const out: Pt[] = [];
        const m = pts.length;
        for (let k = 0; k < m; k++) {
            const a = pts[k];
            const b = pts[(k + 1) % m];
            const tilesLen = (Math.abs(b.x - a.x) + Math.abs(b.z - a.z)) * 8;
            const segs = Math.max(1, Math.ceil(tilesLen / BOX_SEG_TILES));
            for (let s = 0; s < segs; s++) {
                out.push({
                    // Cell corner (cx, cz) is the tile edge at tile-centre coordinates (8cx - 0.5, 8cz - 0.5).
                    x: 8 * (a.x + ((b.x - a.x) * s) / segs) - 0.5,
                    z: 8 * (a.z + ((b.z - a.z) * s) / segs) - 0.5,
                });
            }
        }
        chains.push({pts: out, closed: true});
    }
    return chains;
}

// The gaps between angular intervals on the circle, unwrapped so each runs upward; the whole circle
// when there are none.
function complement(covered: [number, number][]): [number, number][] {
    if (covered.length === 0) {
        return [[0, 2 * Math.PI]];
    }
    const TAU = 2 * Math.PI;
    const norm: [number, number][] = [];
    for (let [a, b] of covered) {
        a = ((a % TAU) + TAU) % TAU;
        b = ((b % TAU) + TAU) % TAU;
        if (b >= a) {
            norm.push([a, b]);
        } else {
            norm.push([a, TAU]);
            norm.push([0, b]);
        }
    }
    norm.sort((x, y) => x[0] - y[0]);
    const merged: [number, number][] = [];
    for (const iv of norm) {
        const last = merged.at(-1);
        if (last && iv[0] <= last[1]) {
            last[1] = Math.max(last[1], iv[1]);
        } else {
            merged.push([iv[0], iv[1]]);
        }
    }
    const out: [number, number][] = [];
    if (merged[0][0] > 0) {
        out.push([0, merged[0][0]]);
    }
    for (let i = 0; i < merged.length - 1; i++) {
        out.push([merged[i][1], merged[i + 1][0]]);
    }
    if (merged[merged.length - 1][1] < TAU) {
        out.push([merged[merged.length - 1][1], TAU]);
    }
    return out;
}
