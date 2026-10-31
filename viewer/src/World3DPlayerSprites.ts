import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import {uploadPrefix, uploadQuads} from "./bufferUpload";
import {classOfSlot, cropIndex, loadPlayerLayerAtlas, palClamp, type PlayerLayerAtlas} from "./playerLayerAtlas";
import {need} from "./need";
import {anchorDepth, combatShift, DEPTH_FROM_ANCHOR} from "./spriteDepth";

/**
 * One player to draw this frame, at its interpolated tile position; `appearance` is its token.
 * `dx`, `dz` is the heading while `moving`; `dir` the server's facing, 0-7, or 8 and 9 for the
 * combat stances. An `offline` player is drawn as a faded ghost.
 */
export interface PlayerSpriteState {
    key: string;
    appearance: string;
    x: number;
    z: number;
    moving: boolean;
    dx: number;
    dz: number;
    dir?: number | null;
    offline?: boolean;
}

// The client's cycles: a player steps its walk every 6 client frames, combat stance A every 5 and B
// every 6, at 20 ms a client frame.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6656
const WALK_CYCLE = [0, 1, 2, 1];
const COMBAT_CYCLE_A = [0, 1, 2, 1, 0, 0, 0, 0];
const COMBAT_CYCLE_B = [0, 0, 0, 0, 0, 1, 2, 1];
const CLIENT_FRAME_MS = 20;

const T_BL = new THREE.Vector3();
const T_BR = new THREE.Vector3();
const T_TR = new THREE.Vector3();
const T_TL = new THREE.Vector3();
const T_WP = new THREE.Vector3();

const SLOTS = 12;
// Players beyond this are skipped.
const MAX_PLAYERS = 2048;
const MAX_QUADS = MAX_PLAYERS * SLOTS;
// Offline players drawn as ghosts.
const MAX_PHANTOM_PLAYERS = 512;
const MAX_PHANTOM_QUADS = MAX_PHANTOM_PLAYERS * SLOTS;

// Six bits per palette index, all four packed into one float attribute; exact in float32.
const PAL_SHIFT = 64;
const PAL_MAX = PAL_SHIFT;

const LOOK_CACHE_CAP = 4096;

interface Look {
    layers: Int32Array;
    pal: number;
}

// The client's recolour: the red channel is the shade, so colour times shade is one multiply; skin
// keeps its red channel and shades green and blue. No colour-space conversion either way, so a pixel
// reaches the screen as the byte the bake wrote.
function fragmentShader(nHair: number, nCloth: number, nSkin: number) {
    return `
precision highp float;
uniform sampler2D map;
uniform vec3 hairPal[${nHair}];
uniform vec3 clothPal[${nCloth}];
uniform vec3 skinPal[${nSkin}];
uniform float uOpacity;
/** Flat-colour override for the highlight halo; alpha 0 = shade normally. */
uniform vec4 uFlat;
in vec2 vUv;
// flat: the packed indices are identical at all four corners and are used as
// array subscripts, so interpolating them risks 3.0 arriving as 2.9999996.
flat in float vPal;
out vec4 outColor;
void main() {
    vec4 t = texture(map, vUv);
    // The alpha byte tags the mask; 0 is empty, and nothing else is coverage.
    float tag = floor(t.a * 255.0 + 0.5);
    if (tag < 0.5) discard;
    if (uFlat.a > 0.5) { outColor = vec4(uFlat.rgb, uOpacity); return; }
    // hair | top<<6 | bottom<<12 | skin<<18, see PAL_SHIFT.
    vec3 rgb;
    if (tag > 253.5) {
        // 0xFF already final, 0xFE hair.
        rgb = tag > 254.5 ? t.rgb : hairPal[int(mod(vPal, 64.0))] * t.r;
    } else if (tag > 251.5) {
        // 0xFD top, 0xFC bottom — both come out of the clothing palette.
        float c = tag > 252.5 ? floor(vPal / 64.0) : floor(vPal / 4096.0);
        rgb = clothPal[int(mod(c, 64.0))] * t.r;
    } else if (tag > 250.5) {
        vec3 sk = skinPal[int(mod(floor(vPal / 262144.0), 64.0))];
        rgb = vec3(sk.r * (255.0 / 256.0), sk.g * t.r, sk.b * t.r);
    } else {
        rgb = t.rgb; // unrecognised tag: the kernel takes the pixel as-is
    }
    outColor = vec4(rgb, uOpacity);
}`;
}

