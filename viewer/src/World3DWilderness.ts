import * as THREE from "three";

/**
 * Wilderness depth lines: a light-red contour line draped along each
 * wilderness-level boundary, labelled with the level it crosses into.
 *
 * The geometry is the server's own rule (Point.wildernessLevel, mirrored by
 * the bot as Bot.wildernessLevel): with zl the floor-local z,
 *
 *     wild  = 427 − zl          (wilderness iff wild > 0 and x < 336)
 *     level = 1 + wild/6        (integer division)
 *
 * so level 1 is the five tile rows zl 422..426 and every deeper level spans
 * six rows, growing northward (clamped to the playable map — see X_EAST /
 * MAX_LEVEL below). The zl rule
 * is identical on every plane (the formula's z/944 term), so upper storeys
 * and the underground get the same lines — that IS the depth the server
 * would enforce there.
 *
 * Lines are 1px hairlines (LineSegments), subdivided so they drape the
 * terrain, with the usual solid + x-ray ghost pair. Density adapts to zoom
 * like a graticule: the level stride widens (1 → 2 → 5 → 10) as tiles
 * shrink, so the overview shows round-number contours instead of a red
 * wash. Labels are pooled DOM pills placed like axis ticks: each pill pins
 * to the point where its line leaves the screen — preferring the left edge,
 * falling back to the top when the camera's yaw turns the lines vertical —
 * and clamps to a small inset, so the numbers hug the frame instead of
 * crossing the user's focal point at any view angle.
 */

/** Drawn x extent in tile-centre coords. The formula's wilderness is x < 336
 *  all the way to x=0, and levels keep counting to 72 at z=0 — but tiles east
 *  of x=48 and levels past 56 are off the playable map (void/ocean), so the
 *  lines clamp to where there is actually ground to stand on. */
const X_EAST = 47.5;
const X_WEST = 335.5;
const MAX_LEVEL = 56;
/** Drape subdivision (tiles), same grain as the sight ribbons. */
const SEG_TILES = 4;
/** Height above the terrain (world units) — the ribbons' anti-z-fight lift. */
const LIFT = 6;
/** Minimum on-screen spacing between adjacent lines before the stride widens. */
const MIN_LINE_PX = 26;
const STRIDES = [1, 2, 5, 10];

/** The southern edge of level `l` in floor-local tile-centre z (the line sits
 *  on the tile boundary you cross entering that level from the south). */
const zEdgeFor = (l: number): number => (l <= 1 ? 426.5 : 427.5 - 6 * (l - 1));

/** Contour stride for the current zoom (px per tile), so adjacent drawn
 *  lines stay ≥ MIN_LINE_PX apart. Never hides entirely — 10 is the cap. */
export const wildernessStrideFor = (pxPerTile: number): number =>
    STRIDES.find(s => s * 6 * pxPerTile >= MIN_LINE_PX) ?? 10;

/** How far (px) a pill centre may approach the canvas edge. */
const INSET_X = 18;
const INSET_Y = 12;

/** Levels drawn at a stride: the multiples, plus level 1 (the wilderness
 *  edge itself — the one line that must never drop out). */
const levelsFor = (stride: number): number[] => {
    const out = [1];
    for (let l = stride; l <= MAX_LEVEL; l += stride) {
        if (l !== 1) out.push(l);
    }
    return out;
};

export class WildernessLayer {
    private readonly geo = new THREE.BufferGeometry();
    private readonly solid: THREE.LineSegments;
    private readonly ghost: THREE.LineSegments;
    private readonly labelPool = new Map<number, HTMLDivElement>();
    private levels: number[] = [];

    constructor(private readonly scene: THREE.Scene,
                private readonly labelHost: HTMLElement) {
        const mat = (opacity: number, xray: boolean) =>
            new THREE.LineBasicMaterial({
                color: 0xff8a8a, transparent: true, opacity,
                depthWrite: false,
                ...(xray ? {depthFunc: THREE.GreaterDepth} : {}),
            });
        this.solid = new THREE.LineSegments(this.geo, mat(0.28, false));
        this.ghost = new THREE.LineSegments(this.geo, mat(0.07, true));
        for (const m of [this.solid, this.ghost]) {
            m.renderOrder = 9;
            m.frustumCulled = false;
            m.userData.noPick = true; // exclude from the GPU depth pick
            scene.add(m);
        }
    }

    set visible(v: boolean) {
        this.solid.visible = v;
        this.ghost.visible = v;
    }

    /** Rebuild the hairlines for a stride, every vertex draped via toWorld
     *  (terrain height + world mirror) like the ribbons. */
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

    /** Place one level label per drawn line, axis-tick style: clip the
     *  projected line to the screen (Liang-Barsky in NDC — the camera is
     *  orthographic, so NDC t maps linearly back to tile x), then pin the
     *  pill at the visible end nearest the LEFT edge (nearest the top for
     *  the tie when yaw turns the lines vertical), clamped to a small inset
     *  so the numbers hug the frame at any view angle. */
    frameLabels(shown: boolean, camera: THREE.Camera, w: number, h: number,
                toWorld: (x: number, z: number) => THREE.Vector3) {
        const used = new Set<number>();
        if (shown) {
            camera.updateMatrixWorld();
            for (const lvl of this.levels) {
                const z = zEdgeFor(lvl);
                const a = toWorld(X_EAST, z).project(camera);
                const b = toWorld(X_WEST, z).project(camera);
                if (a.z > 1 || a.z < -1 || b.z > 1 || b.z < -1) continue;
                // Clip [a→b] to the NDC box; skip lines fully off-screen.
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
                if (!clip(-dx, a.x + 1) || !clip(dx, 1 - a.x)
                    || !clip(-dy, a.y + 1) || !clip(dy, 1 - a.y)) continue;
                // Left edge beats right (x term); top beats bottom on the
                // near-vertical tie (small −y term, NDC y grows upward).
                const score = (t: number) =>
                    (a.x + dx * t) - 0.3 * (a.y + dy * t);
                const tPick = score(t0) <= score(t1) ? t0 : t1;
                // Re-drape through toWorld: the draped line bends with the
                // terrain, so the NDC lerp alone would misplace pills at
                // ends that stop mid-screen (the map-edge termini).
                const v = toWorld(X_EAST + (X_WEST - X_EAST) * tPick, z)
                    .project(camera);
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
                div.style.left = `${Math.min(w - INSET_X,
                    Math.max(INSET_X, ((v.x + 1) / 2) * w))}px`;
                div.style.top = `${Math.min(h - INSET_Y,
                    Math.max(INSET_Y, ((1 - v.y) / 2) * h))}px`;
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
