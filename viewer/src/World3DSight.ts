import * as THREE from "three";

/**
 * Combined line-of-sight outlines, drawn as thin ribbons draped on the
 * terrain (real geometry — a ground-level overlay quad parallaxes over tall
 * geometry when the camera tilts).
 *
 * TWO boundaries, because the server gates visibility with two different
 * rules (verified in GameStateUpdater):
 *
 * 1. MOB circle — npcs/players stream within Mob.withinRange =
 *    (int)sqrt(dx²+dy²) ≤ 15, a EUCLIDEAN circle of radius √255 ≈ 15.97
 *    tiles. Drawn as the brighter outline.
 * 2. GRID box — scenery/wall objects/ground items stream within
 *    withinGridRange: both positions snapped to 8×8-tile cells, ±2 cells —
 *    a 40×40-tile square ALIGNED TO THE GRID (not bot-centred; it lurches 8
 *    tiles as the bot crosses a cell boundary). Drawn as a fainter outline:
 *    "we know the loot/doors here, but not the actors".
 *
 * Circle union boundary is analytic (equal radii): per circle subtract, for
 * each neighbour at d < 2r, the arc of half-angle acos(d/2r) around the
 * direction to it; chain the surviving arcs into loops at their shared
 * endpoints. Box union boundary is edge-tracing over the binary cell grid.
 * Every loop extrudes as ONE continuous mitered ribbon — independent
 * segments would double-blend where they overlap.
 */

const VIEW_R = Math.sqrt(255);
/** Circle polyline step (radians) — ~5° ≈ 1.4 tiles of arc. */
const STEP = Math.PI / 36;
/** Grid-box edges subdivide to ≤ this many tiles so the ribbon drapes. */
const BOX_SEG_TILES = 4;
/** Ribbon half-width in TILE units (5 world units / 128). */
const HALF_T = 5 / 128;
/** Miter clamp: cos(half corner angle) floor → max ~2.5× width at corners. */
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

/** One draped outline: solid pass + x-ray ghost pass over shared geometry. */
export class Ribbon {
    readonly solid: THREE.Mesh;
    readonly ghost: THREE.Mesh;
    private readonly geo: THREE.BufferGeometry;
    private cap = 0;

    constructor(scene: THREE.Scene, color: number, opacity: number,
                ghostOpacity: number) {
        this.geo = new THREE.BufferGeometry();
        this.grow(4096);
        this.solid = new THREE.Mesh(this.geo, new THREE.MeshBasicMaterial({
            color, transparent: true, opacity,
            polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2,
            depthWrite: false, side: THREE.DoubleSide,
        }));
        this.solid.renderOrder = 9;
        // X-ray pass, like the entity rings: visible where occluded.
        this.ghost = new THREE.Mesh(this.geo, new THREE.MeshBasicMaterial({
            color, transparent: true, opacity: ghostOpacity,
            depthFunc: THREE.GreaterDepth, depthWrite: false,
            side: THREE.DoubleSide,
        }));
        this.ghost.renderOrder = 9;
        for (const m of [this.solid, this.ghost]) {
            m.frustumCulled = false;
            m.userData.noPick = true; // exclude from the GPU depth pick
            scene.add(m);
        }
    }

    set visible(v: boolean) {
        this.solid.visible = v;
        this.ghost.visible = v;
    }