const VERTEX_SHADER = `
in vec3 position;
in vec2 uv;
/** Packed palette indices: hair | top<<6 | bottom<<12 | skin<<18. */
in float pal;
/** The player's NDC depth, identical at every corner of its twelve layer quads. */
in float depth;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
out vec2 vUv;
flat out float vPal;
void main() {
    vUv = uv;
    vPal = pal;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
    // One depth for every layer (see spriteDepth): LEQUAL and the stock
    // back-to-front layer order decide between them.
    ${DEPTH_FROM_ANCHOR}
}`;

function paletteVectors(table: number[]): THREE.Vector3[] {
    return table.map((c) => new THREE.Vector3(((c >> 16) & 255) / 255, ((c >> 8) & 255) / 255, (c & 255) / 255));
}

/**
 * Players as one quad per worn layer, all in one batch over the shared layer atlas, recoloured in
 * the shader. Frames, facing and the combat nudge are picked as the client's drawPlayer picks them;
 * a faint pass shows a player where something hides it, and offline players go in a ghost batch.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6642 mudclient.drawPlayer
 */
export class PlayerSpriteLayer {
    private readonly scene: THREE.Scene;
    private atlas: PlayerLayerAtlas | null = null;
    private readonly geo: THREE.BufferGeometry;
    private solid: THREE.Mesh | null = null;
    private readonly ghostGeo: THREE.BufferGeometry;
    private ghost: THREE.Mesh | null = null;
    private readonly phantomGeo: THREE.BufferGeometry;
    private phantom: THREE.Mesh | null = null;
    private phantomXray: THREE.Mesh | null = null;
    private readonly glowGeo: THREE.BufferGeometry;
    private glow: THREE.Mesh | null = null;
    highlightKey: string | null = null;
    private readonly lastDir = new Map<string, number>();
    private readonly stepAcc = new Map<string, number>();
    private lastFrameNow = -1;
    // Parsed appearance tokens, bounded since every re-equip mints a new one.
    private readonly looks = new Map<string, Look>();

    constructor(scene: THREE.Scene) {
        this.scene = scene;
        this.geo = new THREE.BufferGeometry();
        this.phantomGeo = new THREE.BufferGeometry();
        this.glowGeo = new THREE.BufferGeometry();
        allocQuads(this.geo, MAX_QUADS);
        this.ghostGeo = new THREE.BufferGeometry();
        for (const name of ["position", "uv", "pal", "depth"]) {
            this.ghostGeo.setAttribute(name, this.geo.getAttribute(name));
        }
        // The live batch's quads walked in reverse: the hidden-part pass keeps the first layer to land on a
        // pixel, so it must meet each player's layers front to back.
        this.ghostGeo.setIndex(new THREE.BufferAttribute(new Uint32Array(MAX_QUADS * 6), 1));
        this.ghostGeo.setDrawRange(0, 0);
        allocQuads(this.phantomGeo, MAX_PHANTOM_QUADS);
        allocQuads(this.glowGeo, SLOTS);
        loadPlayerLayerAtlas()
            .then((a) => this.build(a))
            .catch((e: unknown) => console.error("[players] layer atlas", e));
    }

