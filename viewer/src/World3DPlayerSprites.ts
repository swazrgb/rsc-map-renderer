import * as THREE from "three";
import {playerSpriteUrls} from "./api";

/**
 * Equipped player/bot sprites: per-appearance strips composed on demand by
 * the runner (engine drawPlayer rules) and cached by token. Entities sharing
 * an appearance share one texture + one dynamic quad batch; facing/walk/
 * mirror math matches the NPC sprite layer. The {json,png} strip URLs come
 * from the api module: a live server serves them on demand keyed by ?a=token,
 * a static host serves pre-baked files keyed by the token hash.
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
        }));
        this.glow.renderOrder = 11;
        this.glow.frustumCulled = false;
        this.glow.userData.noPick = true;
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
        const solid = new THREE.Mesh(geo, new THREE.MeshBasicMaterial({
            transparent: true, alphaTest: 0.06, side: THREE.DoubleSide,
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
        }));
        ghost.renderOrder = 13;
        ghost.frustumCulled = false;
        ghost.userData.noPick = true;
        this.scene.add(solid);
        this.scene.add(ghost);
        const strip: Strip = {tex: null, scale: 0.3, width: 1, height: 1,
            // Orders 0-7 = walk facings, 8/9 = combat stance A/B.
            frames: Array.from({length: 10}, () => []), geo, solid, ghost,
            failedAt: 0, cap: 0};
        this.growStrip(strip, BATCH_CAP);
        this.load(token, strip);
        return strip;
    }

    private load(token: string, strip: Strip) {
        // v must match PlayerSpriteService's cache-key version: the strips are
        // served with a long max-age, so a frame-layout/composition change
        // (v3 = stock mirrored-walk weapon swap) needs a URL change to bust
        // browser caches.
        playerSpriteUrls(token)
            .then(({json, png}) => fetch(json)
                .then(r => {
                    if (!r.ok) throw new Error(`${r.status}`);
                    return r.json();
                })
                .then((idx: {scale: number; width: number; height: number; frames: StripFrame[]}) => {
                    strip.scale = idx.scale;
                    strip.width = idx.width;
                    strip.height = idx.height;
                    for (const f of idx.frames) strip.frames[f.o][f.f] = f;
                    return new Promise<THREE.Texture>((res, rej) => {
                        new THREE.TextureLoader().load(png, res, undefined, rej);
                    });
                }))
            .then(tex => {
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
            if (!byToken.has(token)) strip.geo.setDrawRange(0, 0);
        }
        for (const [token, group] of byToken) {
            let strip = this.strips.get(token);
            if (!strip) {
                strip = this.makeStrip(token);
                this.strips.set(token, strip);
            }
            if (!strip.tex) {
                if (strip.failedAt && now - strip.failedAt > 8000) {
                    strip.failedAt = 0;
                    this.load(token, strip);
                }
                strip.geo.setDrawRange(0, 0);
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
                let dir = this.lastDir.get(p.key) ?? 4;
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
            strip.geo.setDrawRange(0, i * 6);
        }
        this.glowGeo.setDrawRange(0, glowSet ? 6 : 0);
        (window as any).__playerSprites = dbg;
    }

    dispose() {
        for (const [, s] of this.strips) {
            this.scene.remove(s.solid);
            this.scene.remove(s.ghost);
            s.geo.dispose();
            (s.solid.material as THREE.Material).dispose();
            (s.ghost.material as THREE.Material).dispose();
            s.tex?.dispose();
        }
        this.strips.clear();
        this.scene.remove(this.glow);
        this.glowGeo.dispose();
        (this.glow.material as THREE.Material).dispose();
    }
}
