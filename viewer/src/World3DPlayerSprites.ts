import * as THREE from "three";
import {compositePlayerStrip} from "./playerCompositor";

/**
 * Equipped player/bot sprites: per-appearance strips composited in the browser
 * ({@link import("./playerCompositor")}) from the per-layer atlas and cached by
 * token — any {@code layers|colours} appearance renders with no server and
 * nothing pre-baked per token. Entities sharing an appearance share one texture
 * + one dynamic quad batch; facing/walk/mirror math matches the NPC sprite layer.
 */

interface StripFrame {
    o: number;
    f: number;
    x: number;
    y: number;
    w: number;
    h: number;
    ax: number;
    ay: number;
}

interface Strip {
    tex: THREE.Texture | null;
    scale: number;
    width: number;
    height: number;
    frames: StripFrame[][]; // [order][walk]
    geo: THREE.BufferGeometry;
    solid: THREE.Mesh;
    ghost: THREE.Mesh;
    failedAt: number;
    /** Current quad capacity — grown on demand (a whole swarm can share one
     *  appearance token, so a fixed per-strip batch would silently drop). */
    cap: number;
    /** `now` of the last frame this token had players. Strips idle longer than
     *  STRIP_TTL_MS are evicted by frame(). */
    lastUsed: number;
}

export interface PlayerSpriteState {
    key: string;
    appearance: string;
    x: number;
    z: number;
    moving: boolean;
    dx: number;
    dz: number;
    /** Server sprite direction: 0-7 facing, 8/9 = combat stance A/B. */
    dir?: number | null;
}

const WALK_CYCLE = [0, 1, 2, 1];
// Combat swing cycles (mudclient animFrameToSprite_CombatA/B); see the NPC
// layer for the client provenance. ±30 = client setCombatXOffset. Players
// hardcode combat divisors 5 (A) / 6 (B) and walk divisor 6.
const COMBAT_CYCLE_A = [0, 1, 2, 1, 0, 0, 0, 0];
const COMBAT_CYCLE_B = [0, 0, 0, 0, 0, 1, 2, 1];
const CLIENT_FRAME_MS = 20; // the client's 50fps logic tick

// Per-frame scratch — see the NPC layer (allocation-free corner math).
const T_BL = new THREE.Vector3();
const T_BR = new THREE.Vector3();
const T_TR = new THREE.Vector3();
const T_TL = new THREE.Vector3();
/** Initial per-strip quad capacity; doubles on demand up to MAX_BATCH.
 *  MAX_BATCH covers the server's max_players (2000) all sharing one token. */
const BATCH_CAP = 160;
const MAX_BATCH = 2048;
/** How long a token's strip survives with nobody wearing it. Tokens churn for
 *  the life of the page (every re-equip mints a new one), so without an
 *  eviction the scene accumulates a mesh pair + material pair + texture per
 *  appearance ever seen. The compositor caches the composited PNG by token,
 *  so a token that comes back reloads without re-compositing. */
const STRIP_TTL_MS = 60_000;
/** New appearance strips to start compositing per frame — see frame(). */
const MAX_NEW_STRIPS_PER_FRAME = 2;

export class PlayerSpriteLayer {
    private readonly scene: THREE.Scene;
    private readonly strips = new Map<string, Strip>();
    private readonly lastDir = new Map<string, number>();
    /** Sprite drawn with an additive glow halo (hover/selection feedback),
     *  same treatment as the NPC layer. */
    highlightKey: string | null = null;
    private readonly glowGeo: THREE.BufferGeometry;
    private readonly glow: THREE.Mesh;
    /** Stock stepFrame: fractional client frames walked, per entity. Advances
     *  only while moving (per axis — diagonals count double), never resets, so
     *  a stopped character freezes mid-stride exactly like the game. */
    private readonly stepAcc = new Map<string, number>();
    private lastFrameNow = -1;