    private build(atlas: PlayerLayerAtlas) {
        this.atlas = atlas;
        for (const [name, table] of Object.entries(atlas.palettes)) {
            if (table.length > PAL_MAX) {
                console.error(
                    `[players] ${name} palette has ${table.length} ` + `entries; the packed attribute holds ${PAL_MAX}`,
                );
            }
        }
        const shared = {
            map: {value: atlas.tex},
            hairPal: {value: paletteVectors(atlas.palettes.hair)},
            clothPal: {value: paletteVectors(atlas.palettes.clothing)},
            skinPal: {value: paletteVectors(atlas.palettes.skin)},
        };
        const frag = fragmentShader(
            atlas.palettes.hair.length,
            atlas.palettes.clothing.length,
            atlas.palettes.skin.length,
        );
        const make = (extra: THREE.MaterialParameters, opacity: number, flat: THREE.Vector4) =>
            new THREE.RawShaderMaterial({
                glslVersion: THREE.GLSL3,
                vertexShader: VERTEX_SHADER,
                fragmentShader: frag,
                uniforms: {...shared, uOpacity: {value: opacity}, uFlat: {value: flat}},
                side: THREE.DoubleSide,
                ...extra,
            });
        const NO_FLAT = new THREE.Vector4(0, 0, 0, 0);
        // Transparent to be ordered after the opaque pass, so the glow behind it rings it instead of covering
        // it. forceSinglePass: a DoubleSide transparent draw is otherwise split into two passes per frame.
        this.solid = new THREE.Mesh(this.geo, make({transparent: true, forceSinglePass: true}, 1, NO_FLAT));
        this.solid.renderOrder = 12;
        this.ghost = new THREE.Mesh(
            this.ghostGeo,
            make(
                // depthWrite, so stacked ghosts do not composite into an opaque blob; a player's layers share one
                // depth, so its first layer shows.
                {transparent: true, depthFunc: THREE.GreaterDepth, depthWrite: true, forceSinglePass: true},
                0.3,
                NO_FLAT,
            ),
        );
        this.ghost.renderOrder = 13;
        this.glow = new THREE.Mesh(
            this.glowGeo,
            make(
                {transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, forceSinglePass: true},
                1,
                new THREE.Vector4(1, 0.894, 0.361, 1),
            ),
        );
        this.glow.renderOrder = 11;
        this.phantom = new THREE.Mesh(
            this.phantomGeo,
            // No depthWrite: an offline ghost must not hide the live players behind it.
            make({transparent: true, depthWrite: false, forceSinglePass: true}, 0.4, NO_FLAT),
        );
        this.phantom.renderOrder = 12;
        this.phantomXray = new THREE.Mesh(
            this.phantomGeo,
            make(
                {transparent: true, depthFunc: THREE.GreaterDepth, depthWrite: false, forceSinglePass: true},
                0.12,
                NO_FLAT,
            ),
        );
        this.phantomXray.renderOrder = 13;
        // Named so a headless check can find the ghost batch in the scene graph.
        this.phantom.name = "playerGhosts";
        this.phantomXray.name = "playerGhostsXray";
        for (const m of [this.solid, this.ghost, this.glow, this.phantom, this.phantomXray]) {
            m.frustumCulled = false;
            m.userData.noPick = true;
            m.visible = false;
            this.scene.add(freezeTransform(m));
        }
    }

    private look(token: string): Look {
        let l = this.looks.get(token);
        if (l) return l;
        const atlas = need(this.atlas, "player layer atlas");
        const halves = token.split("|");
        const layerStr = (halves[0] ?? "").split(",");
        const layers = new Int32Array(SLOTS);
        for (let i = 0; i < SLOTS; i++) {
            layers[i] = i < layerStr.length ? parseInt(layerStr[i], 10) || 0 : 0;
        }
        const cs = (halves[1] ?? "").split(",").map((n) => parseInt(n, 10) || 0);
        l = {
            layers,
            pal:
                palClamp(atlas.palettes.hair, cs[0] ?? 0) +
                palClamp(atlas.palettes.clothing, cs[1] ?? 0) * PAL_SHIFT +
                palClamp(atlas.palettes.clothing, cs[2] ?? 0) * PAL_SHIFT ** 2 +
                palClamp(atlas.palettes.skin, cs[3] ?? 0) * PAL_SHIFT ** 3,
        };
        this.looks.set(token, l);
        while (this.looks.size > LOOK_CACHE_CAP) {
            const oldest = this.looks.keys().next();
            if (oldest.done) break;
            this.looks.delete(oldest.value);
        }
        return l;
    }