    private grow(quads: number) {
        this.cap = quads;
        this.geo.setAttribute("position",
            new THREE.BufferAttribute(new Float32Array(quads * 4 * 3), 3));
        const idx = new Uint32Array(quads * 6);
        for (let i = 0; i < quads; i++) {
            idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3],
                i * 6);
        }
        this.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        this.geo.setDrawRange(0, 0);
    }

    /** Extrude the chains as mitered ribbons, every vertex draped via
     *  toWorld (terrain height + world mirror). */
    extrude(chains: Chain[],
            toWorld: (x: number, z: number) => THREE.Vector3) {
        const need = chains.reduce((n, c) => n + c.pts.length, 0);
        if (need > this.cap) {
            this.grow(Math.ceil(need * 1.5));
        }
        const pos = this.geo.getAttribute("position") as THREE.BufferAttribute;
        const at = pos.array as Float32Array;
        let q = 0;
        for (const chain of chains) {
            const pts = chain.pts;
            const n = pts.length;
            if (n < (chain.closed ? 3 : 2)) continue;
            // Per-vertex mitered offsets (tile space; toWorld drapes after).
            const left: THREE.Vector3[] = [];
            const right: THREE.Vector3[] = [];
            for (let k = 0; k < n; k++) {
                const hasPrev = chain.closed || k > 0;
                const hasNext = chain.closed || k < n - 1;
                const prev = pts[(k - 1 + n) % n];
                const next = pts[(k + 1) % n];
                let d1 = hasPrev ? unit(pts[k].x - prev.x, pts[k].z - prev.z) : null;
                let d2 = hasNext ? unit(next.x - pts[k].x, next.z - pts[k].z) : null;
                d1 = d1 ?? d2!;
                d2 = d2 ?? d1;
                // Perpendiculars of adjacent segments; miter = their bisector,
                // lengthened by 1/cos(half angle) so the ribbon keeps width
                // through the corner (clamped so junction spikes stay short).
                const n1 = {x: -d1.z, z: d1.x};
                const n2 = {x: -d2.z, z: d2.x};
                const m = unit(n1.x + n2.x, n1.z + n2.z) ?? n1;
                const cosHalf = Math.max(MITER_COS_MIN,
                    Math.abs(m.x * n1.x + m.z * n1.z));
                const s = HALF_T / cosHalf;
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
                at.set([
                    left[a].x, left[a].y, left[a].z,
                    right[a].x, right[a].y, right[a].z,
                    right[b].x, right[b].y, right[b].z,
                    left[b].x, left[b].y, left[b].z,
                ], q * 12);
                q++;
            }
        }
        pos.needsUpdate = true;
        this.geo.setDrawRange(0, q * 6);
    }

    dispose(scene: THREE.Scene) {
        for (const m of [this.solid, this.ghost]) {
            scene.remove(m);
            (m.material as THREE.Material).dispose();
        }
        this.geo.dispose();
    }
}

export class SightLayer {
    /** Mob-visibility circle (npcs/players). */
    private readonly mob: Ribbon;
    /** withinGridRange box union (scenery/walls/ground items) — fainter. */
    private readonly grid: Ribbon;

    constructor(scene: THREE.Scene) {
        this.mob = new Ribbon(scene, 0x9fd8ff, 0.42, 0.1);
        this.grid = new Ribbon(scene, 0x9fd8ff, 0.18, 0.05);
    }

    set visible(v: boolean) {
        this.mob.visible = v;
        this.grid.visible = v;
    }

    /**
     * Rebuild both outlines for the active floor's bots (floor-local tiles).
     */
    update(bots: {x: number; z: number}[],
           toWorld: (x: number, z: number) => THREE.Vector3) {
        this.mob.extrude(buildCircleChains(bots), toWorld);
        this.grid.extrude(buildGridChains(bots), toWorld);
    }

    dispose(scene: THREE.Scene) {
        this.mob.dispose(scene);
        this.grid.dispose(scene);
    }
}

function unit(x: number, z: number): {x: number; z: number} | null {
    const l = Math.hypot(x, z);
    return l < 1e-9 ? null : {x: x / l, z: z / l};
}

/**
 * Visible boundary arcs per bot circle, sampled and chained into loops at
 * their shared endpoints (circle intersection points match to float
 * precision — both circles compute the same point, keyed at 1e-5 tiles).
 */
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
                duplicate = j < i; // stacked bots: keep one circle
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
                pts.push({x: b.x + VIEW_R * Math.cos(t),
                    z: b.z + VIEW_R * Math.sin(t)});
            }
            arcs.push({pts, used: false});
        }
    }

    const key = (p: Pt) => `${p.x.toFixed(5)},${p.z.toFixed(5)}`;
    const byStart = new Map<string, number[]>();
    arcs.forEach((a, i) => {
        const k = key(a.pts[0]);
        let arr = byStart.get(k);
        if (!arr) byStart.set(k, arr = []);
        arr.push(i);
    });

    const chains: Chain[] = [];
    for (let i = 0; i < arcs.length; i++) {
        if (arcs[i].used) continue;
        arcs[i].used = true;
        const pts = [...arcs[i].pts];
        let closed = false;
        for (let guard = 0; guard <= arcs.length; guard++) {
            const endK = key(pts[pts.length - 1]);
            if (endK === key(pts[0])) {
                closed = true;
                pts.pop(); // drop the duplicated closing point
                break;
            }
            const nextIdx = (byStart.get(endK) ?? []).find(c => !arcs[c].used);
            if (nextIdx == null) break; // open chain (degenerate tangency)
            arcs[nextIdx].used = true;
            pts.push(...arcs[nextIdx].pts.slice(1)); // skip shared junction pt
        }
        chains.push({pts, closed});
    }
    return chains;
}