    constructor(scene: THREE.Scene) {
        this.scene = scene;
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
        this.glow = new THREE.Mesh(this.glowGeo, new THREE.MeshBasicMaterial({
            transparent: true,
            alphaTest: 0.06,
            color: 0xffe45c,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
            side: THREE.DoubleSide,
            // Flat camera-facing quad: it can never overlap itself, so the
            // back-then-front two-pass three runs for transparent DoubleSide
            // materials buys nothing and costs a full program rebuild per pass
            // (it flips material.side and sets needsUpdate between them).
            forceSinglePass: true,
        }));
        this.glow.renderOrder = 11;
        this.glow.frustumCulled = false;
        this.glow.userData.noPick = true;
        this.glow.visible = false; // no highlight yet — see setStripQuads
        scene.add(this.glow);
    }

    /** (Re)allocate a strip's quad buffers — contents are rewritten every
     *  frame, so no copy is needed. */
    private growStrip(strip: {geo: THREE.BufferGeometry; cap: number}, quads: number) {
        strip.cap = quads;
        strip.geo.setAttribute("position",
            new THREE.BufferAttribute(new Float32Array(quads * 4 * 3), 3));
        strip.geo.setAttribute("uv",
            new THREE.BufferAttribute(new Float32Array(quads * 4 * 2), 2));
        const idx = new Uint16Array(quads * 6);
        for (let i = 0; i < quads; i++) {
            idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        strip.geo.setIndex(new THREE.BufferAttribute(idx, 1));
        strip.geo.setDrawRange(0, 0);
    }

    private makeStrip(token: string): Strip {
        const geo = new THREE.BufferGeometry();
        // forceSinglePass on both: these are flat camera-facing billboards, so
        // the back-then-front split three does for transparent DoubleSide
        // materials draws the same pixels twice AND rebuilds the shader
        // program twice per frame (it sets material.needsUpdate between the
        // passes, which bumps material.version → needsProgramChange).
        //
        // It is NOT purely a perf change for a STACK of co-located sprites (30
        // idle bots on one tile). Both materials write depth, so which quad of
        // the stack survives depends on the order fragments reach the depth
        // test, and splitting into a back pass then a front pass changes that
        // order. Observed on the live swarm: a stack now resolves to ONE clean
        // sprite where the two-pass version left them faintly layered — which
        // is what the ghost's depth trick below was aiming at anyway. Drop
        // forceSinglePass from the ghost alone to get the layered look back,
        // at roughly half the saving.
        const solid = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({
            transparent: true, alphaTest: 0.06, side: THREE.DoubleSide,
            forceSinglePass: true,
        }));
        solid.renderOrder = 12;
        solid.frustumCulled = false;
        solid.userData.noPick = true; // exclude from the GPU depth pick
        const ghost = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({
            transparent: true, opacity: 0.3, alphaTest: 0.04,
            // depthWrite TRUE so a STACK of co-located identical sprites (e.g.
            // 30 idle bots on one tile) doesn't composite 30× the ghost alpha
            // into an opaque blob: the first quad writes its depth, the rest at
            // the same depth fail the strict GreaterDepth test → one draws.
            depthFunc: THREE.GreaterDepth, depthWrite: true, side: THREE.DoubleSide,
            forceSinglePass: true,
        }));
        ghost.renderOrder = 13;
        ghost.frustumCulled = false;
        ghost.userData.noPick = true;
        // Both start hidden: growStrip leaves the draw range empty and frame()
        // reveals them only once quads are written (see setStripQuads).
        solid.visible = false;
        ghost.visible = false;
        this.scene.add(solid);
        this.scene.add(ghost);
        const strip: Strip = {tex: null, scale: 0.3, width: 1, height: 1,
            // Orders 0-7 = walk facings, 8/9 = combat stance A/B.
            frames: Array.from({length: 10}, () => []), geo, solid, ghost,
            failedAt: 0, cap: 0, lastUsed: 0};
        this.growStrip(strip, BATCH_CAP);
        this.load(token, strip);
        return strip;
    }

    /** Set a strip's draw range AND its meshes' visibility together — an empty
     *  strip must be `visible = false`, never merely zero-length.
     *
     *  <p>three runs the whole setProgram path (program cache-key rebuild, and
     *  twice over for a transparent DoubleSide material) BEFORE it looks at the
     *  draw range, so a zero-length strip left visible costs a drawn strip's
     *  full per-frame CPU. With one strip per appearance token that was the
     *  frame budget: ~1000 empty strips → ~2800 program rebuilds per frame. */
    private setStripQuads(strip: Strip, quads: number) {
        strip.geo.setDrawRange(0, quads * 6);
        strip.solid.visible = quads > 0;
        strip.ghost.visible = quads > 0;
    }

    private disposeStrip(strip: Strip) {
        this.scene.remove(strip.solid);
        this.scene.remove(strip.ghost);
        strip.geo.dispose();
        (strip.solid.material as THREE.Material).dispose();
        (strip.ghost.material as THREE.Material).dispose();
        strip.tex?.dispose();
    }

    private load(token: string, strip: Strip) {
        // Composite the appearance token into a strip in the browser (from the
        // per-layer atlas) and upload it; tokens are cached so entities sharing
        // an appearance reuse one strip.
        compositePlayerStrip(token)
            .then(({canvas, index}) => {
                strip.scale = index.scale;
                strip.width = index.width;
                strip.height = index.height;
                for (const f of index.frames) strip.frames[f.o][f.f] = f;
                // Straight from the composited canvas — no PNG encode, no blob
                // URL, no fetch of our own JSON back, no image decode. flipY
                // false keeps the UVs identical to the old TextureLoader path.
                const tex = new THREE.CanvasTexture(canvas);
                tex.magFilter = THREE.NearestFilter;
                tex.minFilter = THREE.NearestFilter;
                tex.flipY = false;
                tex.colorSpace = THREE.SRGBColorSpace;
                strip.tex = tex;
                (strip.solid.material as THREE.MeshBasicMaterial).map = tex;
                (strip.solid.material as THREE.MeshBasicMaterial).needsUpdate = true;
                (strip.ghost.material as THREE.MeshBasicMaterial).map = tex;
                (strip.ghost.material as THREE.MeshBasicMaterial).needsUpdate = true;
            })
            .catch(() => {
                strip.failedAt = performance.now(); // retried by frame() after cooldown
            });
    }

    frame(now: number, players: PlayerSpriteState[],
          toWorld: (x: number, z: number) => THREE.Vector3,
          camRight: THREE.Vector3, camUp: THREE.Vector3, camYaw: number,
          camToward: THREE.Vector3, lift: number) {
        const camRot = Math.round((camYaw / (2 * Math.PI)) * 256) & 255;
        const dtFrames = this.lastFrameNow < 0 ? 0
            : (now - this.lastFrameNow) / CLIENT_FRAME_MS;
        this.lastFrameNow = now;
        let glowSet = false;
        const dbg: Record<string, {order: number; frame: number; tex: boolean}> = {};
        const byToken = new Map<string, PlayerSpriteState[]>();
        for (const p of players) {
            if (!p.appearance) continue;
            let arr = byToken.get(p.appearance);
            if (!arr) byToken.set(p.appearance, arr = []);
            arr.push(p);
        }
        for (const [token, strip] of this.strips) {
            if (byToken.has(token)) continue;
            this.setStripQuads(strip, 0);
            // Nobody has worn this appearance for a while — give the mesh
            // pair, material pair and texture back. Deleting while iterating a
            // Map is safe.
            if (now - strip.lastUsed > STRIP_TTL_MS) {
                this.disposeStrip(strip);
                this.strips.delete(token);
            }
        }
        let composedThisFrame = 0;
        for (const [token, group] of byToken) {
            let strip = this.strips.get(token);
            if (!strip) {
                // Compositing a strip is expensive (10 facings × 3 walk frames
                // over a 512² buffer, then a PNG encode+decode), and on a cold
                // start every player in view is a new token at once — measured
                // at ~47% of load CPU, starving the map-cell load it competes
                // with. Start a bounded number per frame; the rest arrive over
                // the next few frames, which is invisible next to the wait they
                // were causing.
                if (composedThisFrame >= MAX_NEW_STRIPS_PER_FRAME) continue;
                composedThisFrame++;
                strip = this.makeStrip(token);
                this.strips.set(token, strip);
            }
            strip.lastUsed = now;
            if (!strip.tex) {
                if (strip.failedAt && now - strip.failedAt > 8000) {
                    strip.failedAt = 0;
                    this.load(token, strip);
                }
                this.setStripQuads(strip, 0);
                continue;
            }
            // Grow the strip's batch when the crowd sharing this appearance
            // outgrows it (double up to MAX_BATCH — a full server can stack
            // 2000 players on one token; beyond the max, extras are skipped).
            if (group.length > strip.cap && strip.cap < MAX_BATCH) {
                let want = strip.cap;
                while (want < group.length && want < MAX_BATCH) {
                    want *= 2;
                }
                this.growStrip(strip, Math.min(want, MAX_BATCH));
            }
            const pos = strip.geo.getAttribute("position") as THREE.BufferAttribute;
            const uv = strip.geo.getAttribute("uv") as THREE.BufferAttribute;
            let i = 0;
            for (const p of group) {
                if (i >= strip.cap) break;
                // Never walked and no server facing: the spawn default, which
                // is Mob.mobSprite = 0 (north) — same rule as the npc layer.
                let dir = this.lastDir.get(p.key) ?? 0;
                if (p.moving && (p.dx !== 0 || p.dz !== 0)) {
                    const ang = Math.atan2(p.dx, -p.dz);
                    dir = Math.round(((ang + 2 * Math.PI) % (2 * Math.PI)) / (Math.PI / 4)) & 7;
                    this.lastDir.set(p.key, dir);
                } else if (p.dir != null && p.dir >= 0 && p.dir <= 7) {
                    // Stopped: face the server-dictated standing direction, as
                    // the stock client does (mudclient.java:11738, reaching the
                    // last waypoint: direction = lookup(animationNext)). Server
                    // streams the facing via updateType-1 sprite updates; same
                    // RSC encoding as our atan2 dir, so the order formula below
                    // is unchanged. Prevents idle players/bots from freezing
                    // facing wherever they last walked.
                    dir = p.dir;
                    this.lastDir.set(p.key, dir);
                }
                // Stock stepFrame: advance while moving, one count per moving
                // axis per 20ms client frame; freeze (don't reset) when still.
                if (p.moving && dtFrames > 0) {
                    const axes = (p.dx !== 0 ? 1 : 0) + (p.dz !== 0 ? 1 : 0);
                    this.stepAcc.set(p.key,
                        (this.stepAcc.get(p.key) ?? 0) + axes * dtFrames);
                }
                const stepFrame = Math.floor(this.stepAcc.get(p.key) ?? 0);
                let order = (4 - dir + ((camRot + 16) >> 5)) & 7;
                // Walk frame = WALK[stepFrame / 6 % 4] (drawPlayer).
                let frameIdx = WALK_CYCLE[Math.floor(stepFrame / 6) % 4];
                // Combat stance: camera-independent profile pair (A faces B);
                // falls back to walk facing on strips without combat frames.
                if (p.dir === 8 || p.dir === 9) {
                    // Stock combat render: both fighters overlap at the same
                    // spot (the ±30 is pick-only) — see the NPC layer. A
                    // faces right via the flipped frame set, B left.
                    const order2 = p.dir === 8 ? 9 : 8;
                    if (strip.frames[order2]?.length) {
                        order = order2;
                        frameIdx = p.dir === 8
                            ? COMBAT_CYCLE_A[Math.floor(now / 100) % 8]
                            : COMBAT_CYCLE_B[Math.floor(now / 120) % 8];
                    }
                }
                dbg[p.key] = {order, frame: frameIdx, tex: !!strip.tex};
                const frames = strip.frames[order];
                if (!frames || frames.length === 0) continue;
                const f = frames[Math.min(frameIdx, frames.length - 1)] ?? frames[0];
                if (!f) continue;
                const wp = toWorld(p.x, p.z).addScaledVector(camToward, lift);
                const s = strip.scale;
                const left = -f.ax / s;
                const right = (f.w - f.ax) / s;
                const top = f.ay / s;
                const bottom = -(f.h - f.ay) / s;
                const bl = T_BL.copy(wp).addScaledVector(camRight, left).addScaledVector(camUp, bottom);
                const br = T_BR.copy(wp).addScaledVector(camRight, right).addScaledVector(camUp, bottom);
                const tr = T_TR.copy(wp).addScaledVector(camRight, right).addScaledVector(camUp, top);
                const tl = T_TL.copy(wp).addScaledVector(camRight, left).addScaledVector(camUp, top);
                pos.setXYZ(i * 4, bl.x, bl.y, bl.z);
                pos.setXYZ(i * 4 + 1, br.x, br.y, br.z);
                pos.setXYZ(i * 4 + 2, tr.x, tr.y, tr.z);
                pos.setXYZ(i * 4 + 3, tl.x, tl.y, tl.z);
                const u0 = f.x / strip.width;
                const u1 = (f.x + f.w) / strip.width;
                const v0 = f.y / strip.height;
                const v1 = (f.y + f.h) / strip.height;
                uv.setXY(i * 4, u0, v1);
                uv.setXY(i * 4 + 1, u1, v1);
                uv.setXY(i * 4 + 2, u1, v0);
                uv.setXY(i * 4 + 3, u0, v0);
                if (p.key === this.highlightKey) {
                    // Same quad scaled ~12% about its centre for the halo.
                    const gpos = this.glowGeo
                        .getAttribute("position") as THREE.BufferAttribute;
                    const guv = this.glowGeo
                        .getAttribute("uv") as THREE.BufferAttribute;
                    const cx = (bl.x + tr.x) / 2;
                    const cy = (bl.y + tr.y) / 2;
                    const cz = (bl.z + tr.z) / 2;
                    const corners = [bl, br, tr, tl];
                    const scGlow = 1.12;
                    for (let k = 0; k < 4; k++) {
                        gpos.setXYZ(k,
                            cx + (corners[k].x - cx) * scGlow,
                            cy + (corners[k].y - cy) * scGlow,
                            cz + (corners[k].z - cz) * scGlow);
                    }
                    guv.setXY(0, u0, v1);
                    guv.setXY(1, u1, v1);
                    guv.setXY(2, u1, v0);
                    guv.setXY(3, u0, v0);
                    gpos.needsUpdate = true;
                    guv.needsUpdate = true;
                    const gm = this.glow.material as THREE.MeshBasicMaterial;
                    if (gm.map !== strip.tex) {
                        gm.map = strip.tex;
                        gm.needsUpdate = true;
                    }
                    glowSet = true;
                }
                i++;
            }
            pos.needsUpdate = true;
            uv.needsUpdate = true;
            this.setStripQuads(strip, i);
        }
        this.glowGeo.setDrawRange(0, glowSet ? 6 : 0);
        this.glow.visible = glowSet;
        (window as any).__playerSprites = dbg;
    }

    dispose() {
        for (const [, s] of this.strips) this.disposeStrip(s);
        this.strips.clear();
        this.scene.remove(this.glow);
        this.glowGeo.dispose();
        (this.glow.material as THREE.Material).dispose();
    }
}