    /**
     * Rebuilds the batches for this frame. Sprites move `lift` engine units along `camToward`, the unit
     * vector toward the camera, so cards at high pitch clear the terrain under them.
     */
    frame(
        now: number,
        players: PlayerSpriteState[],
        toWorld: (x: number, z: number) => THREE.Vector3,
        camRight: THREE.Vector3,
        camUp: THREE.Vector3,
        camYaw: number,
        camToward: THREE.Vector3,
        lift: number,
        viewProj: THREE.Matrix4,
    ) {
        const atlas = this.atlas;
        if (!atlas || !this.solid || !this.ghost || !this.glow || !this.phantom || !this.phantomXray) return;
        const camRot = (128 - Math.round((camYaw / (2 * Math.PI)) * 256)) & 255;
        const dtFrames = this.lastFrameNow < 0 ? 0 : (now - this.lastFrameNow) / CLIENT_FRAME_MS;
        this.lastFrameNow = now;
        const posA = this.geo.getAttribute("position").array as Float32Array;
        const uvA = this.geo.getAttribute("uv").array as Float32Array;
        const palA = this.geo.getAttribute("pal").array as Float32Array;
        const depA = this.geo.getAttribute("depth").array as Float32Array;
        const pPosA = this.phantomGeo.getAttribute("position").array as Float32Array;
        const pUvA = this.phantomGeo.getAttribute("uv").array as Float32Array;
        const pPalA = this.phantomGeo.getAttribute("pal").array as Float32Array;
        const pDepA = this.phantomGeo.getAttribute("depth").array as Float32Array;
        const crops = atlas.crops;
        const invScale = 1 / atlas.scale;
        const anchorX = atlas.originX + (atlas.width1 >> 1);
        const anchorY = atlas.originY + atlas.height;
        const dbg: Record<string, {order: number; frame: number; layers: number; appearance: string}> = {};
        let q = 0;
        let pq = 0;
        let glowQuads = 0;
        for (const p of players) {
            if (!p.appearance) continue;
            const ghosted = p.offline === true;
            const outPos = ghosted ? pPosA : posA;
            const outUv = ghosted ? pUvA : uvA;
            const outPal = ghosted ? pPalA : palA;
            const outDep = ghosted ? pDepA : depA;
            if (ghosted ? pq + SLOTS > MAX_PHANTOM_QUADS : q + SLOTS > MAX_QUADS) continue;
            const look = this.look(p.appearance);
            // Never moved and never faced: north, as the server spawns a mob.
            let dir = this.lastDir.get(p.key) ?? 0;
            if (p.moving && (p.dx !== 0 || p.dz !== 0)) {
                const ang = Math.atan2(p.dx, -p.dz);
                dir = Math.round(((ang + 2 * Math.PI) % (2 * Math.PI)) / (Math.PI / 4)) & 7;
                this.lastDir.set(p.key, dir);
            } else if (p.dir != null && p.dir >= 0 && p.dir <= 7) {
                dir = p.dir;
                this.lastDir.set(p.key, dir);
            }
            if (p.moving && dtFrames > 0) {
                const axes = (p.dx !== 0 ? 1 : 0) + (p.dz !== 0 ? 1 : 0);
                this.stepAcc.set(p.key, (this.stepAcc.get(p.key) ?? 0) + axes * dtFrames);
            }
            const stepFrame = Math.floor(this.stepAcc.get(p.key) ?? 0);
            let order = (dir + ((camRot + 16) >> 5)) & 7;
            let walk = WALK_CYCLE[Math.floor(stepFrame / 6) % 4];
            let shift = 0;
            if (p.dir === 8 || p.dir === 9) {
                order = p.dir === 8 ? 8 : 9;
                walk =
                    p.dir === 8 ? COMBAT_CYCLE_A[Math.floor(now / 100) % 8] : COMBAT_CYCLE_B[Math.floor(now / 120) % 8];
                shift = p.dir === 8 ? -combatShift(5) : combatShift(5);
            }
            const wp = T_WP.copy(toWorld(p.x, p.z)).addScaledVector(camToward, lift);
            // Taken before the combat nudge: the client moves the drawn sprite, not the point its depth comes from.
            const depth = anchorDepth(viewProj, wp);
            wp.addScaledVector(camRight, shift);
            const drawOrder = atlas.layerOrder[order <= 7 ? order : 2];
            const glowing = p.key === this.highlightKey;
            const first = ghosted ? pq : q;
            for (const slot of drawOrder) {
                const animId = look.layers[slot] - 1;
                if (animId < 0 || animId > atlas.maxAnim) continue;
                const at = cropIndex(animId, classOfSlot(slot), order, walk);
                const ax = crops[at];
                if (ax < 0) continue;
                const ay = crops[at + 1],
                    w = crops[at + 2],
                    h = crops[at + 3];
                const dx = crops[at + 4],
                    dy = crops[at + 5];
                const lax = anchorX - dx,
                    lay = anchorY - dy;
                const w0 = ghosted ? pq : q;
                writeQuad(
                    outPos,
                    outUv,
                    w0,
                    wp,
                    camRight,
                    camUp,
                    -lax * invScale,
                    (w - lax) * invScale,
                    lay * invScale,
                    -(h - lay) * invScale,
                    ax / atlas.atlasW,
                    (ax + w) / atlas.atlasW,
                    ay / atlas.atlasH,
                    (ay + h) / atlas.atlasH,
                );
                outPal[w0 * 4] = look.pal;
                outPal[w0 * 4 + 1] = look.pal;
                outPal[w0 * 4 + 2] = look.pal;
                outPal[w0 * 4 + 3] = look.pal;
                outDep[w0 * 4] = depth;
                outDep[w0 * 4 + 1] = depth;
                outDep[w0 * 4 + 2] = depth;
                outDep[w0 * 4 + 3] = depth;
                if (ghosted) pq++;
                else q++;
            }
            const last = ghosted ? pq : q;
            dbg[p.key] = {order, frame: walk, layers: last - first, appearance: p.appearance};
            if (glowing && last > first) {
                glowQuads = inflateInto(this.glowGeo, outPos, outUv, outDep, first, last, 1.12);
            }
        }
        uploadQuads(this.geo, q, "position", "uv", "pal", "depth");
        uploadQuads(this.phantomGeo, pq, "position", "uv", "pal", "depth");
        this.geo.setDrawRange(0, q * 6);
        const ghostIdx = this.ghostGeo.getIndex();
        if (ghostIdx && q > 0) {
            const gi = ghostIdx.array as Uint32Array;
            for (let k = 0; k < q; k++) {
                const v = (q - 1 - k) * 4,
                    i = k * 6;
                gi[i] = v;
                gi[i + 1] = v + 1;
                gi[i + 2] = v + 2;
                gi[i + 3] = v;
                gi[i + 4] = v + 2;
                gi[i + 5] = v + 3;
            }
            uploadPrefix(ghostIdx, q * 6);
        }
        this.ghostGeo.setDrawRange(0, q * 6);
        this.phantomGeo.setDrawRange(0, pq * 6);
        this.solid.visible = q > 0;
        this.ghost.visible = q > 0;
        this.phantom.visible = pq > 0;
        this.phantomXray.visible = pq > 0;
        this.glowGeo.setDrawRange(0, glowQuads * 6);
        this.glow.visible = glowQuads > 0;
        window.__playerSprites = dbg;
    }

