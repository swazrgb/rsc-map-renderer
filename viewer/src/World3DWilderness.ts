import * as THREE from "three";
import {freezeTransform} from "./staticTransform";

// The server's wilderness runs from x 0 to 335, deepening northward to level 72, but east of x 48 and
// past level 56 there is no ground, so the lines stop there.
const X_EAST = 47.5;
const X_WEST = 335.5;
const MAX_LEVEL = 56;
// Drape subdivision, in tiles.
const SEG_TILES = 4;
const LIFT = 6;
// The closest two drawn lines may come on screen before the stride widens.
const MIN_LINE_PX = 26;
const STRIDES = [1, 2, 5, 10];

// The southern edge of level `l` in floor-local z: the server's level is 1 + (427 - z) / 6 while
// 427 - z is positive, so level 1 is 5 rows deep and every other level 6.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L373
const zEdgeFor = (l: number): number => (l <= 1 ? 426.5 : 427.5 - 6 * (l - 1));

/** The level stride (1, 2, 5 or 10) that keeps drawn lines at least 26 px apart at `pxPerTile`. */
export const wildernessStrideFor = (pxPerTile: number): number =>
    STRIDES.find((s) => s * 6 * pxPerTile >= MIN_LINE_PX) ?? 10;

const INSET_X = 18;
const INSET_Y = 12;

// The multiples of the stride, and always level 1, the wilderness edge.
const levelsFor = (stride: number): number[] => {
    const out = [1];
    for (let l = stride; l <= MAX_LEVEL; l += stride) {
        if (l !== 1) out.push(l);
    }
    return out;
};

/**
 * A light-red line along each wilderness level boundary, on every floor, with its level as a label.
 * The same floor-local rule holds on every floor, as the server's does.
 */
export class WildernessLayer {
    private readonly geo = new THREE.BufferGeometry();
    private readonly solid: THREE.LineSegments;
    private readonly ghost: THREE.LineSegments;
    private readonly labelPool = new Map<number, HTMLDivElement>();
    private levels: number[] = [];

    constructor(
        private readonly scene: THREE.Scene,
        private readonly labelHost: HTMLElement,
    ) {
        const mat = (opacity: number, xray: boolean) =>
            new THREE.LineBasicMaterial({
                color: 0xff8a8a,
                transparent: true,
                opacity,
                depthWrite: false,
                ...(xray ? {depthFunc: THREE.GreaterDepth} : {}),
            });
        this.solid = new THREE.LineSegments(this.geo, mat(0.28, false));
        this.ghost = new THREE.LineSegments(this.geo, mat(0.07, true));
        for (const m of [this.solid, this.ghost]) {
            m.renderOrder = 9;
            m.frustumCulled = false;
            m.userData.noPick = true;
            scene.add(freezeTransform(m));
        }
    }

    set visible(v: boolean) {
        this.solid.visible = v;
        this.ghost.visible = v;
    }

    /** Rebuilds the lines for `stride`, draped on the terrain through `toWorld`. */
    rebuild(stride: number, toWorld: (x: number, z: number) => THREE.Vector3) {
        this.levels = levelsFor(stride);
        const segs = Math.ceil((X_WEST - X_EAST) / SEG_TILES);
        const pos = new Float32Array(this.levels.length * segs * 6);
        let o = 0;
        for (const lvl of this.levels) {
            const z = zEdgeFor(lvl);
            let prev = toWorld(X_EAST, z);
            for (let s = 1; s <= segs; s++) {
                const cur = toWorld(Math.min(X_WEST, X_EAST + s * SEG_TILES), z);
                pos[o++] = prev.x;
                pos[o++] = prev.y + LIFT;
                pos[o++] = prev.z;
                pos[o++] = cur.x;
                pos[o++] = cur.y + LIFT;
                pos[o++] = cur.z;
                prev = cur;
            }
        }
        this.geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
    }

    /**
     * Places one label per drawn line at the end where it leaves the screen, preferring the left edge,
     * then the top, clamped to a small inset.
     */
    frameLabels(
        shown: boolean,
        camera: THREE.Camera,
        w: number,
        h: number,
        toWorld: (x: number, z: number) => THREE.Vector3,
    ) {
        const used = new Set<number>();
        if (shown) {
            camera.updateMatrixWorld();
            for (const lvl of this.levels) {
                const z = zEdgeFor(lvl);
                const a = toWorld(X_EAST, z).project(camera);
                const b = toWorld(X_WEST, z).project(camera);
                if (a.z > 1 || a.z < -1 || b.z > 1 || b.z < -1) continue;
                let t0 = 0;
                let t1 = 1;
                const clip = (p: number, q: number): boolean => {
                    if (Math.abs(p) < 1e-12) return q >= 0;
                    const r = q / p;
                    if (p < 0) {
                        if (r > t1) return false;
                        if (r > t0) t0 = r;
                    } else {
                        if (r < t0) return false;
                        if (r < t1) t1 = r;
                    }
                    return true;
                };
                const dx = b.x - a.x;
                const dy = b.y - a.y;
                if (!clip(-dx, a.x + 1) || !clip(dx, 1 - a.x) || !clip(-dy, a.y + 1) || !clip(dy, 1 - a.y)) continue;
                const score = (t: number) => a.x + dx * t - 0.3 * (a.y + dy * t);
                const tPick = score(t0) <= score(t1) ? t0 : t1;
                // Projected again through toWorld, since the draped line bends with the terrain.
                const v = toWorld(X_EAST + (X_WEST - X_EAST) * tPick, z).project(camera);
                used.add(lvl);
                let div = this.labelPool.get(lvl);
                if (!div) {
                    div = document.createElement("div");
                    div.style.cssText =
                        "position:absolute;transform:translate(-50%,-50%);" +
                        "font:10px monospace;color:rgba(255,176,176,.8);" +
                        "background:rgba(40,8,8,.35);padding:0 4px;" +
                        "border:1px solid rgba(255,138,138,.2);" +
                        "border-radius:4px;pointer-events:none;" +
                        "white-space:nowrap;z-index:879000;";
                    div.textContent = String(lvl);
                    this.labelHost.appendChild(div);
                    this.labelPool.set(lvl, div);
                }
                div.style.left = `${Math.min(w - INSET_X, Math.max(INSET_X, ((v.x + 1) / 2) * w))}px`;
                div.style.top = `${Math.min(h - INSET_Y, Math.max(INSET_Y, ((1 - v.y) / 2) * h))}px`;
            }
        }
        for (const [lvl, div] of this.labelPool) {
            if (!used.has(lvl)) {
                div.remove();
                this.labelPool.delete(lvl);
            }
        }
    }

    dispose() {
        for (const m of [this.solid, this.ghost]) {
            this.scene.remove(m);
            (m.material as THREE.Material).dispose();
        }
        this.geo.dispose();
        for (const div of this.labelPool.values()) div.remove();
        this.labelPool.clear();
    }
}
