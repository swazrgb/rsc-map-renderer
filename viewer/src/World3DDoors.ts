import * as THREE from "three";
import {versioned} from "./api";

/** A boundary object definition: height in engine units, front and back texture (12345678 for none). */
export interface DoorDefLite {
    id: number;
    height: number;
    texF: number;
    texB: number;
    doorType: number;
    name: string;
    /** Its menu commands; absent when it has none. */
    cmd1?: string;
    cmd2?: string;
}

/** A placed boundary object; z is floor-local and `dir` the tile edge it stands on. */
export interface BoundaryPlacement {
    id: number;
    x: number;
    z: number;
    floor: number;
    dir: number;
}

/** The boundary definitions and every placement; null when either cannot be loaded. */
export async function fetchDoorData(): Promise<{
    defs: Map<number, DoorDefLite>;
    boundaries: BoundaryPlacement[];
} | null> {
    try {
        const [dl, bl] = await Promise.all([
            versioned("/api/world3d/doorlib.json").then((url) => fetch(url)),
            versioned("/api/world/boundaries.json").then((url) => fetch(url)),
        ]);
        if (!dl.ok || !bl.ok) return null;
        const doors = ((await dl.json()) as {doors: DoorDefLite[]}).doors;
        const bounds = ((await bl.json()) as {boundaries: BoundaryPlacement[]}).boundaries;
        const defs = new Map<number, DoorDefLite>();
        for (const d of doors) defs.set(d.id, d);
        return {defs, boundaries: bounds};
    } catch {
        return null;
    }
}

// The edge a boundary stands on, by direction, as the client builds it.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L2588
function endpoints(x: number, z: number, dir: number): [number, number, number, number] {
    switch (dir & 3) {
        case 0:
            return [x, z, x + 1, z];
        case 1:
            return [x, z, x, z + 1];
        case 2:
            return [x + 1, z, x, z + 1];
        default:
            return [x, z, x + 1, z + 1];
    }
}

// The client's lighting for a boundary: light (-50, -10, -50), ambience 16, divisor 213; the front
// face is shaded 16 - intensity and the back 16 + intensity.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L2617
const LX = -50,
    LY = -10,
    LZ = -50;
const DIVISOR = 213;
const AMBIENCE = 16;

/**
 * Boundary objects as the client draws them: one quad on the tile edge, each end standing on the
 * ground at that corner, textured front and back. Grouped by texture. `cornerHeight` gives the
 * terrain height at a tile corner; `idFor` gives a per-boundary pick id, baked into a `pickId`
 * attribute so the whole quad is its hit area.
 */
export function assembleDoors(
    defs: Map<number, DoorDefLite>,
    placements: BoundaryPlacement[],
    worldWidthUnits: number,
    cornerHeight: (cx: number, cz: number) => number,
    idFor?: (b: BoundaryPlacement) => number,
): {tex: number; geometry: THREE.BufferGeometry}[] {
    interface Acc {
        pos: number[];
        shade: number[];
        base: number[];
        uv: number[];
        pid: number[];
    }
    const acc = new Map<number, Acc>();
    const push = (a: Acc, v: number[][], shade: number, tex: number, reversed: boolean, pid: number) => {
        const uvs = [
            [0, 0],
            [1, 0],
            [1, 1],
            [0, 1],
        ];
        const tris = reversed
            ? [
                  [0, 2, 1],
                  [0, 3, 2],
              ]
            : [
                  [0, 1, 2],
                  [0, 2, 3],
              ];
        const pr = pid & 255,
            pg = (pid >> 8) & 255,
            pb = (pid >> 16) & 255,
            pa = (pid >> 24) & 255;
        for (const tri of tris) {
            for (const idx of tri) {
                a.pos.push(v[idx][0], v[idx][1], v[idx][2]);
                a.shade.push(shade);
                if (tex >= 0) {
                    a.base.push(255, 255, 255);
                    a.uv.push(uvs[idx][0], uvs[idx][1]);
                } else {
                    const c = -1 - tex;
                    a.base.push(((c >> 10) & 31) * 8, ((c >> 5) & 31) * 8, (c & 31) * 8);
                    a.uv.push(0, 0);
                }
                a.pid.push(pr, pg, pb, pa);
            }
        }
    };
    for (const b of placements) {
        const def = defs.get(b.id);
        if (!def || def.height <= 0) continue;
        const pid = idFor ? idFor(b) : 0;
        const [x1, z1, x2, z2] = endpoints(b.x, b.z, b.dir);
        const g1 = cornerHeight(x1, z1);
        const g2 = cornerHeight(x2, z2);
        const e1 = {x: x1 * 128, y: -g1, z: z1 * 128};
        const e2 = {x: x2 * 128, y: -g2, z: z2 * 128};
        const quadE = [
            [e1.x, e1.y, e1.z],
            [e1.x, e1.y - def.height, e1.z],
            [e2.x, e2.y - def.height, e2.z],
            [e2.x, e2.y, e2.z],
        ];
        const x21 = quadE[1][0] - quadE[0][0],
            y21 = quadE[1][1] - quadE[0][1],
            z21 = quadE[1][2] - quadE[0][2];
        const x31 = quadE[2][0] - quadE[0][0],
            y31 = quadE[2][1] - quadE[0][1],
            z31 = quadE[2][2] - quadE[0][2];
        let nx = z31 * y21 - z21 * y31;
        let ny = z21 * x31 - x21 * z31;
        let nz = x21 * y31 - x31 * y21;
        const mag = Math.sqrt(nx * nx + ny * ny + nz * nz) || 1;
        nx = (nx / mag) * 256;
        ny = (ny / mag) * 256;
        nz = (nz / mag) * 256;
        const intensity = Math.round((nx * LX + ny * LY + nz * LZ) / DIVISOR);
        const shadeF = Math.max(0, Math.min(255, AMBIENCE - intensity));
        const shadeB = Math.max(0, Math.min(255, AMBIENCE + intensity));
        // The normal is taken in engine space (y up negative), then the quad converted, x mirrored.
        const quad = quadE.map(([ex, ey, ez]) => [worldWidthUnits - ex, -ey, ez]);
        if (def.texF !== 12345678) {
            let a = acc.get(def.texF);
            if (!a) acc.set(def.texF, (a = {pos: [], shade: [], base: [], uv: [], pid: []}));
            push(a, quad, shadeF, def.texF, false, pid);
        }
        if (def.texB !== 12345678) {
            let a = acc.get(def.texB);
            if (!a) acc.set(def.texB, (a = {pos: [], shade: [], base: [], uv: [], pid: []}));
            push(a, quad, shadeB, def.texB, true, pid);
        }
    }
    const out: {tex: number; geometry: THREE.BufferGeometry}[] = [];
    for (const [tex, a] of acc) {
        const geo = new THREE.BufferGeometry();
        geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(a.pos), 3));
        geo.setAttribute("shade", new THREE.BufferAttribute(new Float32Array(a.shade), 1));
        geo.setAttribute("base", new THREE.BufferAttribute(new Uint8Array(a.base), 3, true));
        geo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(a.uv), 2));
        if (idFor) {
            geo.setAttribute("pickId", new THREE.BufferAttribute(new Uint8Array(a.pid), 4, true));
        }
        out.push({tex, geometry: geo});
    }
    return out;
}
