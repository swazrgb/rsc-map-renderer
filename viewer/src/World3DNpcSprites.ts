import * as THREE from "three";
import {asset} from "./api";

/**
 * Billboard NPC sprites for the 3D world view.
 *
 * Frames come pre-composed from the engine (8 camera-relative facing orders ×
 * 3 walk frames per npc id, flips baked in). At draw time each visible NPC
 * picks its frame the way the client does — order = (direction + camera
 * rotation snap) & 7, walk frame cycling [0,1,2,1] while moving — and is
 * drawn as a camera-facing quad anchored at its feet. A second pass with the
 * depth test inverted paints occluded sprites as ghosts (same X-ray treatment
 * as the rings).
 *
 * Facing directions are derived from movement (the wire doesn't carry npc
 * sprite direction): RSC dir 0=N, going counter-clockwise; standing keeps the
 * last direction.
 */

interface AtlasFrame {
    x: number;
    y: number;
    w: number;
    h: number;
    ax: number;
    ay: number;
}

/** Per-npc def meta baked into the atlas index: sprite quad size (w/h engine
 *  units), animation speeds (cs/ws), and the context-menu fields — atk=1 +
 *  lvl (displayed combat level) when attackable, cmd1/cmd2 the def's
 *  right-click commands (Pickpocket, Trade…). */
export interface NpcMeta {
    w: number;
    h: number;
    name: string;
    cs?: number;
    ws?: number;
    atk?: number;
    lvl?: number;
    cmd1?: string;
    cmd2?: string;
}

export interface NpcAtlas {
    tex: THREE.Texture;
    width: number;
    height: number;
    /** Atlas px per engine unit. */
    scale: number;
    /** npcId -> world quad size (engine units). */
    meta: Map<number, NpcMeta>;
    /** npcId -> [order 0..7][frame 0..2]. */
    frames: Map<number, AtlasFrame[][]>;
}

export async function fetchNpcAtlas(): Promise<NpcAtlas | null> {
    try {
        const r = await fetch(asset("/api/world3d/npc-atlas.json"));
        if (!r.ok) return null;
        const idx = await r.json() as {
            baked: number; scale: number; width: number; height: number;
            npcs: ({id: number} & NpcMeta)[];
            frames: ({id: number; o: number; f: number} & AtlasFrame)[];
        };
        const tex = await new Promise<THREE.Texture>((resolve, reject) => {
            new THREE.TextureLoader().load(
                asset(`/api/world3d/npc-atlas.png?v=${idx.baked}`), resolve, undefined, reject);
        });
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.flipY = false;
        // Built-in materials run three's colour pipeline (unlike our raw world
        // shaders): an unmarked texture is treated as linear and BRIGHTENED
        // by the sRGB output conversion. Marking it sRGB makes decode+encode
        // cancel, so sprites keep their exact engine-baked colours.
        tex.colorSpace = THREE.SRGBColorSpace;
        const meta = new Map<number, NpcMeta>();
        for (const n of idx.npcs) meta.set(n.id, n);
        const frames = new Map<number, AtlasFrame[][]>();
        for (const f of idx.frames) {
            let byOrder = frames.get(f.id);
            if (!byOrder) {
                // Orders 0-7 = walk facings, 8/9 = combat stance A/B.
                frames.set(f.id, byOrder = Array.from({length: 10}, () => []));
            }
            byOrder[f.o][f.f] = f;
        }
        return {tex, width: idx.width, height: idx.height, scale: idx.scale, meta, frames};
    } catch {
        return null;
    }
}

export interface NpcSpriteState {
    key: string;
    npcId: number;
    /** Interpolated position, bot tiles. */
    x: number;
    z: number;
    /** True while lerping between tiles. */
    moving: boolean;
    /** Movement heading in tiles (dx, dz), used to derive facing. */
    dx: number;
    dz: number;
    /** Server sprite direction: 0-7 facing, 8/9 = combat stance A/B. */
    dir?: number | null;
    /** Ghost spawn marker (dead npc, respawn pending): drawn translucent. */
    ghost?: boolean;
}