    // The atlas texture stays: loadPlayerLayerAtlas keeps it for the life of the page.
    dispose() {
        for (const m of [this.solid, this.ghost, this.glow, this.phantom, this.phantomXray]) {
            if (!m) continue;
            this.scene.remove(m);
            (m.material as THREE.Material).dispose();
        }
        this.phantomGeo.dispose();
        this.geo.dispose();
        this.ghostGeo.dispose();
        this.glowGeo.dispose();
    }
}

function allocQuads(geo: THREE.BufferGeometry, quads: number) {
    geo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(quads * 4 * 3), 3));
    geo.setAttribute("uv", new THREE.BufferAttribute(new Float32Array(quads * 4 * 2), 2));
    geo.setAttribute("pal", new THREE.BufferAttribute(new Float32Array(quads * 4), 1));
    geo.setAttribute("depth", new THREE.BufferAttribute(new Float32Array(quads * 4), 1));
    const idx = new Uint32Array(quads * 6);
    for (let i = 0; i < quads; i++) {
        idx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
    }
    geo.setIndex(new THREE.BufferAttribute(idx, 1));
    geo.setDrawRange(0, 0);
}

function writeQuad(
    posA: Float32Array,
    uvA: Float32Array,
    q: number,
    wp: THREE.Vector3,
    camRight: THREE.Vector3,
    camUp: THREE.Vector3,
    left: number,
    right: number,
    top: number,
    bottom: number,
    u0: number,
    u1: number,
    v0: number,
    v1: number,
) {
    const bl = T_BL.copy(wp).addScaledVector(camRight, left).addScaledVector(camUp, bottom);
    const br = T_BR.copy(wp).addScaledVector(camRight, right).addScaledVector(camUp, bottom);
    const tr = T_TR.copy(wp).addScaledVector(camRight, right).addScaledVector(camUp, top);
    const tl = T_TL.copy(wp).addScaledVector(camRight, left).addScaledVector(camUp, top);
    const p = q * 12;
    posA[p] = bl.x;
    posA[p + 1] = bl.y;
    posA[p + 2] = bl.z;
    posA[p + 3] = br.x;
    posA[p + 4] = br.y;
    posA[p + 5] = br.z;
    posA[p + 6] = tr.x;
    posA[p + 7] = tr.y;
    posA[p + 8] = tr.z;
    posA[p + 9] = tl.x;
    posA[p + 10] = tl.y;
    posA[p + 11] = tl.z;
    const u = q * 8;
    uvA[u] = u0;
    uvA[u + 1] = v1;
    uvA[u + 2] = u1;
    uvA[u + 3] = v1;
    uvA[u + 4] = u1;
    uvA[u + 5] = v0;
    uvA[u + 6] = u0;
    uvA[u + 7] = v0;
}

