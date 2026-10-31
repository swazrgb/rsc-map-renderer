import * as THREE from "three";
import {versioned} from "./api";
import {OVERLAY_BAND} from "./World3DOverlays";
import type {OverlayContext, ViewerOverlay} from "./World3DOverlays";

/**
 * One floor's danger tiles: each tile an aggressive NPC makes a route pay for, in columns `x`, `z`
 * (floor-local) and `v`, the charge, which the tint saturates at `ceiling`.
 */
export interface FloorDanger {
    floor: number;
    combatLevel: number;
    ceiling: number;
    x: number[];
    z: number[];
    v: number[];
}

const FLOOR_HEIGHT = 944;
// Above the terrain, under the draped ribbons.
const LIFT = 4;
const ALPHA_MIN = 100 / 255;
const ALPHA_MAX = 210 / 255;

const loaded = new Map<number, Promise<FloorDanger | null>>();

/** `floor`'s danger tiles, fetched once; null where there are none to fetch. */
export function fetchFloorDanger(floor: number): Promise<FloorDanger | null> {
    let p = loaded.get(floor);
    if (!p) {
        p = versioned(`/api/map/floor/${floor}.danger.json`)
            .then((url) => fetch(url))
            .then((r) => (r.ok ? (r.json() as Promise<FloorDanger>) : null))
            .catch((e: unknown) => {
                console.warn(`[danger] floor ${floor}:`, e);
                return null;
            });
        loaded.set(floor, p);
    }
    return p;
}

/** Tints each danger tile of the floor in view, yellow to red by its charge; ignores its data. */
export function dangerOverlay(): ViewerOverlay {
    let mesh: THREE.Mesh | null = null;
    const byFloor = new Map<number, FloorDanger | null>();
    let drawnFloor = Number.NaN;
    let drawnHeights = Number.NaN;
    let drawn: FloorDanger | null = null;
    return {
        mount(ctx: OverlayContext) {
            drawn = null;
            drawnFloor = Number.NaN;
            drawnHeights = Number.NaN;
            mesh = new THREE.Mesh(
                new THREE.BufferGeometry(),
                new THREE.MeshBasicMaterial({
                    vertexColors: true,
                    transparent: true,
                    depthWrite: false,
                    side: THREE.DoubleSide,
                    polygonOffset: true,
                    polygonOffsetFactor: -1,
                    polygonOffsetUnits: -1,
                }),
            );
            // Just under the ground band, so a route across a zone stays on top.
            mesh.renderOrder = OVERLAY_BAND.ground - 0.5;
            mesh.frustumCulled = false;
            mesh.userData.noPick = true;
            ctx.group.add(mesh);
        },
        frame(ctx: OverlayContext) {
            if (!mesh) return;
            const floor = ctx.floor;
            if (!byFloor.has(floor)) {
                byFloor.set(floor, null);
                void fetchFloorDanger(floor).then((d) => byFloor.set(floor, d));
            }
            const data = byFloor.get(floor) ?? null;
            if (data === drawn && floor === drawnFloor && ctx.heightsRev === drawnHeights) return;
            drawn = data;
            drawnFloor = floor;
            drawnHeights = ctx.heightsRev;
            mesh.geometry.dispose();
            mesh.geometry = build(data, floor, ctx);
        },
        dispose() {
            mesh?.geometry.dispose();
            (mesh?.material as THREE.Material | undefined)?.dispose();
            mesh = null;
        },
    };
}

function build(d: FloorDanger | null, floor: number, ctx: OverlayContext): THREE.BufferGeometry {
    const geo = new THREE.BufferGeometry();
    const n = d?.v.length ?? 0;
    const pos = new Float32Array(n * 12);
    const col = new Float32Array(n * 16);
    const idx = new Uint32Array(n * 6);
    const p = new THREE.Vector3();
    const ys = [0, 0, 0, 0];
    const base = floor * FLOOR_HEIGHT;
    for (let i = 0; d && i < n; i++) {
        const x = d.x[i];
        const z = base + d.z[i];
        const corners = [
            [x - 0.5, z - 0.5],
            [x + 0.5, z - 0.5],
            [x + 0.5, z + 0.5],
            [x - 0.5, z + 0.5],
        ];
        const t = Math.min(1, d.v[i] / Math.max(1, d.ceiling));
        const g = (150 / 255) * (1 - t);
        const a = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * t;
        for (let k = 0; k < 4; k++) {
            ctx.toWorld(corners[k][0], corners[k][1], p);
            ys[k] = p.y;
            pos.set([p.x, p.y + LIFT, p.z], (i * 4 + k) * 3);
            col.set([1, g, 0, a], (i * 4 + k) * 4);
        }
        const centre = ctx.toWorld(x, z, p).y;
        // Split the quad along the diagonal the terrain splits this tile along, or half of it sinks.
        const main = Math.abs((ys[0] + ys[2]) / 2 - centre) < Math.abs((ys[1] + ys[3]) / 2 - centre);
        const v0 = i * 4;
        idx.set(main ? [v0, v0 + 1, v0 + 2, v0, v0 + 2, v0 + 3] : [v0, v0 + 1, v0 + 3, v0 + 1, v0 + 2, v0 + 3], i * 6);
    }
    geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    geo.setAttribute("color", new THREE.BufferAttribute(col, 4));
    geo.setIndex(new THREE.BufferAttribute(idx, 1));
    geo.computeBoundingSphere();
    return geo;
}