const WALK_CYCLE = [0, 1, 2, 1];
// Combat swing cycles (mudclient animFrameToSprite_CombatA/B). NPC step rate
// comes from the def's combatModel (cs): stance A steps every (cs−1) client
// frames, B every cs — 20ms per frame, cs=6 for most humanoids (= the player
// rates), 7 rats, 8 goblins, 11 Delrith.
const COMBAT_CYCLE_A = [0, 1, 2, 1, 0, 0, 0, 0];
const COMBAT_CYCLE_B = [0, 0, 0, 0, 0, 1, 2, 1];
const CLIENT_FRAME_MS = 20; // the client's 50fps logic tick

// Per-frame scratch (a full-server render is thousands of sprites — fresh
// Vector3 clones per corner were ~20k allocations/frame).
const T_BL = new THREE.Vector3();
const T_BR = new THREE.Vector3();
const T_TR = new THREE.Vector3();
const T_TL = new THREE.Vector3();

export class NpcSpriteLayer {
    private readonly solid: THREE.Mesh;
    private readonly ghost: THREE.Mesh;
    private readonly geo: THREE.BufferGeometry;
    /** Ghost-spawn batch: dead npcs' spawn markers, drawn translucent. */
    private readonly phantomGeo: THREE.BufferGeometry;
    private readonly phantom: THREE.Mesh;
    private phantomXray!: THREE.Mesh;
    /** Ghosts can be the whole static spawn table (~3.6k), not just kills. */
    private readonly phantomCap = 4096;
    private atlas: NpcAtlas | null = null;
    private readonly lastDir = new Map<string, number>();
    /** Stock stepFrame: fractional client frames walked, per entity. Advances
     *  only while moving (per axis — diagonals count double), never resets, so
     *  a stopped character freezes mid-stride exactly like the game. */
    private readonly stepAcc = new Map<string, number>();
    private lastFrameNow = -1;
    /** The server's npc list capacity (World.java: new EntityList<>(4000)) —
     *  a full-server render fits; overflow skips quads, never crashes. */
    private readonly cap = 4096;
    /** Sprite drawn with an additive glow halo (hover/selection feedback). */
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
        this.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        this.geo.setDrawRange(0, 0);

        const dbg = new URLSearchParams(location.search).has("npcdebug");
        const solidMat = new THREE.MeshBasicMaterial({
            transparent: !dbg,
            // Low cutoff: the atlas carries REAL alpha now (ghosts ~0.4) —
            // 0.5 would erase them. Edges are hard (alpha 0/1) so no halos.
            alphaTest: dbg ? 0 : 0.06,
            side: THREE.DoubleSide,
            ...(dbg ? {color: 0xff00ff, wireframe: false} : {}),
        });
        if (dbg) (window as any).__npcDbgMat = solidMat;
        this.solid = new THREE.Mesh(this.geo, solidMat);
        this.solid.renderOrder = 12;
        this.solid.frustumCulled = false;

        const ghostMat = new THREE.MeshBasicMaterial({
            transparent: true,
            opacity: 0.3,
            alphaTest: 0.4,
            depthFunc: THREE.GreaterDepth,
            // depthWrite TRUE so stacked co-located npcs don't composite their
            // ghost alpha into an opaque blob (see World3DPlayerSprites).
            depthWrite: true,
            side: THREE.DoubleSide,
        });
        this.ghost = new THREE.Mesh(this.geo, ghostMat);
        this.ghost.renderOrder = 13;
        this.ghost.frustumCulled = false;

        // Single-quad glow: the highlighted sprite redrawn slightly larger,
        // additive yellow — reads as an outline halo.
        this.glowGeo = new THREE.BufferGeometry();
        this.glowGeo.setAttribute("position",
            new THREE.BufferAttribute(new Float32Array(4 * 3), 3));
        this.glowGeo.setAttribute("uv",
            new THREE.BufferAttribute(new Float32Array(4 * 2), 2));
        this.glowGeo.setIndex(new THREE.BufferAttribute(
            new Uint16Array([0, 1, 2, 0, 2, 3]), 1));
        this.glowGeo.setDrawRange(0, 0);
        const glowMat = new THREE.MeshBasicMaterial({
            transparent: true,
            alphaTest: 0.06,
            color: 0xffe45c,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
            side: THREE.DoubleSide,
        });
        this.glow = new THREE.Mesh(this.glowGeo, glowMat);
        this.glow.renderOrder = 11;
        this.glow.frustumCulled = false;
        scene.add(this.glow);

