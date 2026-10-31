import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import {asset, versioned} from "./api";
import {uploadQuads} from "./bufferUpload";
import {anchorDepth, atAnchorDepth, combatShift} from "./spriteDepth";

interface AtlasFrame {
    x: number;
    y: number;
    w: number;
    h: number;
    ax: number;
    ay: number;
}

/**
 * An NPC definition as the atlas carries it: quad size `w` by `h` in engine units, combat and walk
 * animation speeds `cs` and `ws` in client frames, combat width `cw`, and for the menu `atk` (1
 * when attackable), `lvl` its combat level and its two commands.
 */
export interface NpcMeta {
    w: number;
    h: number;
    name: string;
    cs?: number;
    ws?: number;
    cw: number;
    atk?: number;
    lvl?: number;
    cmd1?: string;
    cmd2?: string;
}

/** Every NPC's sprite frames in one texture, `scale` atlas pixels per engine unit. */
export interface NpcAtlas {
    tex: THREE.Texture;
    width: number;
    height: number;
    scale: number;
    meta: Map<number, NpcMeta>;
    /** By NPC id, [order][frame]: orders 0-7 are camera-relative facings, 8 and 9 the combat stances. Sparse. */
    frames: Map<number, (AtlasFrame | undefined)[][]>;
}

/** The NPC sprite atlas; null when it cannot be loaded. */
export async function fetchNpcAtlas(): Promise<NpcAtlas | null> {
    try {
        const r = await fetch(await versioned("/api/world3d/npc-atlas.json"));
        if (!r.ok) return null;
        const idx = (await r.json()) as {
            baked: number;
            scale: number;
            width: number;
            height: number;
            npcs: ({id: number} & NpcMeta)[];
            frames: ({id: number; o: number; f: number} & AtlasFrame)[];
        };
        const tex = await new Promise<THREE.Texture>((resolve, reject) => {
            new THREE.TextureLoader().load(
                asset(`/api/world3d/npc-atlas.png?v=${idx.baked}`),
                resolve,
                undefined,
                reject,
            );
        });
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.flipY = false;
        // Marked sRGB so three's decode and output encode cancel and the baked colours come through exact.
        tex.colorSpace = THREE.SRGBColorSpace;
        const meta = new Map<number, NpcMeta>();
        for (const n of idx.npcs) meta.set(n.id, n);
        const frames = new Map<number, (AtlasFrame | undefined)[][]>();
        for (const f of idx.frames) {
            let byOrder = frames.get(f.id);
            if (!byOrder) {
                frames.set(f.id, (byOrder = Array.from({length: 10}, () => [])));
            }
            byOrder[f.o][f.f] = f;
        }
        return {tex, width: idx.width, height: idx.height, scale: idx.scale, meta, frames};
    } catch {
        return null;
    }
}

/**
 * One NPC to draw this frame, at its interpolated tile position. `dx`, `dz` is the heading while
 * `moving`; `dir` the server's facing, 0-7, or 8 and 9 for the combat stances. A `ghost` is a
 * spawn marker for a dead NPC, drawn translucent.
 */
export interface NpcSpriteState {
    key: string;
    npcId: number;
    x: number;
    z: number;
    moving: boolean;
    dx: number;
    dz: number;
    dir?: number | null;
    ghost?: boolean;
}

// The client's walk and combat frame cycles, at 20 ms a client frame.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L398
const WALK_CYCLE = [0, 1, 2, 1];
const COMBAT_CYCLE_A = [0, 1, 2, 1, 0, 0, 0, 0];
const COMBAT_CYCLE_B = [0, 0, 0, 0, 0, 1, 2, 1];
const CLIENT_FRAME_MS = 20;

const T_BL = new THREE.Vector3();
const T_BR = new THREE.Vector3();
const T_TR = new THREE.Vector3();
const T_TL = new THREE.Vector3();

/**
 * NPC sprites as camera-facing quads anchored at their feet, with frames picked as the client's
 * drawNPC picks them, a faint pass where something hides them, and a separate translucent batch
 * for spawn markers. At most 4096 of each.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6386 mudclient.drawNPC
 */