// Copies quads [from, to) into the glow batch, each scaled about the whole sprite's centre, so the
// halo is one outline rather than twelve.
function inflateInto(
    glowGeo: THREE.BufferGeometry,
    posA: Float32Array,
    uvA: Float32Array,
    depA: Float32Array,
    from: number,
    to: number,
    scale: number,
): number {
    const gpos = glowGeo.getAttribute("position") as THREE.BufferAttribute;
    const guv = glowGeo.getAttribute("uv") as THREE.BufferAttribute;
    const gp = gpos.array as Float32Array;
    const gd = glowGeo.getAttribute("depth").array as Float32Array;
    const gu = guv.array as Float32Array;
    let cx = 0,
        cy = 0,
        cz = 0,
        n = 0;
    for (let i = from * 12; i < to * 12; i += 3) {
        cx += posA[i];
        cy += posA[i + 1];
        cz += posA[i + 2];
        n++;
    }
    if (n === 0) return 0;
    cx /= n;
    cy /= n;
    cz /= n;
    let g = 0;
    for (let q = from; q < to && g < SLOTS; q++, g++) {
        const s = q * 12,
            d = g * 12;
        for (let v = 0; v < 4; v++) {
            gp[d + v * 3] = cx + (posA[s + v * 3] - cx) * scale;
            gp[d + v * 3 + 1] = cy + (posA[s + v * 3 + 1] - cy) * scale;
            gp[d + v * 3 + 2] = cz + (posA[s + v * 3 + 2] - cz) * scale;
        }
        gu.set(uvA.subarray(q * 8, q * 8 + 8), g * 8);
        gd.set(depA.subarray(q * 4, q * 4 + 4), g * 4);
    }
    uploadQuads(glowGeo, g, "position", "uv", "depth");
    return g;
}