/**
 * Union boundary of the bots' withinGridRange boxes, traced over the binary
 * 8×8-tile CELL grid: every bot covers cells (bx>>3 ± 2, bz>>3 ± 2); boundary
 * edges (covered cell faces against uncovered neighbours) are emitted
 * clockwise and chained into loops; ambiguous corners (two boxes touching
 * diagonally) resolve to the sharpest right turn so the loops stay separate.
 */
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

    // Directed boundary edges in cell-corner space, clockwise around the
    // interior: N face → +x, E face → +z, S face → −x, W face → −z.
    interface Edge {
        x0: number; z0: number; x1: number; z1: number; used: boolean;
    }
    const edges: Edge[] = [];
    const byStart = new Map<string, Edge[]>();
    const addEdge = (x0: number, z0: number, x1: number, z1: number) => {
        const e = {x0, z0, x1, z1, used: false};
        edges.push(e);
        const k = `${x0},${z0}`;
        let arr = byStart.get(k);
        if (!arr) byStart.set(k, arr = []);
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
        const corners: Pt[] = [{x: start.x0, z: start.z0}, {x: start.x1, z: start.z1}];
        let dx = start.x1 - start.x0;
        let dz = start.z1 - start.z0;
        for (let guard = 0; guard <= edges.length; guard++) {
            const at = corners[corners.length - 1];
            if (at.x === corners[0].x && at.z === corners[0].z) {
                corners.pop(); // closed — drop the duplicated corner
                break;
            }
            const outs = (byStart.get(`${at.x},${at.z}`) ?? [])
                .filter(e => !e.used);
            if (outs.length === 0) break; // shouldn't happen (loops close)
            // Prefer the sharpest RIGHT turn (cw = (−dz, dx)), then straight,
            // then left — keeps diagonally-touching boxes as separate loops.
            const prefs = [
                [-dz, dx], [dx, dz], [dz, -dx],
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
        // Merge collinear runs, then convert cell corners → tile coords and
        // subdivide long straights so the ribbon drapes the terrain. Cell
        // corner (cx, cz) sits on the tile-edge at tile-centre coords
        // (8·cx − 0.5, 8·cz − 0.5).
        const pts: Pt[] = [];
        const n = corners.length;
        for (let k = 0; k < n; k++) {
            const prev = corners[(k - 1 + n) % n];
            const cur = corners[k];
            const nxt = corners[(k + 1) % n];
            const straight = (cur.x - prev.x === nxt.x - cur.x)
                && (cur.z - prev.z === nxt.z - cur.z);
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
                    x: 8 * (a.x + ((b.x - a.x) * s) / segs) - 0.5,
                    z: 8 * (a.z + ((b.z - a.z) * s) / segs) - 0.5,
                });
            }
        }
        chains.push({pts: out, closed: true});
    }
    return chains;
}

/**
 * Complement of a set of angular intervals on the circle, as [from, to]
 * ranges with to > from (unwrapped). No intervals → the full circle.
 */
function complement(covered: [number, number][]): [number, number][] {
    if (covered.length === 0) {
        return [[0, 2 * Math.PI]];
    }
    // Normalize to [0, 2π), splitting wrap-around intervals.
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
    // Merge, then invert.
    const merged: [number, number][] = [];
    for (const iv of norm) {
        const last = merged[merged.length - 1];
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