export class NpcSpriteLayer {
    private readonly solid: THREE.Mesh;
    private readonly ghost: THREE.Mesh;
    private readonly geo: THREE.BufferGeometry;
    private readonly phantomGeo: THREE.BufferGeometry;
    private readonly phantom: THREE.Mesh;
    private phantomXray!: THREE.Mesh;
    private readonly phantomCap = 4096;
    private atlas: NpcAtlas | null = null;
    private readonly lastDir = new Map<string, number>();
    // The client's stepFrame: client frames walked, one per moving axis; it never resets, so a stopped
    // NPC freezes mid-stride.
    private readonly stepAcc = new Map<string, number>();
    private lastFrameNow = -1;
    private readonly cap = 4096;
    highlightKey: string | null = null;
    private readonly glowGeo: THREE.BufferGeometry;
    private readonly glow: THREE.Mesh;

    constructor(scene: THREE.Scene) {
        this.geo = new THREE.BufferGeometry();
        const pos = new Float32Array(this.cap * 4 * 3);
        const uv = new Float32Array(this.cap * 4 * 2);
        const idx = new Uint16Array(this.cap * 6);
        for (let i = 0; i < this.cap; i++) {
            idx[i * 6] = i * 4;
            idx[i * 6 + 1] = i * 4 + 1;
            idx[i * 6 + 2] = i * 4 + 2;
            idx[i * 6 + 3] = i * 4;
            idx[i * 6 + 4] = i * 4 + 2;
            idx[i * 6 + 5] = i * 4 + 3;
        }
        this.geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
        this.geo.setAttribute("uv", new THREE.BufferAttribute(uv, 2));
        this.geo.setAttribute("depth", new THREE.BufferAttribute(new Float32Array(this.cap * 4), 1));
        this.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        this.geo.setDrawRange(0, 0);

        const dbg = new URLSearchParams(location.search).has("npcdebug");
        const solidMat = atAnchorDepth(
            new THREE.MeshBasicMaterial({
                transparent: !dbg,
                alphaTest: dbg ? 0 : 0.06,
                side: THREE.DoubleSide,
                ...(dbg ? {color: 0xff00ff, wireframe: false} : {}),
            }),
        );
        if (dbg) window.__npcDbgMat = solidMat;
        this.solid = new THREE.Mesh(this.geo, solidMat);
        // After the players: rsc-c draws every player sprite before any NPC, so on a depth tie the NPC shows.
        this.solid.renderOrder = 12.5;
        this.solid.frustumCulled = false;

        const ghostMat = atAnchorDepth(
            new THREE.MeshBasicMaterial({
                transparent: true,
                opacity: 0.3,
                alphaTest: 0.4,
                // depthWrite, so stacked NPCs do not composite their ghosts into an opaque blob.
                depthFunc: THREE.GreaterDepth,
                depthWrite: true,
                side: THREE.DoubleSide,
            }),
        );
        this.ghost = new THREE.Mesh(this.geo, ghostMat);
        // Ahead of the player ghost pass (13), so the NPC wins the tie it wins in the solid pass.
        this.ghost.renderOrder = 12.9;
        this.ghost.frustumCulled = false;

        this.glowGeo = new THREE.BufferGeometry();
        this.glowGeo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(4 * 3), 3));
        this.glowGeo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(4 * 2), 2));
        this.glowGeo.setAttribute("depth", new THREE.BufferAttribute(new Float32Array(4), 1));
        this.glowGeo.setIndex(new THREE.BufferAttribute(new Uint16Array([0, 1, 2, 0, 2, 3]), 1));
        this.glowGeo.setDrawRange(0, 0);
        const glowMat = atAnchorDepth(
            new THREE.MeshBasicMaterial({
                transparent: true,
                alphaTest: 0.06,
                color: 0xffe45c,
                blending: THREE.AdditiveBlending,
                depthWrite: false,
                side: THREE.DoubleSide,
            }),
        );
        this.glow = new THREE.Mesh(this.glowGeo, glowMat);
        this.glow.renderOrder = 11;
        this.glow.frustumCulled = false;
        scene.add(freezeTransform(this.glow));

        this.phantomGeo = new THREE.BufferGeometry();
        this.phantomGeo.setAttribute(
            "position",
            new THREE.BufferAttribute(new Float32Array(this.phantomCap * 4 * 3), 3),
        );
        this.phantomGeo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(this.phantomCap * 4 * 2), 2));
        this.phantomGeo.setAttribute("depth", new THREE.BufferAttribute(new Float32Array(this.phantomCap * 4), 1));
        const pidx = new Uint16Array(this.phantomCap * 6);
        for (let i = 0; i < this.phantomCap; i++) {
            pidx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        this.phantomGeo.setIndex(new THREE.BufferAttribute(pidx, 1));
        this.phantomGeo.setDrawRange(0, 0);
        this.phantom = new THREE.Mesh(
            this.phantomGeo,
            atAnchorDepth(
                new THREE.MeshBasicMaterial({
                    transparent: true,
                    opacity: 0.35,
                    alphaTest: 0.05,
                    depthWrite: false,
                    side: THREE.DoubleSide,
                }),
            ),
        );
        this.phantom.renderOrder = 12;
        this.phantom.frustumCulled = false;
        scene.add(freezeTransform(this.phantom));
        this.phantomXray = new THREE.Mesh(
            this.phantomGeo,
            atAnchorDepth(
                new THREE.MeshBasicMaterial({
                    transparent: true,
                    opacity: 0.12,
                    alphaTest: 0.05,
                    depthFunc: THREE.GreaterDepth,
                    depthWrite: false,
                    side: THREE.DoubleSide,
                }),
            ),
        );
        this.phantomXray.renderOrder = 13;
        this.phantomXray.frustumCulled = false;
        scene.add(freezeTransform(this.phantomXray));

        scene.add(freezeTransform(this.solid));
        scene.add(freezeTransform(this.ghost));
        for (const m of [this.solid, this.ghost, this.glow, this.phantom, this.phantomXray]) {
            m.userData.noPick = true;
        }
    }

    /** The NPC's quad size in engine units, for hit testing; null before the atlas has loaded. */
    dims(npcId: number): {w: number; h: number} | null {
        const m = this.atlas?.meta.get(npcId);
        return m ? {w: m.w, h: m.h} : null;
    }

    info(npcId: number): NpcMeta | null {
        return this.atlas?.meta.get(npcId) ?? null;
    }

    setAtlas(atlas: NpcAtlas) {
        this.atlas = atlas;
        (this.glow.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.glow.material as THREE.MeshBasicMaterial).needsUpdate = true;
        (this.phantom.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.phantom.material as THREE.MeshBasicMaterial).needsUpdate = true;
        (this.phantomXray.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.phantomXray.material as THREE.MeshBasicMaterial).needsUpdate = true;
        if (new URLSearchParams(location.search).has("npcdebug")) {
            (this.ghost.material as THREE.MeshBasicMaterial).map = atlas.tex;
            (this.ghost.material as THREE.MeshBasicMaterial).needsUpdate = true;
            return;
        }
        (this.solid.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.solid.material as THREE.MeshBasicMaterial).needsUpdate = true;
        (this.ghost.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.ghost.material as THREE.MeshBasicMaterial).needsUpdate = true;
    }

    /**
     * Rebuilds the batches for this frame. Sprites move `lift` engine units along `camToward`, the unit
     * vector toward the camera, so cards at high pitch clear the terrain under them.
     */
    frame(
        now: number,
        npcs: NpcSpriteState[],
        toWorld: (x: number, z: number) => THREE.Vector3,
        camRight: THREE.Vector3,
        camUp: THREE.Vector3,
        camYaw: number,
        camToward: THREE.Vector3,
        lift: number,
        viewProj: THREE.Matrix4,
    ) {
        const atlas = this.atlas;
        if (!atlas) {
            this.geo.setDrawRange(0, 0);
            window.__npcSprites = {atlas: false, drawn: 0, fed: npcs.length};
            return;
        }
        // The client's cameraRotation: 0-255, 128 looking north, counting down as the view turns west.
        const camRot = (128 - Math.round((camYaw / (2 * Math.PI)) * 256)) & 255;
        const dtFrames = this.lastFrameNow < 0 ? 0 : (now - this.lastFrameNow) / CLIENT_FRAME_MS;
        this.lastFrameNow = now;
        const pos = this.geo.getAttribute("position") as THREE.BufferAttribute;
        const uv = this.geo.getAttribute("uv") as THREE.BufferAttribute;
        const ppos = this.phantomGeo.getAttribute("position") as THREE.BufferAttribute;
        const puv = this.phantomGeo.getAttribute("uv") as THREE.BufferAttribute;
        const dep = this.geo.getAttribute("depth") as THREE.BufferAttribute;
        const pdep = this.phantomGeo.getAttribute("depth") as THREE.BufferAttribute;
        const gpos = this.glowGeo.getAttribute("position") as THREE.BufferAttribute;
        const guv = this.glowGeo.getAttribute("uv") as THREE.BufferAttribute;
        const gdep = this.glowGeo.getAttribute("depth") as THREE.BufferAttribute;
        let glowSet = false;
        let i = 0;
        let pi = 0;
        for (const n of npcs) {
            if (n.ghost ? pi >= this.phantomCap : i >= this.cap) continue;
            const meta = atlas.meta.get(n.npcId);
            const byOrder = atlas.frames.get(n.npcId);
            if (!meta || !byOrder) continue;
            // An NPC that has never moved or been faced points north, as the server spawns it.
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L70
            let dir = this.lastDir.get(n.key) ?? 0;
            if (n.moving && (n.dx !== 0 || n.dz !== 0)) {
                const ang = Math.atan2(n.dx, -n.dz);
                dir = Math.round(((ang + 2 * Math.PI) % (2 * Math.PI)) / (Math.PI / 4)) & 7;
                this.lastDir.set(n.key, dir);
                // Standing still, it faces the way the server says, as the client does.
            } else if (n.dir != null && n.dir >= 0 && n.dir <= 7) {
                dir = n.dir;
                this.lastDir.set(n.key, dir);
            }
            if (n.moving && dtFrames > 0) {
                const axes = (n.dx !== 0 ? 1 : 0) + (n.dz !== 0 ? 1 : 0);
                this.stepAcc.set(n.key, (this.stepAcc.get(n.key) ?? 0) + axes * dtFrames);
            }
            const stepFrame = Math.floor(this.stepAcc.get(n.key) ?? 0);
            let order = (dir + ((camRot + 16) >> 5)) & 7;
            let frameIdx = WALK_CYCLE[Math.floor(stepFrame / Math.max(1, meta.ws ?? 6)) % 4];
            let shift = 0;
            // Both fighters share one tile; the client nudges each sideways by the definition's combat width.
            if (n.dir === 8 || n.dir === 9) {
                shift = n.dir === 8 ? -combatShift(meta.cw) : combatShift(meta.cw);
                const stance = n.dir === 8 ? 8 : 9;
                if (byOrder[stance]?.length) {
                    order = stance;
                    const cs = meta.cs ?? 6;
                    frameIdx =
                        n.dir === 8
                            ? COMBAT_CYCLE_A[Math.floor(now / (Math.max(2, cs - 1) * CLIENT_FRAME_MS)) % 8]
                            : COMBAT_CYCLE_B[Math.floor(now / (cs * CLIENT_FRAME_MS)) % 8];
                }
            }
            const frames = byOrder[order];
            if (frames.length === 0) continue;
            const f = frames[Math.min(frameIdx, frames.length - 1)] ?? frames[0];
            if (!f) continue;

            const p = toWorld(n.x, n.z).addScaledVector(camToward, lift);
            // Taken before the nudge: the client moves the drawn sprite, not the point its depth comes from.
            const depth = anchorDepth(viewProj, p);
            p.addScaledVector(camRight, shift);
            const s = atlas.scale;
            const left = -f.ax / s;
            const right = (f.w - f.ax) / s;
            const top = f.ay / s;
            const bottom = -(f.h - f.ay) / s;
            const bl = T_BL.copy(p).addScaledVector(camRight, left).addScaledVector(camUp, bottom);
            const br = T_BR.copy(p).addScaledVector(camRight, right).addScaledVector(camUp, bottom);
            const tr = T_TR.copy(p).addScaledVector(camRight, right).addScaledVector(camUp, top);
            const tl = T_TL.copy(p).addScaledVector(camRight, left).addScaledVector(camUp, top);
            const tPos = n.ghost ? ppos : pos;
            const tUv = n.ghost ? puv : uv;
            const tDep = n.ghost ? pdep : dep;
            const q = n.ghost ? pi : i;
            tPos.setXYZ(q * 4, bl.x, bl.y, bl.z);
            tPos.setXYZ(q * 4 + 1, br.x, br.y, br.z);
            tPos.setXYZ(q * 4 + 2, tr.x, tr.y, tr.z);
            tPos.setXYZ(q * 4 + 3, tl.x, tl.y, tl.z);
            for (let k = 0; k < 4; k++) tDep.setX(q * 4 + k, depth);
            const u0 = f.x / atlas.width;
            const u1 = (f.x + f.w) / atlas.width;
            const v0 = f.y / atlas.height;
            const v1 = (f.y + f.h) / atlas.height;
            tUv.setXY(q * 4, u0, v1);
            tUv.setXY(q * 4 + 1, u1, v1);
            tUv.setXY(q * 4 + 2, u1, v0);
            tUv.setXY(q * 4 + 3, u0, v0);
            if (n.key === this.highlightKey) {
                const cx = (bl.x + tr.x) / 2;
                const cy = (bl.y + tr.y) / 2;
                const cz = (bl.z + tr.z) / 2;
                const sc = 1.12;
                const corners = [bl, br, tr, tl];
                for (let k = 0; k < 4; k++) {
                    gpos.setXYZ(
                        k,
                        cx + (corners[k].x - cx) * sc,
                        cy + (corners[k].y - cy) * sc,
                        cz + (corners[k].z - cz) * sc,
                    );
                }
                guv.setXY(0, u0, v1);
                guv.setXY(1, u1, v1);
                guv.setXY(2, u1, v0);
                guv.setXY(3, u0, v0);
                for (let k = 0; k < 4; k++) gdep.setX(k, depth);
                gpos.needsUpdate = true;
                guv.needsUpdate = true;
                gdep.needsUpdate = true;
                glowSet = true;
            }
            if (n.ghost) {
                pi++;
            } else {
                i++;
            }
        }
        uploadQuads(this.geo, i, "position", "uv", "depth");
        uploadQuads(this.phantomGeo, pi, "position", "uv", "depth");
        this.geo.setDrawRange(0, i * 6);
        this.phantomGeo.setDrawRange(0, pi * 6);
        this.glowGeo.setDrawRange(0, glowSet ? 6 : 0);
        window.__npcSprites = {atlas: true, drawn: i, ghosts: pi, fed: npcs.length};
    }

    dispose(scene: THREE.Scene) {
        scene.remove(this.solid);
        scene.remove(this.ghost);
        scene.remove(this.glow);
        scene.remove(this.phantom);
        scene.remove(this.phantomXray);
        this.geo.dispose();
        this.glowGeo.dispose();
        this.phantomGeo.dispose();
        (this.solid.material as THREE.Material).dispose();
        (this.ghost.material as THREE.Material).dispose();
        (this.glow.material as THREE.Material).dispose();
        (this.phantom.material as THREE.Material).dispose();
        (this.phantomXray.material as THREE.Material).dispose();
        this.atlas?.tex.dispose();
    }
}
