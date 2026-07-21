import * as THREE from "three";
import {asset} from "./api";

/**
 * Client-side door/boundary assembly for the 3D world view.
 *
 * Static boundary edges (doors, gates, some fences) are STRIPPED from the
 * baked wall meshes; the viewer draws each one as the engine does — a single
 * quad on the tile edge, front/back textured, corner posts at per-endpoint
 * ground elevation (mudclient_create_wall_object) — and swaps an edge's
 * boundary id when a bot observes different state (an opened door).
 *
 * Lighting replicates the engine's boundary-model light call
 * (set_light(60, 24, -50,-10,-50)): ambience 16, divisor (768·|L|)>>8 = 213;
 * front shade = 16 − intensity, back = 16 + intensity.
 */

export interface DoorDefLite {
    id: number;
    height: number;
    texF: number;
    texB: number;
    doorType: number;
    name: string;
    /** Right-click menu commands ("Open", "Picklock"…); absent when the def has none. */
    cmd1?: string;
    cmd2?: string;
}

export interface BoundaryPlacement {
    id: number;
    x: number;
    z: number; // floor-local
    floor: number;
    dir: number;
}

export async function fetchDoorData(): Promise<{
    defs: Map<number, DoorDefLite>;
    boundaries: BoundaryPlacement[];
} | null> {
    try {
        const [dl, bl] = await Promise.all([
            fetch(asset("/api/world3d/doorlib.json")),
            fetch(asset("/api/world3d/boundaries.json")),
        ]);
        if (!dl.ok || !bl.ok) return null;
        const doors = (await dl.json()).doors as DoorDefLite[];
        const bounds = (await bl.json()).boundaries as BoundaryPlacement[];
        const defs = new Map<number, DoorDefLite>();
        for (const d of doors) defs.set(d.id, d);
        return {defs, boundaries: bounds};
    } catch {
        return null;
    }
}

/** Edge endpoints per direction (client mudclient_create_wall_object). */
function endpoints(x: number, z: number, dir: number): [number, number, number, number] {
    switch (dir & 3) {
        case 0: return [x, z, x + 1, z];
        case 1: return [x, z, x, z + 1];
        case 2: return [x + 1, z, x, z + 1];
        default: return [x, z, x + 1, z + 1];
    }
}

const LX = -50, LY = -10, LZ = -50;
const DIVISOR = 213; // (light_diffuse 768 · |L| 71) >> 8
const AMBIENCE = 16; // 256 − 60·4

/**
 * Assemble one cell's boundary quads, grouped by texture. Corner heights come
 * from the terrain height grid (three-y up); coordinates converted like all
 * other geometry (x mirrored).
 */
export function assembleDoors(
    defs: Map<number, DoorDefLite>,
    placements: BoundaryPlacement[],
    worldWidthUnits: number,
    cornerHeight: (cx: number, cz: number) => number,
    // Optional GPU-pick id per boundary (see World3DView's id-pick); baked as a
    // per-vertex `pickId` vec4-u8 attribute so a tall gate's whole silhouette is
    // its hit area instead of a thin ground-proximity band.
    idFor?: (b: BoundaryPlacement) => number,
): {tex: number; geometry: THREE.BufferGeometry}[] {
    type Acc = {pos: number[]; shade: number[]; base: number[]; uv: number[];
        pid: number[]};
    const acc = new Map<number, Acc>();
    const push = (a: Acc, v: number[][], shade: number, tex: number,
                  reversed: boolean, pid: number) => {
        // v = 4 quad corners; UVs = corners in vertex order (engine quads).
        const uvs = [[0, 0], [1, 0], [1, 1], [0, 1]];
        const tris = reversed ? [[0, 2, 1], [0, 3, 2]] : [[0, 1, 2], [0, 2, 3]];
        const pr = pid & 255, pg = (pid >> 8) & 255,
              pb = (pid >> 16) & 255, pa = (pid >> 24) & 255;
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
        // Engine coords for normal math (y negative up), then convert.
        const e1 = {x: x1 * 128, y: -g1, z: z1 * 128};
        const e2 = {x: x2 * 128, y: -g2, z: z2 * 128};
        const quadE = [
            [e1.x, e1.y, e1.z],
            [e1.x, e1.y - def.height, e1.z],
            [e2.x, e2.y - def.height, e2.z],
            [e2.x, e2.y, e2.z],
        ];
        // Face normal (computeNormals cross convention on v0,v1,v2).
        const x21 = quadE[1][0] - quadE[0][0], y21 = quadE[1][1] - quadE[0][1],
              z21 = quadE[1][2] - quadE[0][2];
        const x31 = quadE[2][0] - quadE[0][0], y31 = quadE[2][1] - quadE[0][1],
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
        // Convert to three space (x mirrored, y up).
        const quad = quadE.map(([ex, ey, ez]) => [worldWidthUnits - ex, -ey, ez]);
        if (def.texF !== 12345678) {
            let a = acc.get(def.texF);
            if (!a) acc.set(def.texF,
                a = {pos: [], shade: [], base: [], uv: [], pid: []});
            push(a, quad, shadeF, def.texF, false, pid);
        }
        if (def.texB !== 12345678) {
            let a = acc.get(def.texB);
            if (!a) acc.set(def.texB,
                a = {pos: [], shade: [], base: [], uv: [], pid: []});
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
            geo.setAttribute("pickId",
                new THREE.BufferAttribute(new Uint8Array(a.pid), 4, true));
        }
        out.push({tex, geometry: geo});
    }
    return out;
}