        // Ghost-spawn batch: same quads/atlas, drawn translucent (a dead
        // npc's spawn marker while its respawn is pending).
        this.phantomGeo = new THREE.BufferGeometry();
        this.phantomGeo.setAttribute("position", new THREE.BufferAttribute(
            new Float32Array(this.phantomCap * 4 * 3), 3));
        this.phantomGeo.setAttribute("uv", new THREE.BufferAttribute(
            new Float32Array(this.phantomCap * 4 * 2), 2));
        const pidx = new Uint16Array(this.phantomCap * 6);
        for (let i = 0; i < this.phantomCap; i++) {
            pidx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3],
                i * 6);
        }
        this.phantomGeo.setIndex(new THREE.BufferAttribute(pidx, 1));
        this.phantomGeo.setDrawRange(0, 0);
        this.phantom = new THREE.Mesh(this.phantomGeo, new THREE.MeshBasicMaterial({
            transparent: true, opacity: 0.35, alphaTest: 0.05,
            depthWrite: false, side: THREE.DoubleSide,
        }));
        this.phantom.renderOrder = 12;
        this.phantom.frustumCulled = false;
        scene.add(this.phantom);
        // X-ray pass for phantoms too — a spawn under a roof/behind a wall
        // would otherwise show only its nameplate.
        this.phantomXray = new THREE.Mesh(this.phantomGeo, new THREE.MeshBasicMaterial({
            transparent: true, opacity: 0.12, alphaTest: 0.05,
            depthFunc: THREE.GreaterDepth, depthWrite: false,
            side: THREE.DoubleSide,
        }));
        this.phantomXray.renderOrder = 13;
        this.phantomXray.frustumCulled = false;
        scene.add(this.phantomXray);

        scene.add(this.solid);
        scene.add(this.ghost);
        for (const m of [this.solid, this.ghost, this.glow, this.phantom,
                         this.phantomXray]) {
            m.userData.noPick = true; // exclude from the GPU depth pick
        }
    }

    /** World quad size (engine units) for an npc id, for hit-testing. */
    dims(npcId: number): {w: number; h: number} | null {
        const m = this.atlas?.meta.get(npcId);
        return m ? {w: m.w, h: m.h} : null;
    }

    /** Full def meta (attackable/level/commands) for the context menu; null pre-atlas. */
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
            return; // solid stays untextured pink for geometry debugging
        }
        (this.solid.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.solid.material as THREE.MeshBasicMaterial).needsUpdate = true;
        (this.ghost.material as THREE.MeshBasicMaterial).map = atlas.tex;
        (this.ghost.material as THREE.MeshBasicMaterial).needsUpdate = true;
    }

    /** Rebuild the quad batch for this frame.
     * @param camToward unit vector from the scene toward the camera; sprites
     *     shift along it (depth-only in ortho) so flat-lying cards at high
     *     pitch don't get clipped by terrain relief under them.
     * @param lift shift distance in engine units (scale with sin(pitch)). */
    frame(now: number, npcs: NpcSpriteState[],
          toWorld: (x: number, z: number) => THREE.Vector3,
          camRight: THREE.Vector3, camUp: THREE.Vector3, camYaw: number,
          camToward: THREE.Vector3, lift: number) {
        const atlas = this.atlas;
        if (!atlas) {
            this.geo.setDrawRange(0, 0);
            (window as any).__npcSprites = {atlas: false, drawn: 0, fed: npcs.length};
            return;
        }
        // Camera rotation in the client's 0..255 space; +16>>5 snap like drawNPC.
        const camRot = Math.round((camYaw / (2 * Math.PI)) * 256) & 255;
        const dtFrames = this.lastFrameNow < 0 ? 0
            : (now - this.lastFrameNow) / CLIENT_FRAME_MS;
        this.lastFrameNow = now;
        const pos = this.geo.getAttribute("position") as THREE.BufferAttribute;
        const uv = this.geo.getAttribute("uv") as THREE.BufferAttribute;
        const ppos = this.phantomGeo.getAttribute("position") as THREE.BufferAttribute;
        const puv = this.phantomGeo.getAttribute("uv") as THREE.BufferAttribute;
        const gpos = this.glowGeo.getAttribute("position") as THREE.BufferAttribute;
        const guv = this.glowGeo.getAttribute("uv") as THREE.BufferAttribute;
        let glowSet = false;
        let i = 0;
        let pi = 0;
        for (const n of npcs) {
            if ((n.ghost ? pi >= this.phantomCap : i >= this.cap)) continue;
            const meta = atlas.meta.get(n.npcId);
            const byOrder = atlas.frames.get(n.npcId);
            if (!meta || !byOrder) continue;
            // Facing from movement; RSC dirs: 0=N(−z), 2=W(−x), 4=S, 6=E.
            let dir = this.lastDir.get(n.key) ?? 4;
            if (n.moving && (n.dx !== 0 || n.dz !== 0)) {
                // Client ground truth (game_character_move): +x→2, −x→6,
                // +y→4, −y→0 ⇒ dir = atan2(+dx, −dz) in 45° steps.
                const ang = Math.atan2(n.dx, -n.dz);
                dir = Math.round(((ang + 2 * Math.PI) % (2 * Math.PI)) / (Math.PI / 4)) & 7;
                this.lastDir.set(n.key, dir);
            } else if (n.dir != null && n.dir >= 0 && n.dir <= 7) {
                // Stopped: face the server-dictated standing direction, exactly
                // as the stock client does (mudclient.java:11853, when the last
                // waypoint is reached: direction = lookup(animationNext)). The
                // server streams this facing via updateType-1 sprite updates
                // (PacketDispatcher:1158) — e.g. an npc turning to face the
                // player it talks to, or its spawn orientation. n.dir uses the
                // SAME RSC encoding as our atan2 dir (applyWalkDir:1221), so it
                // feeds the order formula below unchanged. Holding the stale
                // walk heading here (the old behaviour) left idle npcs facing
                // wherever they last walked instead of where the server aims
                // them.
                dir = n.dir;
                this.lastDir.set(n.key, dir);
            }
            // Stock stepFrame: advance while moving, one count per moving
            // axis per 20ms client frame; freeze (don't reset) when still.
            if (n.moving && dtFrames > 0) {
                const axes = (n.dx !== 0 ? 1 : 0) + (n.dz !== 0 ? 1 : 0);
                this.stepAcc.set(n.key,
                    (this.stepAcc.get(n.key) ?? 0) + axes * dtFrames);
            }
            const stepFrame = Math.floor(this.stepAcc.get(n.key) ?? 0);
            // Mirrored-world display order, calibrated with straight-line
            // walkers: (4 − dir + snap). The x-mirror makes the naive engine
            // formula (dir + snap) show fronts for backs.
            let order = (4 - dir + ((camRot + 16) >> 5)) & 7;
            // Walk frame = WALK[stepFrame / walkModel % 4] (drawNPC).
            let frameIdx = WALK_CYCLE[
                Math.floor(stepFrame / Math.max(1, meta.ws ?? 6)) % 4];
            // Combat stance: camera-independent profile pair, A faces B.
            // Falls back to the walk facing on atlases without combat frames.
            if (n.dir === 8 || n.dir === 9) {
                // Stock combat render: BOTH fighters draw at the SAME spot,
                // fully overlapping — the client's ±30 setCombatXOffset is
                // used ONLY for mouse picking (Scene.java:2741 adds it after
                // drawEntity, inside the pick branch). Stance A faces right,
                // B left; our mirrored billboard axis flips textures, so A
                // draws the FLIPPED frame set (atlas order 9) and vice versa.
                // Cycles stay keyed on the stance.
                const order2 = n.dir === 8 ? 9 : 8;
                if (byOrder[order2]?.length) {
                    order = order2;
                    const cs = meta.cs ?? 6;
                    frameIdx = n.dir === 8
                        ? COMBAT_CYCLE_A[Math.floor(
                            now / (Math.max(2, cs - 1) * CLIENT_FRAME_MS)) % 8]
                        : COMBAT_CYCLE_B[Math.floor(
                            now / (cs * CLIENT_FRAME_MS)) % 8];
                }
            }
            const frames = byOrder[order];
            if (!frames || frames.length === 0) continue;
            const f = frames[Math.min(frameIdx, frames.length - 1)] ?? frames[0];
            if (!f) continue;

            const p = toWorld(n.x, n.z).addScaledVector(camToward, lift);
            // px -> engine units via the bake scale; anchor (ax, ay) is the
            // bottom-centre of the def box in crop coords (y down).
            const s = atlas.scale;
            const left = -f.ax / s;
            const right = (f.w - f.ax) / s;
            const top = f.ay / s;
            const bottom = -(f.h - f.ay) / s;
            const bl = T_BL.copy(p).addScaledVector(camRight, left).addScaledVector(camUp, bottom);
            const br = T_BR.copy(p).addScaledVector(camRight, right).addScaledVector(camUp, bottom);
            const tr = T_TR.copy(p).addScaledVector(camRight, right).addScaledVector(camUp, top);
            const tl = T_TL.copy(p).addScaledVector(camRight, left).addScaledVector(camUp, top);
            // Ghost spawn markers write into the translucent phantom batch.
            const tPos = n.ghost ? ppos : pos;
            const tUv = n.ghost ? puv : uv;
            const q = n.ghost ? pi : i;
            tPos.setXYZ(q * 4, bl.x, bl.y, bl.z);
            tPos.setXYZ(q * 4 + 1, br.x, br.y, br.z);
            tPos.setXYZ(q * 4 + 2, tr.x, tr.y, tr.z);
            tPos.setXYZ(q * 4 + 3, tl.x, tl.y, tl.z);
            const u0 = f.x / atlas.width;
            const u1 = (f.x + f.w) / atlas.width;
            const v0 = f.y / atlas.height;
            const v1 = (f.y + f.h) / atlas.height;
            // flipY=false: v0 is the TOP of the sprite.
            tUv.setXY(q * 4, u0, v1);
            tUv.setXY(q * 4 + 1, u1, v1);
            tUv.setXY(q * 4 + 2, u1, v0);
            tUv.setXY(q * 4 + 3, u0, v0);
            if (n.key === this.highlightKey) {
                // Same quad scaled ~12% about its anchor for the halo.
                const cx = (bl.x + tr.x) / 2;
                const cy = (bl.y + tr.y) / 2;
                const cz = (bl.z + tr.z) / 2;
                const sc = 1.12;
                const corners = [bl, br, tr, tl];
                for (let k = 0; k < 4; k++) {
                    gpos.setXYZ(k,
                        cx + (corners[k].x - cx) * sc,
                        cy + (corners[k].y - cy) * sc,
                        cz + (corners[k].z - cz) * sc);
                }
                guv.setXY(0, u0, v1);
                guv.setXY(1, u1, v1);
                guv.setXY(2, u1, v0);
                guv.setXY(3, u0, v0);
                gpos.needsUpdate = true;
                guv.needsUpdate = true;
                glowSet = true;
            }
            if (n.ghost) {
                pi++;
            } else {
                i++;
            }
        }
        pos.needsUpdate = true;
        uv.needsUpdate = true;
        ppos.needsUpdate = true;
        puv.needsUpdate = true;
        this.geo.setDrawRange(0, i * 6);
        this.phantomGeo.setDrawRange(0, pi * 6);
        this.glowGeo.setDrawRange(0, glowSet ? 6 : 0);
        (window as any).__npcSprites =
            {atlas: true, drawn: i, ghosts: pi, fed: npcs.length};
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
