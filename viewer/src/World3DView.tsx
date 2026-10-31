import {useEffect, useImperativeHandle, useRef, useState, type ReactNode, type Ref} from "react";
import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import type {Observer, NpcRespawn, ObjectRespawn, NpcSpawnInfo, RoutePoint, TrailPoint, WorldEntities} from "./api";
import {fetchNpcSpawns, sendInteract, sendWalk} from "./api";
import {VirtualClock, pickCaptureDir, canvasPng, writeFrame} from "./capture";

const WEAR_SLOTS = [
    "head",
    "body",
    "legs",
    "shield",
    "weapon",
    "hat",
    "body",
    "legs",
    "gloves",
    "boots",
    "neck",
    "cape",
];
// The unequipped look's sprites in appearance slots 0-2 (hair styles, the two bodies, legs), which
// worn helmets, bodies and legs replace.
const BASE_SPRITES: Partial<Record<number, number[]>> = {
    0: [1, 4, 6, 7, 8],
    1: [2, 5],
    2: [3],
};
/**
 * What an appearance token shows worn, slot by slot: base sprites skipped, every other sprite named
 * by the items `wearables` maps it to (the first few, when several share it), or by its sprite id.
 */
export function wornItems(token: string, wearables: Record<number, string[]> | null): {slot: string; names: string}[] {
    const out: {slot: string; names: string}[] = [];
    const layers = token.split("|")[0].split(",").map(Number);
    for (let i = 0; i < layers.length; i++) {
        if (!layers[i] || BASE_SPRITES[i]?.includes(layers[i])) continue;
        const names = wearables?.[layers[i]];
        const label = !names?.length
            ? `sprite ${layers[i]}`
            : names.slice(0, 3).join(" / ") + (names.length > 3 ? ` +${names.length - 3} more` : "");
        out.push({slot: WEAR_SLOTS[i] ?? `slot ${i}`, names: label});
    }
    return out;
}
import {Entity3D, EntityLayer} from "./World3DEntities";
import {ObjectLibrary, ResolvedPlacement, assembleCell, sceneryHub} from "./World3DScenery";
import {BoundaryPlacement, DoorDefLite, assembleDoors, fetchDoorData} from "./World3DDoors";
import {NpcSpriteLayer, NpcSpriteState, fetchNpcAtlas} from "./World3DNpcSprites";
import {spriteViewProj} from "./spriteDepth";
import {PlayerSpriteLayer, PlayerSpriteState} from "./World3DPlayerSprites";
import {GroundItemLayer, GroundItem3D, fetchItemAtlas} from "./World3DGroundItems";
import {loadGameFont} from "./World3DChatFont";
import {Ribbon, SightLayer, WatchedSightLayer} from "./World3DSight";
import {TagPool, resolveTagClick, type ViewSize} from "./World3DTags";
import {OverlayHost, boxEdges, boxPerimeter, overlay, type OverlayEntry, type OverlayPoint} from "./World3DOverlays";
import {planOverlay, routeOverlay, trailOverlay} from "./World3DPaths";
import {clusterOverlay, manifestClusters} from "./World3DClusters";
import {dangerOverlay} from "./World3DDanger";
import {componentsOverlay} from "./World3DComponents";
import {WildernessLayer, wildernessStrideFor} from "./World3DWilderness";
import {ProjectileLayer, ProjectileFlight, PROJECTILE_FLIGHT_MS} from "./World3DProjectiles";
import {CompassGizmo} from "./World3DCompass";
import type {SceneryPlacement} from "./api";
import {
    fetchScenery,
    fetchSceneryAtlasIndex,
    fetchWorldMeshManifest,
    WorldMeshCell,
    WorldMeshManifest,
    worldMeshCellUrl,
    worldMeshTextureUrl,
} from "./api";
import type {PickedEntity} from "./EntityCard";
import {need} from "./need";

// The pitch a view without `?pitch=` opens at, and the compass returns to.
const HOME_PITCH_DEG = 45;
/** How far, in tiles, a followed entity may move between frames before the camera cuts to it instead. */
const FOLLOW_CUT_TILES = 40;

// The stock menu: grey D0 at alpha 160 of 256, a cyan title, white entries turning yellow under the cursor.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/gui/Menu.java#L304
const MENU_STYLE = {
    background: "rgba(208,208,208,0.63)",
    padding: "2px 6px 4px 4px",
    font: "bold 12px sans-serif",
    whiteSpace: "nowrap",
    userSelect: "none",
} as const;
const MENU_TEXT_SHADOW = "1px 0 #000, 0 1px #000";

/** One line of a right-click menu: a white verb, then its target in `tcolor`, ` x count` after it past 1. */
export interface MenuLine {
    verb: string;
    target?: string;
    tcolor?: string;
    count?: number;
}

// The toolbar's display order. Numeric floors everywhere else are the game's: 0 ground, 1 and 2
// upper, 3 underground; convert through FLOOR_KEYS_BY_INDEX, never this array.
const FLOORS = [
    {key: "underground", label: "Underground"},
    {key: "ground", label: "Ground"},
    {key: "floor1", label: "Floor 1"},
    {key: "floor2", label: "Floor 2"},
] as const;
type FloorKey = (typeof FLOORS)[number]["key"];

/** Floor keys by the game's floor index: 0 ground, 1 and 2 upper, 3 underground. */
const FLOOR_KEYS_BY_INDEX = ["ground", "floor1", "floor2", "underground"] as const;
const floorKeyForIndex = (i: number): FloorKey => FLOOR_KEYS_BY_INDEX[Math.max(0, Math.min(3, Math.trunc(i)))];
/** The floor index of a key, as `?floor=` carries it; ground for anything unknown. */
export const floorIndexForKey = (k: string | null | undefined): number => {
    const i = FLOOR_KEYS_BY_INDEX.indexOf(k as FloorKey);
    return i < 0 ? 0 : i;
};

// What the roofs toggle hides: as in the client under a roof, the plane's roofs and every upper
// storey's walls and roofs.
const ROOF_KINDS = new Set(["roofs", "roofs1", "roofs2", "walls1", "walls2"]);

// Upper floors are baked flat while the ground keeps its elevation. Shown beneath floor 1 or 2, the
// ground gives up its elevation in the shader (FLATTEN) and each floor beneath drops a storey: 192
// units, the height of nearly every wall.
const STOREY_UNITS = 192;
// Which storey a ground-session kind draws: the copies a lower plane replaces when stacked.
const STOREY_OF_KIND: Partial<Record<string, number>> = {walls1: 1, roofs1: 1, walls2: 2, roofs2: 2};

const WINDMILL_ID = 74;
// Windmill sails turn continuously in the client; one revolution per this long, whatever the frame rate.
const WINDMILL_PERIOD_MS = 4000;
// Tiles the pick point must cross past a tile edge before the hovered tile switches.
const HOVER_TILE_HYST = 0.18;

// The cell plane and kinds a floor shows. Scenery is not a baked kind: it is assembled here, so live
// state can override it.
function kindsFor(floor: FloorKey): {plane: number; kinds: string[]} {
    switch (floor) {
        case "ground":
            return {plane: 0, kinds: ["terrain", "walls", "walls1", "walls2", "roofs", "roofs1", "roofs2"]};
        case "floor1":
            return {plane: 1, kinds: ["terrain", "walls", "roofs"]};
        case "floor2":
            return {plane: 2, kinds: ["terrain", "walls", "roofs"]};
        case "underground":
            return {plane: 3, kinds: ["terrain", "walls"]};
    }
}

interface FlyPoint {
    x: number;
    z: number;
    yaw: number;
    pitch: number;
    zoom: number;
    sec?: number;
}

// The flyby, a closed loop run at constant speed, so one period records seamlessly. `sec` is the
// leg arriving at a point; the first point's is the return leg.
const FLYBY: FlyPoint[] = [
    {x: 120, z: 640, yaw: 45, pitch: 89, zoom: 500, sec: 10},
    {x: 120, z: 640, yaw: 45, pitch: 40, zoom: 40, sec: 4},
    {x: 122, z: 640, yaw: 225, pitch: 35, zoom: 35, sec: 8},
    {x: 215, z: 630, yaw: 270, pitch: 45, zoom: 60, sec: 8},
    {x: 310, z: 570, yaw: 315, pitch: 50, zoom: 80, sec: 8},
    {x: 300, z: 560, yaw: 405, pitch: 89, zoom: 700, sec: 8},
];

// The `?capture=<fps>` recording frame rate; 0 when not recording.
function readCaptureFps(): number {
    const v = parseFloat(new URLSearchParams(location.search).get("capture") ?? "");
    return Number.isFinite(v) && v > 0 ? Math.round(v) : 0;
}

// A "WxH" recording size; null for blank, an error for anything malformed.
function parseCaptureSize(s: string): {w: number; h: number} | null {
    if (!s.trim()) return null;
    const m = /^\s*(\d+)\s*[x×X]\s*(\d+)\s*$/.exec(s);
    const w = m ? parseInt(m[1], 10) : 0;
    const h = m ? parseInt(m[2], 10) : 0;
    if (!w || !h) throw new Error(`bad resolution "${s}" — use e.g. 1920x1080`);
    return {w, h};
}

// Cubic Hermite from p0 to p1 with endpoint velocities per unit time over a leg of `dt`, so the
// flyby stays smooth across legs of different lengths.
function hermite(p0: number, m0: number, p1: number, m1: number, dt: number, s: number): number {
    const s2 = s * s;
    const s3 = s2 * s;
    return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * dt * m0 + (-2 * s3 + 3 * s2) * p1 + (s3 - s2) * dt * m1;
}

const VERT = `
in vec3 position;
in float shade;
in vec3 base;
in vec2 uv;
// Packed GPU-pick id per vertex (see the id-pick); unused in normal shading,
// emitted as colour when pickMode is on. Absent on terrain geometry → the GL
// default (0,0,0,1) → decoded as "not an object".
in vec4 pickId;
uniform mat4 modelViewMatrix;
uniform mat4 projectionMatrix;
// FLATTEN: the ground drawn beneath an upper floor, in THAT floor's frame. Upper floors are baked
// flat — their sessions carry no elevation — while the ground rolls (387 units up at Lumbridge
// castle), so no rigid move puts it under every building at once. The ground gives up its own
// height instead: terrain, walls, roofs and doors vertex by vertex (their vertices sit on tile
// corners, where that is exact, and a tile the engine levels for water sinks under its bridge),
// and a scenery object all at once, by the ground under its anchor tile — vertex by vertex a
// tree on a slope leans. The storey drop is the group's transform. The pick pass runs this same
// shader, so hover and clicks agree with the eye.
#ifdef FLATTEN
// One texel a tile corner: the ground's height there, plus a half when the tile at that corner
// is split along the other diagonal (heights are whole numbers, so the two unpack exactly).
uniform highp sampler2D groundHeights;
uniform float worldWidth;              // units; x is mirrored: corner cx = (worldWidth - x) / 128
float groundTexel(ivec2 i) {
    return texelFetch(groundHeights,
        clamp(i, ivec2(0), textureSize(groundHeights, 0) - 1), 0).r;
}
// The engine's ground under a point given in corner coordinates: a tile is two triangles, never
// a bilinear patch — heightAtGrid, which this mirrors term for term.
float groundAt(vec2 c) {
    ivec2 i = ivec2(floor(c));
    vec2 f = c - floor(c);
    float t00 = groundTexel(i);
    float h00 = floor(t00);
    float h10 = floor(groundTexel(i + ivec2(1, 0)));
    float h01 = floor(groundTexel(i + ivec2(0, 1)));
    float h11 = floor(groundTexel(i + ivec2(1, 1)));
    if (t00 - h00 > 0.25) {
        return f.x >= f.y
            ? h00 + (h10 - h00) * f.x + (h11 - h10) * f.y
            : h00 + (h01 - h00) * f.y + (h11 - h01) * f.x;
    }
    if (f.x <= 1.0 - f.y) return h00 + (h10 - h00) * f.x + (h01 - h00) * f.y;
    f = 1.0 - f;
    return h11 + (h01 - h11) * f.x + (h10 - h11) * f.y;
}
#endif
out float vShade;
out vec3 vBase;
out vec2 vUv;
out vec4 vPickId;
void main() {
    vShade = shade;
    vBase = base;
    vUv = uv;
    vPickId = pickId;
    vec3 p = position;
#ifdef FLATTEN
    // The packed id's top byte (see packSceneryId): 8..11 is a scenery object, whose low 24
    // bits are its anchor tile — x in bits 12-23, z in bits 0-11. Anything else (a door, or the
    // 255 of geometry that carries no id) is flattened where each vertex stands.
    ivec4 idBytes = ivec4(pickId * 255.0 + 0.5);
    vec2 corner = vec2((worldWidth - p.x) / 128.0, p.z / 128.0);
    if (idBytes.a >= 8 && idBytes.a <= 11) {
        int id = idBytes.r | (idBytes.g << 8) | (idBytes.b << 16);
        corner = vec2(float((id >> 12) & 4095), float(id & 4095)) + 0.5;
    }
    p.y -= groundAt(corner);
#endif
    gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}`;

const FRAG = `
precision highp float;
uniform sampler2D map;
uniform bool textured;
// Water scroll (client scene_scroll_texture: texture 17 shifts 1px/20ms
// frame): the material's per-frame v offset; 0 for everything else.
uniform float vScroll;
// Pick pass: 0 = normal shading, 1 = emit the packed placement id (attachment
// 0) AND the packed window-space depth (attachment 1) in one go. Both happen
// AFTER the alpha discard, so a transparent hole in a doorframe (or any
// textured cut-out) passes the pick through to whatever is visible behind it —
// the id AND the depth agree with the eye, so the object/entity tiebreak,
// rotate pivot and tile-under-cursor all see through holes too.
uniform int pickMode;
in float vShade;
in vec3 vBase;
in vec2 vUv;
in vec4 vPickId;
// Two outputs, so one pick pass writes both the id and the depth: the same rasterisation through the
// same 1px sub-frustum, to two attachments of an MRT target. Attachment 1 has no counterpart on the
// default framebuffer, so during the on-screen pass its draw buffer is NONE and GLES 3.0 discards the write.
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outDepth;
// Self-contained 24-bit pack of window depth (base-255 so each byte round-trips
// an 8-bit UNORM target exactly); surfacePoint unpacks it in JS.
vec4 packDepth() {
    float zc = clamp(gl_FragCoord.z, 0.0, 1.0);
    float r8 = floor(zc * 255.0);
    float rem1 = zc * 255.0 - r8;
    float g8 = floor(rem1 * 255.0);
    float b8 = floor((rem1 * 255.0 - g8) * 255.0);
    return vec4(r8 / 255.0, g8 / 255.0, b8 / 255.0, 1.0);
}
void main() {
    float s = clamp(vShade, 0.0, 255.0);
    if (textured) {
        vec4 t = texture(map, vec2(vUv.x, vUv.y + vScroll));
        if (t.a < 0.5) discard;
        if (pickMode == 1) { outColor = vPickId; outDepth = packDepth(); return; }
        float bankIdx = mod(floor(s / 16.0), 4.0);
        float bank = bankIdx < 0.5 ? 1.0 : bankIdx < 1.5 ? 0.875 : bankIdx < 2.5 ? 0.75 : 0.625;
        float f = bank / pow(2.0, floor(s / 64.0));
        outColor = vec4(t.rgb * f, 1.0);
    } else {
        if (pickMode == 1) { outColor = vPickId; outDepth = packDepth(); return; }
        float r = 255.0 - s;
        outColor = vec4(vBase * (r * r / 65536.0), 1.0);
    }
}`;

// The GPU pick's packed id, under 2^28 so it round-trips an RGBA8 target: z in bits 0-11, x in
// 12-23, a door's edge in 24-25, bit 26 for a door, and this marker, which lands the alpha byte in
// 8..15, apart from terrain's 255 and the sky's 0.
const ID_MARK = 1 << 27;
const packSceneryId = (x: number, z: number): number =>
    x >= 0 && x < 4096 && z >= 0 && z < 4096 ? ID_MARK | (x << 12) | z : 0;
const packDoorId = (x: number, z: number, dir: number): number =>
    x >= 0 && x < 4096 && z >= 0 && z < 4096 ? ID_MARK | (1 << 26) | ((dir & 3) << 24) | (x << 12) | z : 0;

// The entity `follow` names, case-insensitively as the server's names compare; a bot wins over a
// player of the same name.
function followedEntity<T extends {kind: string; name?: string | null}>(
    cards: T[],
    follow: string | null | undefined,
): T | null {
    if (!follow) return null;
    const lc = follow.toLowerCase();
    let player: T | null = null;
    for (const c of cards) {
        if (c.name?.toLowerCase() !== lc) continue;
        if (c.kind === "bot") return c;
        if (c.kind === "player" && player == null) player = c;
    }
    return player;
}

// One baked cell file as geometries per texture, x mirrored and y up. A terrain cell feeds
// `heightSink` its corner heights, from the unflattened grid where it has one, so things stand on a
// bridge deck rather than the water the mesh levels under it.
function parseCell(
    buf: ArrayBuffer,
    worldWidthUnits: number,
    botX0: number,
    botZ0: number,
    heightSink?: (botCornerX: number, botCornerZ: number, y: number) => void,
    splitSink?: (botTileX: number, botTileZ: number, main: boolean) => void,
): {tex: number; geometry: THREE.BufferGeometry}[] {
    const dv = new DataView(buf);
    if (dv.getUint32(0) !== 0x52534333) throw new Error("bad cell magic");
    const version = dv.getUint16(4);
    const kind = dv.getUint8(7);
    const originX = botX0 * 128;
    const originZ = botZ0 * 128;
    const groups = dv.getUint16(16);
    let off = 18;
    const out: {tex: number; geometry: THREE.BufferGeometry}[] = [];
    for (let g = 0; g < groups; g++) {
        const tex = dv.getInt16(off);
        const n = dv.getInt32(off + 2);
        off += 6;
        const pos = new Float32Array(n * 3);
        const raw = splitSink && kind === 0 ? new Int16Array(n * 2) : null;
        for (let i = 0; i < n; i++) {
            const rawX = dv.getInt16(off);
            const x = rawX + originX;
            const y = dv.getInt16(off + 2);
            const rawZ = dv.getInt16(off + 4);
            const z = rawZ + originZ;
            off += 6;
            if (raw) {
                raw[i * 2] = rawX;
                raw[i * 2 + 1] = rawZ;
            }
            if (heightSink && rawX % 128 === 0 && rawZ % 128 === 0) {
                heightSink(botX0 + rawX / 128, botZ0 + rawZ / 128, -y);
            }
            pos[i * 3] = worldWidthUnits - x;
            pos[i * 3 + 1] = -y;
            pos[i * 3 + 2] = z;
        }
        if (raw && splitSink) tileSplits(raw, n, botX0, botZ0, splitSink);
        const shade = new Uint8Array(buf, off, n);
        off += n;
        const base = new Uint8Array(buf, off, n * 3);
        off += n * 3;
        let uvArr: Float32Array | null = null;
        if (tex >= 0) {
            uvArr = new Float32Array(n * 2);
            for (let i = 0; i < n * 2; i++) {
                uvArr[i] = dv.getInt16(off) / 512;
                off += 2;
            }
        }
        const geo = new THREE.BufferGeometry();
        geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
        geo.setAttribute("shade", new THREE.BufferAttribute(new Float32Array(shade), 1));
        geo.setAttribute("base", new THREE.BufferAttribute(new Uint8Array(base), 3, true));
        if (uvArr) geo.setAttribute("uv", new THREE.BufferAttribute(uvArr, 2));
        out.push({tex, geometry: geo});
    }
    if (heightSink && version >= 2 && kind === 0) {
        const dim = dv.getUint16(off);
        off += 2;
        for (let i = 0; i < dim; i++) {
            for (let j = 0; j < dim; j++) {
                heightSink(botX0 + i, botZ0 + j, dv.getUint8(off++) * 3);
            }
        }
    }
    return out;
}

// Which diagonal each ground tile's triangles share: the client splits along the anti-diagonal
// unless a decoration's border runs the other way, and draped geometry must split alike.
function tileSplits(
    raw: Int16Array,
    n: number,
    botX0: number,
    botZ0: number,
    sink: (botTileX: number, botTileZ: number, main: boolean) => void,
): void {
    for (let t = 0; t + 2 < n; t += 3) {
        let minX = Infinity;
        let minZ = Infinity;
        let maxX = -Infinity;
        let maxZ = -Infinity;
        let onCorners = true;
        for (let k = 0; k < 3; k++) {
            const rx = raw[(t + k) * 2];
            const rz = raw[(t + k) * 2 + 1];
            if (rx % 128 !== 0 || rz % 128 !== 0) {
                onCorners = false;
                break;
            }
            minX = Math.min(minX, rx);
            maxX = Math.max(maxX, rx);
            minZ = Math.min(minZ, rz);
            maxZ = Math.max(maxZ, rz);
        }
        if (!onCorners || maxX - minX !== 128 || maxZ - minZ !== 128) continue;
        let c00 = false;
        let c11 = false;
        let c10 = false;
        let c01 = false;
        for (let k = 0; k < 3; k++) {
            const hx = raw[(t + k) * 2] !== minX;
            const hz = raw[(t + k) * 2 + 1] !== minZ;
            if (!hx && !hz) c00 = true;
            else if (hx && hz) c11 = true;
            else if (hx) c10 = true;
            else c01 = true;
        }
        const tx = botX0 + minX / 128;
        const tz = botZ0 + minZ / 128;
        if (c00 && c11) sink(tx, tz, true);
        else if (c10 && c01) sink(tx, tz, false);
    }
}

/** One-shot commands to a {@link World3DView}, through its ref. */
export interface World3DHandle {
    /**
     * Swings the camera to a tile, z absolute. Another floor is requested through
     * `onFloorIndexChange`, which the host may refuse.
     */
    panTo(at: {x: number; z: number; floor: number}): void;
    /**
     * Marks the next entity update as a jump in time, so it is placed rather than glided: entering,
     * seeking or leaving a replay. Latched until an update arrives.
     */
    snapNext(): void;
    /** Starts or stops the flyby; `onFlyingChange` reports the result. */
    toggleFly(): void;
}

/** Everything a {@link World3DView} is told. Tiles are absolute, the floor folded into z, unless noted. */
export interface World3DViewProps {
    focus?: {x: number; z: number} | null;
    /** One entry per vantage point, merged into one world and glided between ticks. */
    observers?: Observer[];
    /** Dead NPCs, drawn as ghosts at their spawns with a countdown. */
    npcRespawns?: NpcRespawn[];
    /** Depleted scenery, each with a countdown tag. */
    objectRespawns?: ObjectRespawn[];
    selectedBot?: string | null;
    onSelectBot?: (username: string | null) => void;
    /** The floor shown, when the host controls it; unset, the view keeps its own, seeded from `?floor=`. */
    floorIndex?: number;
    onFloorIndexChange?: (floor: number) => void;
    /**
     * The toolbar toggles, when the host controls them; unset ones stay the view's own. `below` draws
     * the floors beneath floor 1 or 2 as well.
     */
    toggles?: {
        roofs: boolean;
        sight: boolean;
        tags: boolean;
        clusters?: boolean;
        components?: boolean;
        below?: boolean;
    };
    onTogglesChange?: (o: {
        roofs: boolean;
        sight: boolean;
        tags: boolean;
        clusters: boolean;
        components: boolean;
        below: boolean;
    }) => void;
    /**
     * The name the camera follows, matched against live entities every frame, since a server index is
     * reused after a relog. Nobody in view by that name holds the camera where it is until they are seen.
     */
    follow?: string | null;
    /** Set, the camera holds this tile and its floor instead of following `follow`, framed alike. */
    followTile?: {x: number; z: number} | null;
    /**
     * Set, a stock right-click menu stands open on this tile as if clicked there, `scale` times its size.
     * It shows these lines under its title and takes no clicks.
     */
    menuAt?: {x: number; z: number; lines: readonly MenuLine[]; scale: number} | null;
    /** The player whose view circle is drawn in red, beside the bots' own, while in view. */
    watchedSight?: string | null;
    /**
     * With `follow`, the names the camera also keeps in view. It eases to fit them around the followed
     * entity, zooming between `minTiles` and `maxTiles` of view height; the shot's centre goes `toward`
     * (0 to 1, default 0.5) of the way from the followed entity to the group's middle. A name nobody in
     * view answers to, or further than `reachTiles` from the followed entity, is left out. Given `easeS`,
     * the pan and the zoom both ease with that time constant in seconds, so they move together. `rate`
     * (default 1) is how many seconds the page's clock runs per second shown, as when it is rendered off
     * a faster clock: the eases are seconds shown either way.
     */
    framing?: {
        also: readonly string[];
        minTiles: number;
        maxTiles: number;
        toward?: number;
        easeS?: number;
        reachTiles?: number;
        rate?: number;
    } | null;
    /** Which entities get a nameplate while tags are on; omitted, every one. */
    nameplates?: (e: {kind: string; name?: string | null}) => boolean;
    /** Given, a pan lets go of the followed entity and calls this; without it a drag only rotates. */
    onFollowBreak?: () => void;
    route?: RoutePoint[] | null;
    /** The planner's own route, drawn dotted beside `route`. */
    plan?: RoutePoint[] | null;
    /** How long one tick is drawn over, in ms; 640 unless the host plays faster. */
    tickMs?: number;
    /** Walking components to show; while set, the components layer shows and draws only these. */
    reachableComponents?: ReadonlySet<number> | null;
    trail?: TrailPoint[] | null;
    /** True while `observers` carry a reconstructed past instant: entities are placed, not glided. */
    scrubbing?: boolean;
    ref?: Ref<World3DHandle>;
    /** Armed, a left drag sweeps a ground rectangle instead of panning. */
    areaTool?: boolean;
    /**
     * The committed rectangle, floor-local and inclusive, controlled: only a null clears it. A sweep in
     * progress is the view's own until release, when this prop takes over again.
     */
    areaRect?: {x0: number; z0: number; x1: number; z1: number} | null;
    /** A new rectangle: at the end of a sweep, and at every tile a resize handle crosses. */
    onAreaRectChange?: (box: {x0: number; z0: number; x1: number; z1: number}) => void;
    /**
     * Every click on a player, NPC, scenery or boundary object; null for a click on nothing. The view draws no card.
     */
    onPickEntity: (pick: PickedEntity | null) => void;
    /** Decoration from the host: stable implementations with this render's data, keyed by id. */
    overlays?: readonly OverlayEntry[];
    /** Everything the observers see, deduplicated; omitted, only the observers and the spawn ghosts show. */
    world?: WorldEntities;
    /**
     * Which classes of thing are drawn; all on by default but danger zones. An off class of entity
     * is not even assembled.
     */
    layers?: {
        bots?: boolean;
        npcs?: boolean;
        players?: boolean;
        npcSpawns?: boolean;
        transports?: boolean;
        shops?: boolean;
        wilderness?: boolean;
        dangerZones?: boolean;
    };
    /**
     * The floor's transports, floor-local. The anchor object, or the wall it crosses, is outlined;
     * hovering it lights the entry tiles and raises a label of where each goes (`dx`, `dz` floor-local
     * on `dfloor`). `label` is what a map prints, falling back to `name`; `perEntry` lands each entry on
     * its own tile; a boundary's `dir` is -1 when unknown. Null while the host loads them: a floor
     * switch waits for them, up to 1.5 s.
     */
    transports?:
        | {
              name: string;
              label?: string | null;
              req?: string | null;
              perEntry?: boolean;
              anchor: {x: number; z: number} | null;
              boundary?: {x: number; z: number; dir: number} | null;
              entries: {x: number; z: number}[];
              dx: number;
              dz: number;
              dfloor: number;
          }[]
        | null;
    /** Shops by keeper NPC: a keeper's nameplate gets a glyph whose click reports the shop's id. */
    shops?: {id: string; name: string; x: number; z: number; npcIds: number[]}[];
    onSelectShop?: (id: string) => void;
    /** Whether a click marks an entity of this kind selected in the view; default every kind. */
    selectOnClick?: (kind: string) => boolean;
    /**
     * Clicks command the selected bot: a left click runs the top menu entry by the client's priority,
     * a right click opens the menu of every action under the cursor.
     */
    walkTool?: boolean;
    /** A left click on the ground reports its tile through `onTilePick`; entity and label clicks keep their meaning. */
    pickTool?: boolean;
    onTilePick?: (tile: {x: number; z: number; floor: number}) => void;
    /** The tile a `pickTool` or `walkTool` click would land on, each time the cursor enters a new one. */
    onTileHover?: (tile: {x: number; z: number; floor: number}) => void;
    /** Given, the walk tool's "Walk here" reports the tile instead of walking the selected bot. */
    onWalkHere?: (tile: {x: number; z: number; floor: number}) => void;
    /** An armed "Use item with": the next world click uses it on what it lands on. */
    useItem?: {id: number; name: string} | null;
    /** The use was sent, or a walk cancelled it. */
    onUseItemDone?: () => void;
    onFlyingChange?: (flying: boolean) => void;
    /** The cells still to fetch or build, whenever that count changes; 0 once everything queued is in. */
    onStreamPending?: (cells: number) => void;
    /** False while hidden: the view stays mounted, keeping its GPU state, but stops rendering. Default true. */
    visible?: boolean;
    /** A camera box shared with another view: written on pan, read once when this view is revealed. */
    sharedView?: {current: {x: number; z: number; zoom: number} | null};
    /** Shows the in-view toolbar; default true. */
    toolbar?: boolean;
    extraToggles?: ReactNode;
    togglesAfterRoofs?: ReactNode;
    /** Controls placed after "tags", before the pathfinder's layers. */
    togglesAfterTags?: ReactNode;
    /** Hides the "sight" checkbox. */
    hideSight?: boolean;
}

/**
 * The 3D world: baked mesh cells around the camera, shaded as the client's software rasterizer
 * shades them, with scenery and doors assembled live, entities, overlays and a toolbar. Map
 * orientation: east is +x, north -z, y up, one unit an engine unit.
 */
export function World3DView(props: World3DViewProps) {
    const hostRef = useRef<HTMLDivElement | null>(null);
    const [localFloorIndex, setLocalFloorIndex] = useState<number>(() =>
        floorIndexForKey(new URLSearchParams(location.search).get("floor")),
    );
    const toolbar = props.toolbar ?? true;
    // Standalone the view keeps its own floor, seeded from `?floor=`; embedded, the host owns it.
    const floorIndex = props.floorIndex ?? localFloorIndex;
    const floor: FloorKey = floorKeyForIndex(floorIndex);
    // The floor drawn, which trails the one asked for until its cells in view are in, so the switch is one frame.
    const [shownFloorIndex, setShownFloorIndex] = useState(floorIndex);
    const shownFloor: FloorKey = floorKeyForIndex(shownFloorIndex);
    // Embedded this is a request the host may refuse: read floorIndex on the next render.
    const setFloorIndex = (i: number) => {
        const next = Math.max(0, Math.min(3, Math.trunc(i)));
        if (props.onFloorIndexChange) props.onFloorIndexChange(next);
        else setLocalFloorIndex(next);
    };
    const [roofsState, setRoofsState] = useState(() => new URLSearchParams(location.search).get("roofs") === "1");
    const [sightState, setSightState] = useState(() => new URLSearchParams(location.search).get("sight") !== "0");
    const [tagsState, setTagsState] = useState(() => new URLSearchParams(location.search).get("tags") === "1");
    const [clustersState, setClustersState] = useState(
        () => new URLSearchParams(location.search).get("clusters") === "1",
    );
    const [componentsState, setComponentsState] = useState(
        () => new URLSearchParams(location.search).get("components") === "1",
    );
    const [belowState, setBelowState] = useState(() => new URLSearchParams(location.search).get("below") === "1");
    const roofs = props.toggles?.roofs ?? roofsState;
    const sight = props.toggles?.sight ?? sightState;
    const tags = props.toggles?.tags ?? tagsState;
    const clusters = props.toggles?.clusters ?? clustersState;
    const components = props.toggles?.components ?? componentsState;
    const below = props.toggles?.below ?? belowState;
    const all = {roofs, sight, tags, clusters, components, below};
    const setRoofs = (v: boolean) =>
        props.onTogglesChange ? props.onTogglesChange({...all, roofs: v}) : setRoofsState(v);
    const setSight = (v: boolean) =>
        props.onTogglesChange ? props.onTogglesChange({...all, sight: v}) : setSightState(v);
    const setTags = (v: boolean) =>
        props.onTogglesChange ? props.onTogglesChange({...all, tags: v}) : setTagsState(v);
    const setClusters = (v: boolean) =>
        props.onTogglesChange && props.toggles?.clusters !== undefined
            ? props.onTogglesChange({...all, clusters: v})
            : setClustersState(v);
    const setComponents = (v: boolean) =>
        props.onTogglesChange && props.toggles?.components !== undefined
            ? props.onTogglesChange({...all, components: v})
            : setComponentsState(v);
    const setBelow = (v: boolean) =>
        props.onTogglesChange && props.toggles?.below !== undefined
            ? props.onTogglesChange({...all, below: v})
            : setBelowState(v);
    const [status, setStatus] = useState("loading manifest…");
    const [hover, setHover] = useState<string>("");
    // The static spawn table, fetched until it loads: every spawn nobody sees is drawn as a ghost.
    const [npcSpawns, setNpcSpawns] = useState<NpcSpawnInfo[]>([]);
    useEffect(() => {
        let stop = false;
        let retry: ReturnType<typeof setTimeout> | undefined;
        const load = () =>
            fetchNpcSpawns()
                .then((s) => {
                    if (!stop) setNpcSpawns(s);
                })
                .catch(() => {
                    if (!stop) retry = setTimeout(() => void load(), 5000);
                });
        void load();
        return () => {
            stop = true;
            clearTimeout(retry);
        };
    }, []);
    const [flying, setFlying] = useState(false);
    const flightRef = useRef<{start: number} | null>(null);
    // Set by snapNext, cleared by the first entity update after it.
    const snapNextRef = useRef(false);
    const captureFps = readCaptureFps();
    // Recording needs a user gesture for its folder picker, so the Fly button starts it through this.
    const startCaptureRef = useRef<((size: {w: number; h: number} | null) => Promise<void>) | null>(null);
    // The recording size, from `?size=WxH`; blank records at the canvas size.
    const [captureRes, setCaptureRes] = useState(() => new URLSearchParams(location.search).get("size") ?? "");
    // Per observer, the last server tick whose projectiles were launched: an observer stays in the list
    // until a newer one replaces it, and must not launch its shots again on every render.
    const projTickRef = useRef(new Map<string, number>());
    const assembledFromRef = useRef<unknown[] | null>(null);
    const projSeqRef = useRef(0);
    const projFlightsRef = useRef<ProjectileFlight[]>([]);
    // The walk tool's option menu: each entry's verb, its target in the client's colour, and what it runs.
    const [ctxMenu, setCtxMenu] = useState<{
        x: number;
        y: number;
        entries: {
            verb: string;
            target?: string;
            tcolor?: string;
            suffix?: string;
            scolor?: string;
            prio: number;
            run?: () => void;
        }[];
    } | null>(null);
    const ctxMenuOpenRef = useRef(false);
    ctxMenuOpenRef.current = ctxMenu != null;
    const adoptUrlRef = useRef<(() => void) | null>(null);
    useEffect(() => {
        props.onFlyingChange?.(flying);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [flying]);
    const toggleFly = () => {
        if (flightRef.current) {
            flightRef.current = null;
            setFlying(false);
        } else {
            flightRef.current = {start: performance.now()};
            setFlying(true);
        }
    };
    useEffect(() => {
        // Keeps the floor on the followed entity's, read from the raw props: assembly drops everything off the
        // shown floor, including the entity that would say to switch.
        if (props.followTile) {
            const f = Math.floor(props.followTile.z / 944);
            if (f !== floorIndex) setFloorIndex(f);
            return;
        }
        const name = props.follow;
        if (!name) return;
        const lc = name.toLowerCase();
        let f = (props.observers ?? []).find((x) => x.username.toLowerCase() === lc)?.position?.floor;
        if (f == null) {
            const pl = (props.world?.players ?? []).find((p) => p.name?.toLowerCase() === lc);
            if (pl) f = Math.floor(pl.z / 944);
        }
        if (f != null && f !== floorIndex) setFloorIndex(f);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.follow, props.followTile, props.observers, props.world]);
    // No dependency list: the commands close over this render's floor and setters.
    useImperativeHandle(props.ref, () => ({
        panTo(at) {
            const f = Math.max(0, Math.min(3, at.floor));
            if (f !== floorIndex) setFloorIndex(f);
            panToRef.current?.(at.x, at.z - f * 944);
        },
        snapNext() {
            snapNextRef.current = true;
        },
        toggleFly,
    }));
    useEffect(() => {
        if (props.visible === false) return;
        // On reveal, adopt the shared view once the container has its size back.
        const t = setTimeout(() => adoptUrlRef.current?.(), 60);
        return () => clearTimeout(t);
    }, [props.visible]);
    const stateRef = useRef<{
        manifest: WorldMeshManifest | null;
        floor: FloorKey;
        wantFloor: FloorKey;
        roofs: boolean;
        sight: boolean;
        tags: boolean;
        clusters: boolean;
        components: boolean;
        below: boolean;
        focusBot: {x: number; z: number};
        entities: Entity3D[];
        entitiesRev: number;
        groundItems: GroundItem3D[];
        observed: {plane: number; x: number; z: number; id: number}[];
        observedDoors: {plane: number; x: number; z: number; dir: number; id: number}[];
        observedRev: number;
        respawnGhosts: {plane: number; x: number; z: number; id: number; t: number}[];
        route: RoutePoint[] | null;
        plan: RoutePoint[] | null;
        reachable: ReadonlySet<number> | null;
        trail: TrailPoint[] | null;
    }>({
        manifest: null,
        floor: "ground",
        wantFloor: "ground",
        roofs: false,
        sight: true,
        tags: true,
        clusters: false,
        components: false,
        below: false,
        focusBot: props.focus ?? {x: 120, z: 640},
        entities: [],
        entitiesRev: 0,
        groundItems: [],
        observed: [],
        observedDoors: [],
        observedRev: 0,
        respawnGhosts: [],
        route: null,
        plan: null,
        reachable: null,
        trail: null,
    });

    // The scene effect runs once, so it reads props, the floor setter and the toggles through refs.
    const propsRef = useRef(props);
    propsRef.current = props;
    const panToRef = useRef<((x: number, zLocal: number) => void) | null>(null);
    const compassRef = useRef<HTMLDivElement | null>(null);
    const homeViewRef = useRef<(() => void) | null>(null);
    const setFloorIndexRef = useRef(setFloorIndex);
    setFloorIndexRef.current = setFloorIndex;
    stateRef.current.floor = shownFloor;
    stateRef.current.wantFloor = floor;
    stateRef.current.roofs = roofs;
    stateRef.current.sight = sight;
    stateRef.current.tags = tags;
    stateRef.current.clusters = clusters;
    stateRef.current.components = components;
    stateRef.current.below = below;
    stateRef.current.route = props.route ?? null;
    stateRef.current.plan = props.plan ?? null;
    stateRef.current.reachable = props.reachableComponents ?? null;
    stateRef.current.trail = props.trail ?? null;
    if (props.focus) stateRef.current.focusBot = props.focus;

    // The entity set for the shown floor, assembled only while visible and only when an input changed:
    // the view re-renders for every hover and label its host hands it.
    const assemblyInputs = [
        shownFloor,
        props.layers?.bots,
        props.layers?.npcs,
        props.layers?.players,
        props.layers?.npcSpawns,
        props.layers?.shops,
        props.world,
        props.shops,
        props.observers,
        props.selectedBot,
        props.npcRespawns,
        props.objectRespawns,
        npcSpawns,
    ];
    const assembledFrom = assembledFromRef.current;
    const assemblyChanged = assembledFrom === null || assemblyInputs.some((v, i) => !Object.is(v, assembledFrom[i]));
    if (props.visible !== false && assemblyChanged) {
        assembledFromRef.current = assemblyInputs;
        const plane = kindsFor(shownFloor).plane;
        const out: Entity3D[] = [];
        const seenNpc = new Set<number>();
        const showBots = props.layers?.bots !== false;
        const showNpcs = props.layers?.npcs !== false;
        const showPlayers = props.layers?.players !== false;
        const showSpawns = props.layers?.npcSpawns !== false;
        const pooled = props.world;
        const shopByNpc = new Map<number, string>();
        if (props.layers?.shops !== false) {
            for (const sh of props.shops ?? []) {
                for (const nid of sh.npcIds) shopByNpc.set(nid, sh.id);
            }
        }
        const ownIndexes = new Set<number>();
        for (const b of props.observers ?? []) {
            const offline = b.offline === true;
            // Observers are in the pooled players too and must not draw twice; an offline observer's old index
            // may belong to someone else by now.
            if (!offline && b.serverIndex != null) ownIndexes.add(b.serverIndex);
            const pos = b.position;
            if (showBots && pos?.floor === plane) {
                out.push({
                    key: `bot:${b.username}`,
                    kind: "bot",
                    x: pos.x,
                    z: pos.z - pos.floor * 944,
                    name: b.username,
                    selected: b.username === props.selectedBot,
                    inCombat: b.inCombat ?? false,
                    appearance: b.appearance,
                    skulled: b.skulled,
                    offline,
                    dir: b.dir,
                    hp: b.hits,
                    maxHp: b.maxHits,
                    dmg: b.dmg,
                    dmgTick: b.dmg != null ? b.serverTick : null,
                    msg: b.msg,
                    msgTick: b.msgTick,
                    bubble: b.bubble,
                    bubbleTick: b.bubbleTick,
                    sleeping: b.sleeping,
                    snapSeq: b.snapSeq,
                    ringColor: b.ringColor,
                    ringColorFirst: b.ringColorFirst,
                });
            }
        }
        if (pooled) {
            for (const n of showNpcs ? pooled.npcs : []) {
                if (Math.floor(n.z / 944) !== plane) continue;
                seenNpc.add(n.serverIndex);
                out.push({
                    key: `npc:${n.serverIndex}`,
                    kind: "npc",
                    x: n.x,
                    z: n.z % 944,
                    name: n.name,
                    inCombat: n.inCombat,
                    npcId: n.id,
                    dir: n.dir,
                    hp: n.hp,
                    maxHp: n.maxHp,
                    dmg: n.dmg,
                    dmgTick: n.dmgTick,
                    msg: n.msg,
                    msgTick: n.msgTick,
                    shopId: shopByNpc.get(n.id),
                    ringColor: n.ringColor,
                    ringColorFirst: n.ringColorFirst,
                    tagColor: n.tagColor,
                    tagAlways: n.tagAlways,
                    tagOutline: n.tagOutline,
                    tagStrong: n.tagStrong,
                });
            }
            for (const pl of showPlayers ? pooled.players : []) {
                if (Math.floor(pl.z / 944) !== plane || ownIndexes.has(pl.serverIndex)) continue;
                out.push({
                    key: `pl:${pl.serverIndex}`,
                    kind: "player",
                    x: pl.x,
                    z: pl.z % 944,
                    name: pl.name,
                    inCombat: pl.inCombat,
                    appearance: pl.appearance,
                    dir: pl.dir,
                    combatLvl: pl.combatLvl,
                    skulled: pl.skulled,
                    hp: pl.hp,
                    maxHp: pl.maxHp,
                    dmg: pl.dmg,
                    dmgTick: pl.dmgTick,
                    msg: pl.msg,
                    msgTick: pl.msgTick,
                    bubble: pl.bubble,
                    bubbleTick: pl.bubbleTick,
                    ringColor: pl.ringColor,
                    ringColorFirst: pl.ringColorFirst,
                    tagColor: pl.tagColor,
                    tagAlways: pl.tagAlways,
                    tagOutline: pl.tagOutline,
                    tagStrong: pl.tagStrong,
                });
            }
        }
        const pendingByIdx = new Map<number, NpcRespawn>();
        for (const g of showSpawns ? (props.npcRespawns ?? []) : []) {
            pendingByIdx.set(g.serverIndex, g);
        }
        // A spawn whose NPC nobody sees is a ghost, with its respawn window where one is known.
        for (const s of showSpawns ? npcSpawns : []) {
            if (Math.floor(s.z / 944) !== plane || seenNpc.has(s.serverIndex)) continue;
            const pend = pendingByIdx.get(s.serverIndex);
            out.push({
                key: `npc:${s.serverIndex}`,
                kind: "npc",
                x: s.x,
                z: s.z % 944,
                name: s.name,
                inCombat: false,
                npcId: s.id,
                ghost: true,
                shopId: shopByNpc.get(s.id),
                respawnTicks: pend?.t ?? null,
                respawnTicksMin: pend?.tMin ?? null,
                respawnAssumed: pend?.assumed ?? null,
                respawnCheckedAgo: pend?.checkedAgo ?? null,
            });
        }
        stateRef.current.entities = out;
        stateRef.current.entitiesRev++;

        const ground: GroundItem3D[] = [];
        for (const g of pooled?.groundItems ?? []) {
            if (Math.floor(g.z / 944) !== plane) continue;
            ground.push({id: g.id, x: g.x, z: g.z % 944, name: g.name});
        }
        stateRef.current.groundItems = ground;

        const obs: {plane: number; x: number; z: number; id: number}[] = [];
        for (const o of pooled?.objects ?? []) {
            obs.push({plane: Math.floor(o.z / 944), x: o.x, z: o.z % 944, id: o.id});
        }
        stateRef.current.observed = obs;

        const obsDoors: {plane: number; x: number; z: number; dir: number; id: number}[] = [];
        for (const w of pooled?.wallObjects ?? []) {
            if (w.dir == null) continue;
            obsDoors.push({plane: Math.floor(w.z / 944), x: w.x, z: w.z % 944, dir: w.dir, id: w.id});
        }
        stateRef.current.observedDoors = obsDoors;
        stateRef.current.observedRev++;

        const rGhosts: {plane: number; x: number; z: number; id: number; t: number}[] = [];
        const seenRg = new Set<string>();
        for (const o of props.objectRespawns ?? []) {
            const op = Math.floor(o.z / 944);
            const k = `${op}:${o.x},${o.z % 944}`;
            if (seenRg.has(k)) continue;
            seenRg.add(k);
            rGhosts.push({plane: op, x: o.x, z: o.z % 944, id: o.id, t: o.t});
        }
        stateRef.current.respawnGhosts = rGhosts;

        {
            const ownIdx = new Map<number, string>();
            for (const b of props.observers ?? []) {
                if (b.serverIndex != null) ownIdx.set(b.serverIndex, b.username);
            }
            const byKey = new Map<string, Entity3D>();
            for (const e of out) byKey.set(e.key, e);
            const now = performance.now();
            // One flight per shot, however many observers saw it.
            const fired = new Set<string>();
            for (const b of props.observers ?? []) {
                if (b.serverTick == null) continue;
                if (projTickRef.current.get(b.username) === b.serverTick) continue;
                projTickRef.current.set(b.username, b.serverTick);
                for (const p of b.projectiles ?? []) {
                    const k = `${p.sprite}:${p.fromNpc ? "n" : "p"}${p.from}:` + `${p.toPlayer ? "p" : "n"}${p.to}`;
                    if (fired.has(k)) continue;
                    fired.add(k);
                    const fromKey = p.fromNpc
                        ? `npc:${p.from}`
                        : ownIdx.has(p.from)
                          ? `bot:${ownIdx.get(p.from)}`
                          : `pl:${p.from}`;
                    const toKey = !p.toPlayer
                        ? `npc:${p.to}`
                        : ownIdx.has(p.to)
                          ? `bot:${ownIdx.get(p.to)}`
                          : `pl:${p.to}`;
                    const fe = byKey.get(fromKey);
                    const te = byKey.get(toKey);
                    if (!fe || !te) continue;
                    projFlightsRef.current.push({
                        key: `f${projSeqRef.current++}`,
                        sprite: p.sprite,
                        fromKey,
                        toKey,
                        start: now,
                        fx: fe.x,
                        fz: fe.z,
                        tx: te.x,
                        tz: te.z,
                    });
                }
            }
            projFlightsRef.current = projFlightsRef.current.filter((f) => now - f.start < PROJECTILE_FLIGHT_MS + 200);
        }
    }

    useEffect(() => {
        const host = hostRef.current;
        if (!host) return;
        // Every listener this effect adds is removed with it; the host outlives the effect.
        const listening = new AbortController();

        let capturing = false;
        let captureSize: {w: number; h: number} | null = null;

        const renderer = new THREE.WebGLRenderer({
            antialias: true,
            // Frame grabs read the drawing buffer after the draw; only paid for while recording.
            preserveDrawingBuffer: captureFps > 0,
        });
        renderer.setPixelRatio(window.devicePixelRatio);
        host.appendChild(renderer.domElement);
        const scene = new THREE.Scene();
        scene.background = new THREE.Color(0x10101a);
        // The loop updates world matrices once a frame itself, rather than three renders walking the whole
        // scene; the root sits at identity, so it never needs recomposing either.
        scene.matrixWorldAutoUpdate = false;
        scene.matrixAutoUpdate = false;

        const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, -400000, 400000);
        const compass = new CompassGizmo();
        compassRef.current?.appendChild(compass.canvas);

        const target = new THREE.Vector3();
        const q0 = new URLSearchParams(location.search);
        const num = (v: string | null): number | null => {
            if (v == null) return null;
            const n = parseFloat(v);
            return Number.isFinite(n) ? n : null;
        };
        let yaw = ((num(q0.get("yaw")) ?? 45) * Math.PI) / 180;
        let pitch = ((num(q0.get("pitch")) ?? HOME_PITCH_DEG) * Math.PI) / 180;
        const PITCH_MIN = 0.02;
        const PITCH_MAX = 1.5533;
        pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, pitch));
        const DIST = 200000;

        let worldMaxUnits = 1024 * 128;
        // The URL follows the camera only once it has been still a moment, and never the same URL twice:
        // in some browsers every replaceState costs a history query on the main thread.
        const URL_SETTLE_MS = 500;
        let urlTimer: ReturnType<typeof setTimeout> | null = null;
        let cameraMovedAt = 0;
        let urlWritten = `${location.pathname}${location.search}`;
        const syncUrl = () => {
            if (urlTimer) return;
            urlTimer = setTimeout(function write() {
                const still = performance.now() - cameraMovedAt;
                if (still < URL_SETTLE_MS) {
                    urlTimer = setTimeout(write, URL_SETTLE_MS - still);
                    return;
                }
                urlTimer = null;
                const manifest = stateRef.current.manifest;
                if (!manifest) return;
                const worldWidthUnits = manifest.botXTiles * 128;
                const p = new URLSearchParams(location.search);
                p.set("floor", stateRef.current.floor);
                p.set("roofs", stateRef.current.roofs ? "1" : "0");
                p.set("sight", stateRef.current.sight ? "1" : "0");
                p.set("tags", stateRef.current.tags ? "1" : "0");
                if (stateRef.current.clusters) p.set("clusters", "1");
                else p.delete("clusters");
                if (stateRef.current.components) p.set("components", "1");
                else p.delete("components");
                if (stateRef.current.below) p.set("below", "1");
                else p.delete("below");
                p.set("x", ((worldWidthUnits - target.x) / 128).toFixed(1));
                p.set("z", (target.z / 128).toFixed(1));
                p.set("zoom", (viewHeightUnits / 128).toFixed(1));
                p.set("yaw", ((yaw * 180) / Math.PI).toFixed(1));
                p.set("pitch", ((pitch * 180) / Math.PI).toFixed(1));
                const url = `${location.pathname}?${p}`;
                if (url === urlWritten) return;
                urlWritten = url;
                history.replaceState(null, "", url);
            }, URL_SETTLE_MS);
        };

        const applyCamera = () => {
            cameraMovedAt = performance.now();
            const MARGIN = 40 * 128;
            target.x = Math.max(-MARGIN, Math.min(worldMaxUnits + MARGIN, target.x));
            target.y = Math.max(-3000, Math.min(3000, target.y));
            target.z = Math.max(-MARGIN, Math.min(worldMaxUnits + MARGIN, target.z));
            const cp = Math.cos(pitch);
            camera.position.set(
                target.x + DIST * cp * Math.sin(yaw),
                target.y + DIST * Math.sin(pitch),
                target.z + DIST * cp * Math.cos(yaw),
            );
            camera.up.set(0, 1, 0);
            camera.lookAt(target);
            compass.setView(yaw, pitch);
            const sv = propsRef.current.sharedView;
            const mf = stateRef.current.manifest;
            if (sv && mf) {
                sv.current = {
                    x: (mf.botXTiles * 128 - target.x) / 128,
                    z: target.z / 128,
                    zoom: viewHeightUnits / 128,
                };
            }
            syncUrl();
            window.__w3d = {
                target: {x: target.x, y: target.y, z: target.z},
                yaw,
                pitch,
                viewHeightUnits,
            };
        };

        // The camera's right and screen-up vectors in world space; panning and zooming along them is exact
        // in an orthographic view at any pitch.
        const screenBasis = () => {
            const sy = Math.sin(yaw);
            const cy = Math.cos(yaw);
            const sp = Math.sin(pitch);
            const cp = Math.cos(pitch);
            return {
                right: new THREE.Vector3(cy, 0, -sy),
                up: new THREE.Vector3(-sp * sy, cp, -sp * cy),
            };
        };

        // Slides the target along the view axis back to y 0, invisible in an orthographic view, so the
        // target's vertical clamp never eats a pan. Skipped at grazing pitch, where it would shove the
        // target far off.
        const regroundTarget = () => {
            const sp = Math.sin(pitch);
            if (sp < 0.15 || target.y === 0) return;
            const t = target.y / sp;
            const cp = Math.cos(pitch);
            target.x -= cp * Math.sin(yaw) * t;
            target.z -= cp * Math.cos(yaw) * t;
            target.y = 0;
        };

        // The GPU pick: one render of a 1 px frustum at the cursor gives both the packed id of what is drawn
        // there (attachment 0) and its depth (attachment 1), each after alpha discard, so cut-outs pass
        // through. The readback stalls the pipeline, so hovers render at the end of a frame and read at the
        // start of the next.
        const pickRT = new THREE.WebGLRenderTarget(1, 1, {count: 2});
        const idBuf = new Uint8Array(4);
        const pickBuf = new Uint8Array(4);
        const pickCam = new THREE.OrthographicCamera();
        let pickReady: {x: number; y: number; vx: number; vy: number} | null = null;
        let pickInFlight: {x: number; y: number; vx: number; vy: number} | null = null;

        const renderPickAt = (clientX: number, clientY: number): boolean => {
            const r = canvasRect;
            if (!r.width || !r.height) return false;
            const ndcX = ((clientX - r.left) / r.width) * 2 - 1;
            const ndcY = -(((clientY - r.top) / r.height) * 2 - 1);
            const vx = ndcX * camera.right;
            const vy = ndcY * camera.top;
            const eps = (2 * camera.right) / r.width;
            const pc = pickCam.copy(camera);
            pc.left = vx - eps;
            pc.right = vx + eps;
            pc.bottom = vy - eps;
            pc.top = vy + eps;
            pc.updateProjectionMatrix();
            const prevTarget = renderer.getRenderTarget();
            const prevClear = renderer.getClearColor(new THREE.Color());
            const prevAlpha = renderer.getClearAlpha();
            const prevBackground = scene.background;
            // The white clear reads as a miss both ways: depth past 1, and id alpha 255, as terrain's.
            scene.background = null;
            for (const m of materials.values()) {
                m.uniforms.pickMode.value = 1;
                m.blending = THREE.NoBlending;
                m.depthWrite = true;
            }
            const hidden: THREE.Object3D[] = [];
            // Rings, sprites and overlays stay out, or they would block objects and skew ground points.
            for (const child of scene.children) {
                if (child.userData.noPick && child.visible) {
                    child.visible = false;
                    hidden.push(child);
                }
            }
            renderer.setRenderTarget(pickRT);
            renderer.setClearColor(0xffffff, 1);
            renderer.clear();
            renderer.render(scene, pc);
            for (const child of hidden) child.visible = true;
            renderer.setRenderTarget(prevTarget);
            renderer.setClearColor(prevClear, prevAlpha);
            scene.background = prevBackground;
            for (const m of materials.values()) {
                m.uniforms.pickMode.value = 0;
                m.blending = m.userData.blending as THREE.Blending;
                m.depthWrite = m.userData.depthWrite as boolean;
            }
            pickInFlight = {x: clientX, y: clientY, vx, vy};
            return true;
        };

        const consumePick = () => {
            if (!pickInFlight) return;
            renderer.readRenderTargetPixels(pickRT, 0, 0, 1, 1, idBuf, undefined, 0);
            renderer.readRenderTargetPixels(pickRT, 0, 0, 1, 1, pickBuf, undefined, 1);
            pickReady = pickInFlight;
            pickInFlight = null;
        };

        // A hover resolves at the point the loop already rendered; a click renders and reads inline.
        const ensurePickAt = (clientX: number, clientY: number): {vx: number; vy: number} | null => {
            if (pickReady?.x === clientX && pickReady.y === clientY) return pickReady;
            if (!renderPickAt(clientX, clientY)) return null;
            consumePick();
            return pickReady;
        };

        const surfacePoint = (clientX: number, clientY: number): THREE.Vector3 | null => {
            const ready = ensurePickAt(clientX, clientY);
            if (!ready) return null;
            const {vx, vy} = ready;
            const d = pickBuf[0] / 255 + pickBuf[1] / (255 * 255) + pickBuf[2] / (255 * 255 * 255);
            window.__pickDebug = {
                buf: [pickBuf[0], pickBuf[1], pickBuf[2], pickBuf[3]],
                d,
                vx,
                vy,
                near: camera.near,
                far: camera.far,
            };
            if (d >= 0.9999) return null;
            const distForward = camera.near + d * (camera.far - camera.near);
            const {right, up} = screenBasis();
            const viewDir = new THREE.Vector3(
                -Math.cos(pitch) * Math.sin(yaw),
                -Math.sin(pitch),
                -Math.cos(pitch) * Math.cos(yaw),
            );
            return camera.position
                .clone()
                .addScaledVector(right, vx)
                .addScaledVector(up, vy)
                .addScaledVector(viewDir, distForward);
        };

        const raycaster = new THREE.Raycaster();
        // The cursor on the y 0 plane, kept near the view: at shallow pitch the horizon maps absurdly far away.
        const groundPoint = (clientX: number, clientY: number): THREE.Vector3 | null => {
            const r = renderer.domElement.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) return null;
            const ndc = new THREE.Vector2(
                ((clientX - r.left) / r.width) * 2 - 1,
                -((clientY - r.top) / r.height) * 2 + 1,
            );
            raycaster.setFromCamera(ndc, camera);
            const t = -raycaster.ray.origin.y / raycaster.ray.direction.y;
            if (!isFinite(t) || t < 0) return null;
            const p = raycaster.ray.origin.clone().addScaledVector(raycaster.ray.direction, t);
            const maxR = 2 * Math.hypot(camera.right, camera.top);
            const off = p.clone().sub(target);
            off.y = 0;
            if (off.length() > maxR) {
                p.sub(off).add(off.setLength(maxR));
            }
            return p;
        };
        const groundTile = (cx: number, cy: number): {x: number; z: number} | null => {
            const p = groundPoint(cx, cy);
            const mf = stateRef.current.manifest;
            if (!p || !mf) return null;
            return {
                x: Math.max(0, Math.floor((mf.botXTiles * 128 - p.x) / 128)),
                z: Math.max(0, Math.floor(p.z / 128)),
            };
        };
        let areaDrag = false;

        // Rotates camera and target together about `pivot`, then re-anchors the target where the view axis
        // meets y 0, so the camera can never sink under the world.
        const rotateAboutPivot = (pivot: THREE.Vector3, dYaw: number, dPitch: number) => {
            const dp = Math.max(PITCH_MIN, Math.min(PITCH_MAX, pitch + dPitch)) - pitch;
            const right = new THREE.Vector3(Math.cos(yaw), 0, -Math.sin(yaw));
            const q = new THREE.Quaternion()
                .setFromAxisAngle(new THREE.Vector3(0, 1, 0), dYaw)
                // Rotating by +a about `right` lowers the pitch, hence the negation.
                .multiply(new THREE.Quaternion().setFromAxisAngle(right, -dp));
            const newCam = camera.position.clone().sub(pivot).applyQuaternion(q).add(pivot);
            const newTgt = target.clone().sub(pivot).applyQuaternion(q).add(pivot);
            const d = newCam.sub(newTgt);
            const len = d.length();
            pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, Math.asin(Math.max(-1, Math.min(1, d.y / len)))));
            yaw = Math.atan2(d.x, d.z);
            const dirY = Math.sin(pitch);
            if (dirY >= 0.15) {
                const t0 = -newTgt.y / dirY;
                target.set(newTgt.x + (d.x / len) * t0, 0, newTgt.z + (d.z / len) * t0);
            } else {
                target.copy(newTgt);
                target.y = Math.max(-3000, Math.min(3000, target.y));
            }
            applyCamera();
        };

        let hostW = 800,
            hostH = 600;
        const canvasRect = {left: 0, top: 0, width: 0, height: 0};
        // Layout reads, taken at the top of a frame while last frame's layout is still clean; read mid-frame,
        // after the overlays have written their transforms, each would force a layout.
        const readLayout = () => {
            hostW = host.clientWidth || 800;
            hostH = host.clientHeight || 600;
            const r = renderer.domElement.getBoundingClientRect();
            canvasRect.left = r.left;
            canvasRect.top = r.top;
            canvasRect.width = r.width;
            canvasRect.height = r.height;
        };

        const q = new URLSearchParams(location.search);
        let viewHeightUnits = (num(q.get("zoom")) ?? 60) * 128;
        let aspect = 1;
        // A zoom alone: the drawing buffer keeps its size.
        const fitFrustum = () => {
            camera.left = (-viewHeightUnits * aspect) / 2;
            camera.right = (viewHeightUnits * aspect) / 2;
            camera.top = viewHeightUnits / 2;
            camera.bottom = -viewHeightUnits / 2;
            camera.updateProjectionMatrix();
        };
        const resize = () => {
            const w = captureSize ? captureSize.w : host.clientWidth || 800;
            const h = captureSize ? captureSize.h : host.clientHeight || 600;
            renderer.setPixelRatio(captureSize ? 1 : window.devicePixelRatio);
            // While recording, the backing store is exactly the capture size and the on-screen size is untouched.
            renderer.setSize(w, h, !captureSize);
            aspect = w / h;
            fitFrustum();
            readLayout();
        };
        const ro = new ResizeObserver(resize);
        ro.observe(host);

        // Re-centres on the shared camera box when this view is revealed; yaw and pitch stay this view's.
        const adoptShared = () => {
            const manifest = stateRef.current.manifest;
            if (!manifest) return;
            const sv = propsRef.current.sharedView?.current;
            if (!sv) return;
            const worldWidthUnits = manifest.botXTiles * 128;
            target.x = worldWidthUnits - sv.x * 128;
            target.z = sv.z * 128;
            if (sv.zoom > 0) {
                viewHeightUnits = sv.zoom * 128;
                resize();
            }
            applyCamera();
        };
        adoptUrlRef.current = adoptShared;
        // Lands the camera on a floor-local tile, zooming in first when the view is too far out to read.
        panToRef.current = (x: number, zLocal: number) => {
            const mf = stateRef.current.manifest;
            if (!mf) return;
            target.x = mf.botXTiles * 128 - (x * 128 + 64);
            target.z = zLocal * 128 + 64;
            if (viewHeightUnits > 120 * 128) {
                viewHeightUnits = 60 * 128;
                resize();
            }
            applyCamera();
        };
        // Eases back to north-up at HOME_PITCH_DEG about the view centre, the short way round. Each frame
        // applies a delta, so a drag mid-turn composes with it.
        homeViewRef.current = () => {
            if (flightRef.current) return;
            const dYaw = Math.atan2(Math.sin(-yaw), Math.cos(-yaw));
            const dPitch = (HOME_PITCH_DEG * Math.PI) / 180 - pitch;
            if (Math.abs(dYaw) < 1e-4 && Math.abs(dPitch) < 1e-4) return;
            const start = performance.now();
            let applied = 0;
            const step = (now: number) => {
                const s = Math.min(1, (now - start) / 350);
                const eased = s * s * (3 - 2 * s);
                const k = eased - applied;
                rotateAboutPivot(target.clone(), dYaw * k, dPitch * k);
                applied = eased;
                if (s < 1) requestAnimationFrame(step);
            };
            requestAnimationFrame(step);
        };

        interface HoverObj {
            id: number;
            dir: number;
            name: string;
            ax: number;
            az: number;
        }
        // Scenery by every tile it covers, so an object larger than a tile matches on any of them.
        const sceneryByTile = new Map<string, HoverObj[]>();
        Promise.all([fetchScenery(), fetchSceneryAtlasIndex()])
            .then(([placements, atlas]) => {
                for (const pl of placements) {
                    const ck = cellKeyOf(pl.floor, pl.x, pl.z);
                    let arr = placementsByCell.get(ck);
                    if (!arr) placementsByCell.set(ck, (arr = []));
                    arr.push(pl);
                    staticByTile.set(tileKeyOf(pl.floor, pl.x, pl.z), pl);
                }
                for (const key of terrainReady) dirtyCells.add(key);
                const meta = new Map<string, {name: string; tw: number; th: number}>();
                for (const e of atlas.entries) {
                    meta.set(`${e.id}_${e.dir}`, {name: e.name, tw: e.tw, th: e.th});
                }
                for (const pl of placements) {
                    const m = meta.get(`${pl.id}_${pl.dir}`);
                    const tw = m?.tw ?? 1;
                    const th = m?.th ?? 1;
                    const obj: HoverObj = {
                        id: pl.id,
                        dir: pl.dir,
                        name: m?.name ?? `object ${pl.id}`,
                        ax: pl.x,
                        az: pl.z,
                    };
                    for (let dx = 0; dx < tw; dx++) {
                        for (let dz = 0; dz < th; dz++) {
                            const key = `${pl.floor}:${pl.x + dx},${pl.z + dz}`;
                            let arr = sceneryByTile.get(key);
                            if (!arr) sceneryByTile.set(key, (arr = []));
                            arr.push(obj);
                        }
                    }
                }
            })
            .catch(() => {
                /* hover works without names */
            });

        type HoverReq = {kind: "canvas"; x: number; y: number} | {kind: "plate"; key: string} | {kind: "none"};
        // The latest hover request, drained once per frame: one pick per rendered frame however fast the
        // mouse moves, and one path, so a nameplate and the scene are never both lit.
        let pendingHover: HoverReq | null = null;
        let canvasCursor: {x: number; y: number} | null = null;
        let hoverWanted = false;
        let hoverPendingRead = false;
        let lastHoverText = "";
        let lastPickHover: {x: number; z: number; floor: number} | null = null;
        // The sticky hover tile: a pick on a wall face sits on the tile boundary, so the hovered tile only
        // switches once the pick moves HOVER_TILE_HYST past it.
        let hoverTileX: number | null = null;
        let hoverTileZ: number | null = null;
        let hoverPlane = kindsFor(stateRef.current.floor).plane;
        // The readout reports absolute z, as the server does, so a coordinate can be pasted elsewhere.
        const hoverText = (tgt: Target): string => {
            if (!tgt) return "";
            const plane = kindsFor(stateRef.current.floor).plane;
            const zAbs = (zl: number) => zl + plane * 944;
            if (tgt.type === "entity") {
                return (
                    `${tgt.kind} ${tgt.name ?? tgt.key}` +
                    (tgt.npcId != null ? ` (id ${tgt.npcId})` : "") +
                    ` · tile ${Math.round(tgt.x)},${zAbs(Math.round(tgt.z))}` +
                    (tgt.inCombat ? " · in combat" : "")
                );
            } else if (tgt.type === "object") {
                return (
                    `${tgt.name} (id ${tgt.id}, dir ${tgt.dir},` +
                    ` anchor ${tgt.ax},${zAbs(tgt.az)}, ${tgt.w}x${tgt.h})`
                );
            } else if (tgt.type === "wall") {
                return `${tgt.name} (boundary ${tgt.id}, dir ${tgt.dir},` + ` edge ${tgt.x},${zAbs(tgt.z)})`;
            } else if (tgt.type === "grounditem") {
                return (
                    `${tgt.name ?? "item"} (id ${tgt.id})` + ` · tile ${Math.round(tgt.x)},${zAbs(Math.round(tgt.z))}`
                );
            }
            return `tile ${tgt.x},${zAbs(tgt.z)}` + ` · h ${heightAt(tgt.x, tgt.z).toFixed(0)}`;
        };
        // The one place hover is applied; React state is touched only when the readout text changes.
        const commitHover = (tgt: Target) => {
            applyHover(tgt);
            const hg = propsRef.current.pickTool || propsRef.current.walkTool ? hoverGround : null;
            if (hg && (hg.x !== lastPickHover?.x || hg.z !== lastPickHover.z)) {
                lastPickHover = {x: hg.x, z: hg.z, floor: hg.floor};
                propsRef.current.onTileHover?.(lastPickHover);
            }
            const text = hoverText(tgt);
            if (text !== lastHoverText) {
                lastHoverText = text;
                setHover(text);
            }
        };
        const resolveHover = (clientX: number, clientY: number) => {
            if (!stateRef.current.manifest) return;
            commitHover(resolveTarget(clientX, clientY, true));
        };
        const hoverPlate = (key: string) => {
            const e = entityLayer.current().find((c) => c.key === key);
            commitHover(e ? {type: "entity", ...e} : null);
        };

        interface Drag {
            mode: "rotate" | "pan";
            button: number;
            pointerId: number;
            lastX: number;
            lastY: number;
            downX: number;
            downY: number;
            pivot: THREE.Vector3 | null;
            plateKey: string | null;
            shopId?: string | null;
            tagClick?: {pool: TagPool; key: string} | null;
            transport?: {name: string; dx: number; dz: number; dfloor: number} | null;
            noClick?: boolean;
            moved?: number;
        }
        let drag: Drag | null = null;

        // The follow a pan let go of, ignored until the host drops it, so the camera is free at once.
        let releasedFollow: string | null = null;
        // The followed entity's point at y 0, or null. While it answers, the camera belongs to it: panning is
        // off and rotation orbits it.
        const followPoint = (): THREE.Vector3 | null => {
            const mf = stateRef.current.manifest;
            if (!mf) return null;
            const name = propsRef.current.follow ?? null;
            if (name !== releasedFollow) releasedFollow = null;
            if (!name || name === releasedFollow) return null;
            const b = followedEntity(entityLayer.current(), name);
            return b ? new THREE.Vector3(mf.botXTiles * 128 - (b.x * 128 + 64), 0, b.z * 128 + 64) : null;
        };

        const breakFollow = (moved: number): boolean => {
            const onBreak = propsRef.current.onFollowBreak;
            if (!onBreak || moved < LOCK_PX) return false;
            releasedFollow = propsRef.current.follow ?? null;
            onBreak();
            return true;
        };

        const LOCK_PX = 4;
        // A camera drag takes pointer lock once past the click threshold, so it never runs out of screen.
        // Only Chromium is trusted with it; elsewhere it jumps the cursor about, and plain positions do.
        const lockCapable = (
            (navigator as {userAgentData?: {brands?: {brand: string}[]}}).userAgentData?.brands ?? []
        ).some((b) => b.brand === "Chromium");
        let lockOwner: number | null = null;
        // Taking the lock teleports the cursor, and some browsers report that as motion no event can tell
        // apart, so the drag stops steering from the request until one sample has been seen under the lock.
        let lockPhase: "idle" | "pending" | "engaged" = "idle";
        const isLocked = () => document.pointerLockElement === host;
        const wantLock = (pointerId: number) => {
            if (lockOwner != null || isLocked()) return;
            if (typeof host.requestPointerLock !== "function") return;
            lockOwner = pointerId;
            lockPhase = "pending";
            const clear = () => {
                lockOwner = null;
                lockPhase = "idle";
            };
            try {
                // Raw device deltas where supported; retried plain where rejected. A denial leaves the drag unlocked.
                const req: unknown = (host.requestPointerLock as (o?: {unadjustedMovement?: boolean}) => unknown)({
                    unadjustedMovement: true,
                });
                if (req instanceof Promise) {
                    req.catch(() => {
                        try {
                            const again: unknown = host.requestPointerLock();
                            if (again instanceof Promise) again.catch(clear);
                        } catch {
                            clear();
                        }
                    });
                }
            } catch {
                clear();
            }
        };
        // Set while the view releases the lock itself, so the change event is not taken for the browser's.
        let selfExit = false;
        const dropLock = () => {
            lockOwner = null;
            lockPhase = "idle";
            if (isLocked()) {
                selfExit = true;
                document.exitPointerLock();
            }
        };
        const onLockChange = () => {
            if (isLocked()) {
                lockPhase = "engaged";
                // A flick can end the drag before the lock lands; nothing else would release it.
                if (!drag) dropLock();
                return;
            }
            lockOwner = null;
            lockPhase = "idle";
            if (selfExit) {
                selfExit = false;
                return;
            }
            // The browser dropped the lock mid-drag: end the drag rather than steer with a hidden cursor.
            drag = null;
        };
        const onLockError = () => {
            lockOwner = null;
            lockPhase = "idle";
        };
        document.addEventListener("pointerlockchange", onLockChange);
        document.addEventListener("pointerlockerror", onLockError);

        // Touch: one finger pans and taps; two pinch to zoom, twist to yaw and drag vertically to pitch.
        const pointers = new Map<number, {x: number; y: number}>();
        interface Gesture {
            dist: number;
            midX: number;
            midY: number;
            angle: number;
            pivot: THREE.Vector3 | null;
        }
        let gesture: Gesture | null = null;

        // Zooms by `factor` keeping the view-plane point under the cursor fixed; while following, about the
        // centre instead, since the target is the followed entity's.
        const zoomAbout = (clientX: number, clientY: number, factor: number) => {
            const r = renderer.domElement.getBoundingClientRect();
            const ndcX = r.width ? ((clientX - r.left) / r.width) * 2 - 1 : 0;
            const ndcY = r.height ? -(((clientY - r.top) / r.height) * 2 - 1) : 0;
            const halfW = camera.right;
            const halfH = camera.top;
            const oldH = viewHeightUnits;
            viewHeightUnits = Math.min(1024 * 128, Math.max(6 * 128, viewHeightUnits * factor));
            const f = viewHeightUnits / oldH;
            resize();
            if (!followPoint()) {
                const {right, up} = screenBasis();
                target.addScaledVector(right, ndcX * halfW * (1 - f)).addScaledVector(up, ndcY * halfH * (1 - f));
            }
            regroundTarget();
            applyCamera();
        };

        const twoPointer = () => {
            const [a, b] = [...pointers.values()];
            return {
                dist: Math.hypot(a.x - b.x, a.y - b.y),
                midX: (a.x + b.x) / 2,
                midY: (a.y + b.y) / 2,
                angle: Math.atan2(b.y - a.y, b.x - a.x),
            };
        };
        const startGesture = () => {
            const p = twoPointer();
            gesture = {...p, pivot: surfacePoint(p.midX, p.midY) ?? groundPoint(p.midX, p.midY)};
        };
        const applyGesture = () => {
            if (!gesture || pointers.size < 2) return;
            const p = twoPointer();
            if (gesture.dist > 0 && p.dist > 0) {
                zoomAbout(p.midX, p.midY, gesture.dist / p.dist);
            }
            let dYaw = p.angle - gesture.angle;
            if (dYaw > Math.PI) dYaw -= 2 * Math.PI;
            else if (dYaw < -Math.PI) dYaw += 2 * Math.PI;
            const dPitch = (p.midY - gesture.midY) * 0.008;
            rotateAboutPivot(followPoint() ?? gesture.pivot ?? target, -dYaw, dPitch);
            gesture.dist = p.dist;
            gesture.midX = p.midX;
            gesture.midY = p.midY;
            gesture.angle = p.angle;
        };

        const el = renderer.domElement;
        el.style.touchAction = "none";
        host.style.touchAction = "none";
        // Input listens on the host, not the canvas, so it still arrives over the nameplates floating above.
        host.addEventListener("contextmenu", (e) => e.preventDefault(), {signal: listening.signal});
        host.addEventListener(
            "pointerdown",
            (e) => {
                if (flightRef.current) {
                    flightRef.current = null;
                    setFlying(false);
                }
                if (e.pointerType === "touch") {
                    pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
                    host.setPointerCapture(e.pointerId);
                    if (pointers.size >= 2) {
                        drag = null;
                        pendingHover = {kind: "none"};
                        if (pointers.size === 2) startGesture();
                        return;
                    }
                }
                if (e.button !== 0 && e.button !== 1 && e.button !== 2) return;
                pendingHover = {kind: "none"};
                const gripEl =
                    e.button === 0
                        ? e.target instanceof Element
                            ? e.target.closest<HTMLElement>("[data-grip]")
                            : null
                        : null;
                if (gripEl && areaRect) {
                    const [kx, kz] = (gripEl.dataset.grip ?? "").split(":");
                    areaGrip = {
                        kx: kx === "null" ? null : (kx as "x0" | "x1"),
                        kz: kz === "null" ? null : (kz as "z0" | "z1"),
                    };
                    areaGripCursor = gripEl.style.cursor;
                    el.style.cursor = areaGripCursor;
                    host.setPointerCapture(e.pointerId);
                    return;
                }
                if (propsRef.current.areaTool && e.button === 0 && !e.shiftKey) {
                    const at = groundTile(e.clientX, e.clientY);
                    if (at) {
                        areaDrag = true;
                        areaRect = {x0: at.x, z0: at.z, x1: at.x, z1: at.z};
                        areaRectDirty = true;
                        host.setPointerCapture(e.pointerId);
                        return;
                    }
                }
                // Left drag pans, middle or right rotates, shift-left rotates for trackpads; a press that never
                // drags is a click.
                const mode = e.button === 0 && !e.shiftKey ? "pan" : "rotate";
                // Or the middle button starts the browser's autoscroll.
                if (e.button === 1) e.preventDefault();
                // What the press landed on is read now: pointer capture retargets every later event to the host.
                const plate = e.target instanceof Element ? e.target.closest<HTMLElement>("[data-entity-key]") : null;
                drag = {
                    mode,
                    button: e.button,
                    pointerId: e.pointerId,
                    lastX: e.clientX,
                    lastY: e.clientY,
                    downX: e.clientX,
                    downY: e.clientY,
                    moved: 0,
                    pivot:
                        mode === "rotate"
                            ? (followPoint() ?? surfacePoint(e.clientX, e.clientY) ?? groundPoint(e.clientX, e.clientY))
                            : null,
                    plateKey: plate?.dataset.entityKey ?? null,
                    shopId:
                        (e.target instanceof Element ? e.target.closest<HTMLElement>("[data-shop-id]") : null)?.dataset
                            .shopId ?? null,
                    transport: transportRowAt(e.target),
                    tagClick: resolveTagClick(e.target),
                };
                window.__w3dPivot = drag.pivot ? {x: drag.pivot.x, y: drag.pivot.y, z: drag.pivot.z} : null;
                host.setPointerCapture(e.pointerId);
            },
            {signal: listening.signal},
        );
        host.addEventListener(
            "pointermove",
            (e) => {
                if (areaGrip) {
                    const at = groundTile(e.clientX, e.clientY);
                    if (at) areaGrip = resizeArea(areaGrip, at);
                    return;
                }
                if (areaDrag) {
                    const at = groundTile(e.clientX, e.clientY);
                    if (at && areaRect) {
                        areaRect.x1 = at.x;
                        areaRect.z1 = at.z;
                        areaRectDirty = true;
                    }
                    return;
                }
                if (e.pointerType === "touch" && pointers.has(e.pointerId)) {
                    pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
                    if (gesture && pointers.size >= 2) {
                        applyGesture();
                        return;
                    }
                }
                if (!drag) {
                    const plate =
                        e.target instanceof Element ? e.target.closest<HTMLElement>("[data-entity-key]") : null;
                    pendingHover = plate?.dataset.entityKey
                        ? {kind: "plate", key: plate.dataset.entityKey}
                        : e.target === el
                          ? {kind: "canvas", x: e.clientX, y: e.clientY}
                          : {kind: "none"};
                    return;
                }
                const locked = isLocked();
                let dx = locked ? e.movementX : e.clientX - drag.lastX;
                let dy = locked ? e.movementY : e.clientY - drag.lastY;
                drag.lastX = e.clientX;
                drag.lastY = e.clientY;
                if (lockPhase !== "idle") {
                    if (lockPhase === "engaged") lockPhase = "idle";
                    dx = 0;
                    dy = 0;
                }
                drag.moved = (drag.moved ?? 0) + Math.hypot(dx, dy);
                if (drag.mode === "rotate" && drag.moved >= LOCK_PX && e.pointerType === "mouse") {
                    drag.noClick = true;
                    if (lockCapable) wantLock(drag.pointerId);
                }
                if (drag.mode === "rotate") {
                    const pivot = followPoint() ?? drag.pivot ?? target;
                    rotateAboutPivot(pivot, -dx * 0.008, dy * 0.008);
                } else if (!followPoint() || breakFollow(drag.moved)) {
                    const wpp = viewHeightUnits / (renderer.domElement.clientHeight || 600);
                    const {right, up} = screenBasis();
                    target.addScaledVector(right, -dx * wpp).addScaledVector(up, dy * wpp);
                    regroundTarget();
                    applyCamera();
                }
            },
            {signal: listening.signal},
        );
        // A menu entry's `prio` is the client's MenuItemAction value, so sorting gives the client's menu order
        // and its left click: the top entry. Walk here at 920 beats Trade at 2810 and loses to Talk-to at 720.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/enumerations/MenuItemAction.java#L41
        interface MenuEntry {
            verb: string;
            target?: string;
            tcolor?: string;
            suffix?: string;
            scolor?: string;
            prio: number;
            run?: () => void;
        }

        // The client's level-difference colours for an attack entry.
        const levelColor = (delta: number) =>
            delta < -9
                ? "#ff0000"
                : delta < -6
                  ? "#ff3000"
                  : delta < -3
                    ? "#ff7000"
                    : delta < 0
                      ? "#ffb000"
                      : delta > 9
                        ? "#00ff00"
                        : delta > 6
                          ? "#40ff00"
                          : delta > 3
                            ? "#80ff00"
                            : delta > 0
                              ? "#c0ff00"
                              : "#fff";

        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L373
        const wildernessLevel = (x: number, zAbs: number) => {
            if (x + 2304 >= 2640) return 0;
            const wild = 2203 - (zAbs + (1776 - 944 * Math.floor(zAbs / 944)));
            return wild > 0 ? 1 + Math.floor(wild / 6) : 0;
        };

        // Sent where a verb's dir field means nothing, so repeating a command reads as a new one.
        let clickNonce = 1;

        const buildEntries = (clientX: number, clientY: number): MenuEntry[] => {
            const user = propsRef.current.selectedBot;
            const manifest = stateRef.current.manifest;
            if (!user || !manifest) return [];
            const L = resolveLayers(clientX, clientY);
            if (!L) return [];
            const plane = kindsFor(stateRef.current.floor).plane;
            const zAbs = (zl: number) => zl + plane * 944;
            const useIt = propsRef.current.useItem ?? null;
            const endItemUse = () => propsRef.current.onUseItemDone?.();
            const entries: MenuEntry[] = [];
            const send = (
                action: Parameters<typeof sendInteract>[1],
                x: number,
                z: number,
                id: number,
                dir: number,
                item = 0,
            ) => {
                sendInteract(user, action, x, z, id, dir, item).catch(() => {
                    // A lost click is the user's to repeat; the menu has closed either way.
                });
            };

            const ent = L.bot ?? L.ent;
            if (ent && !ent.ghost && ent.kind === "npc" && ent.npcId != null) {
                const idx = parseInt(ent.key.slice(4), 10);
                const nm = ent.name ?? npcSprites.info(ent.npcId)?.name ?? "npc";
                const x = Math.round(ent.x);
                const z = zAbs(Math.round(ent.z));
                const info = npcSprites.info(ent.npcId);
                if (useIt) {
                    entries.push({
                        verb: `Use ${useIt.name} with`,
                        target: nm,
                        tcolor: "#ff0",
                        prio: 710,
                        run: () => {
                            send("use_item_on_npc", x, z, idx, clickNonce++, useIt.id);
                            endItemUse();
                        },
                    });
                } else {
                    const self = (propsRef.current.observers ?? []).find((b) => b.username === user);
                    if (info?.atk) {
                        const delta = (self?.combatLvl ?? 0) - (info.lvl ?? 0);
                        entries.push({
                            verb: "Attack",
                            target: nm,
                            tcolor: "#ff0",
                            suffix: ` (level-${info.lvl})`,
                            scolor: levelColor(delta),
                            // Below Walk here when the NPC out-levels us, as the client ranks it.
                            prio: delta >= 0 ? 715 : 2715,
                            run: () => send("npc_attack", x, z, idx, clickNonce++),
                        });
                    }
                    entries.push({
                        verb: "Talk-to",
                        target: nm,
                        tcolor: "#ff0",
                        prio: 720,
                        run: () => send("npc_talk", x, z, idx, clickNonce++),
                    });
                    if (info?.cmd1) {
                        entries.push({
                            verb: info.cmd1,
                            target: nm,
                            tcolor: "#ff0",
                            prio: 725,
                            run: () => send("npc_command1", x, z, idx, clickNonce++),
                        });
                    }
                    if (info?.cmd2) {
                        entries.push({
                            verb: info.cmd2,
                            target: nm,
                            tcolor: "#ff0",
                            prio: 833,
                            run: () => send("npc_command2", x, z, idx, clickNonce++),
                        });
                    }
                }
            } else if (
                ent &&
                (ent.kind === "player" || ent.kind === "bot") &&
                !(ent.kind === "bot" && ent.name === user)
            ) {
                const idx =
                    ent.kind === "player"
                        ? parseInt(ent.key.slice(3), 10)
                        : (propsRef.current.observers ?? []).find((b) => b.username === ent.name)?.serverIndex;
                const nm = ent.name ?? "player";
                const x = Math.round(ent.x);
                const z = zAbs(Math.round(ent.z));
                if (idx != null && !useIt) {
                    // Attack is offered only in the wilderness; a foreign player's level is unknown, so it ranks as
                    // similar.
                    const wild = wildernessLevel(x, z) > 0;
                    if (wild) {
                        entries.push({
                            verb: "Attack",
                            target: nm,
                            tcolor: "#fff",
                            prio: 805,
                            run: () => send("player_attack", x, z, idx, clickNonce++),
                        });
                    } else {
                        entries.push({
                            verb: "Duel with",
                            target: nm,
                            tcolor: "#fff",
                            prio: 2806,
                            run: () => send("player_duel", x, z, idx, clickNonce++),
                        });
                    }
                    entries.push({
                        verb: "Trade with",
                        target: nm,
                        tcolor: "#fff",
                        prio: 2810,
                        run: () => send("player_trade", x, z, idx, clickNonce++),
                    });
                    entries.push({
                        verb: "Follow",
                        target: nm,
                        tcolor: "#fff",
                        prio: 2820,
                        run: () => send("player_follow", x, z, idx, clickNonce++),
                    });
                }
            }

            // One Take per item in a pile, nearest first.
            for (const g of L.ground) {
                const gx = Math.round(g.x);
                const gz = zAbs(Math.round(g.z));
                if (useIt) {
                    entries.push({
                        verb: `Use ${useIt.name} with`,
                        target: g.name ?? "item",
                        tcolor: "#ff9040",
                        prio: 210,
                        run: () => {
                            send("use_item_on_ground_item", gx, gz, g.id, clickNonce++, useIt.id);
                            endItemUse();
                        },
                    });
                } else {
                    entries.push({
                        verb: "Take",
                        target: g.name ?? "item",
                        tcolor: "#ff9040",
                        prio: 220,
                        run: () => send("ground_item", gx, gz, g.id, clickNonce++),
                    });
                }
            }

            // Only a definition's real commands appear, so decoration adds nothing and a click near it walks.
            if (L.wall?.type === "wall") {
                const wl = L.wall;
                const def = doorDefs?.get(wl.id);
                const z = zAbs(wl.z);
                if (useIt) {
                    entries.push({
                        verb: `Use ${useIt.name} with`,
                        target: wl.name,
                        tcolor: "#0ff",
                        prio: 310,
                        run: () => {
                            send("use_item_on_wall_object", wl.x, z, wl.id, wl.dir, useIt.id);
                            endItemUse();
                        },
                    });
                } else {
                    if (def?.cmd1) {
                        entries.push({
                            verb: def.cmd1,
                            target: wl.name,
                            tcolor: "#0ff",
                            prio: 320,
                            run: () => send("wall_object", wl.x, z, wl.id, wl.dir),
                        });
                    }
                    if (def?.cmd2) {
                        entries.push({
                            verb: def.cmd2,
                            target: wl.name,
                            tcolor: "#0ff",
                            prio: 2300,
                            run: () => send("wall_object2", wl.x, z, wl.id, wl.dir),
                        });
                    }
                }
            }

            if (L.obj?.type === "object") {
                const o = L.obj;
                const lib = objLib?.objects.get(o.id);
                const z = zAbs(o.az);
                if (useIt) {
                    entries.push({
                        verb: `Use ${useIt.name} with`,
                        target: o.name,
                        tcolor: "#0ff",
                        prio: 410,
                        run: () => {
                            send("use_item_on_object", o.ax, z, o.id, o.dir, useIt.id);
                            endItemUse();
                        },
                    });
                } else {
                    if (lib?.cmd1) {
                        entries.push({
                            verb: lib.cmd1,
                            target: o.name,
                            tcolor: "#0ff",
                            prio: 420,
                            run: () => send("object", o.ax, z, o.id, o.dir),
                        });
                    }
                    if (lib?.cmd2) {
                        entries.push({
                            verb: lib.cmd2,
                            target: o.name,
                            tcolor: "#0ff",
                            prio: 2400,
                            run: () => send("object2", o.ax, z, o.id, o.dir),
                        });
                    }
                }
            }

            if (L.tile) {
                const t = L.tile;
                entries.push({
                    verb: "Walk here",
                    prio: 920,
                    run: () => {
                        const walkHere = propsRef.current.onWalkHere;
                        if (walkHere) walkHere({x: t.x, z: zAbs(t.z), floor: plane});
                        else
                            sendWalk(user, {x: t.x, z: zAbs(t.z)}).catch(() => {
                                // A lost walk is the user's to repeat.
                            });
                        if (useIt) endItemUse();
                    },
                });
            }
            entries.sort((a, b) => a.prio - b.prio);
            return entries;
        };

        const openMenu = (clientX: number, clientY: number) => {
            const entries = buildEntries(clientX, clientY);
            if (entries.length === 0) return;
            entries.push({verb: "Cancel", prio: 4000});
            const r = host.getBoundingClientRect();
            setCtxMenu({x: clientX - r.left, y: clientY - r.top, entries});
        };

        const emitPick = (
            p: {
                kind: string;
                name: string | null;
                npcId: number | null;
                key: string;
                inCombat: boolean;
                appearance?: string | null;
            } | null,
        ) => {
            propsRef.current.onPickEntity(
                p == null
                    ? null
                    : {
                          kind: p.kind,
                          name: p.name,
                          npcId: p.npcId,
                          serverIndex: p.kind === "npc" && p.key.startsWith("npc:") ? Number(p.key.slice(4)) : null,
                          inCombat: p.inCombat,
                          appearance: p.appearance,
                      },
            );
        };

        const endDrag = (e: PointerEvent) => {
            const wasClick = drag && !drag.noClick && Math.hypot(e.clientX - drag.downX, e.clientY - drag.downY) < 4;
            if (wasClick && ctxMenuOpenRef.current) {
                setCtxMenu(null);
                drag = null;
                if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                return;
            }
            if (
                wasClick &&
                drag?.button === 2 &&
                (propsRef.current.walkTool || propsRef.current.useItem) &&
                propsRef.current.selectedBot
            ) {
                openMenu(e.clientX, e.clientY);
                drag = null;
                if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                return;
            }
            if (drag?.button === 0 && !drag.noClick && Math.hypot(e.clientX - drag.downX, e.clientY - drag.downY) < 4) {
                if (drag.transport) {
                    const t = drag.transport;
                    const f = Math.max(0, Math.min(3, t.dfloor));
                    setFloorIndexRef.current(f);
                    panToRef.current?.(t.dx, t.dz);
                    drag = null;
                    if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                    return;
                }
                if (drag.shopId) {
                    propsRef.current.onSelectShop?.(drag.shopId);
                    drag = null;
                    if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                    return;
                }
                if (drag.tagClick) {
                    drag.tagClick.pool.onClick?.(drag.tagClick.key, e);
                    drag = null;
                    if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                    return;
                }
                const plateKey = drag.plateKey;
                // A press on a nameplate selects its entity, as a click on the sprite would.
                const plateEnt = plateKey ? entityLayer.current().find((c) => c.key === plateKey) : null;
                const tgt: Target = plateEnt ? {type: "entity", ...plateEnt} : resolveTarget(e.clientX, e.clientY);
                // Ahead of the walk tool: while picking, a click means "here", the tile the hover shows.
                if (propsRef.current.pickTool && hoverGround) {
                    propsRef.current.onTilePick?.({
                        x: hoverGround.x,
                        z: hoverGround.z,
                        floor: hoverGround.floor,
                    });
                    drag = null;
                    if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                    return;
                }
                const user = propsRef.current.selectedBot;
                // The walk tool runs the top menu entry, preferring a clicked plate's own entity's entries.
                if ((propsRef.current.walkTool || propsRef.current.useItem) && user && tgt) {
                    let entries = buildEntries(e.clientX, e.clientY);
                    if (plateEnt && entries.length) {
                        const nm = plateEnt.name ?? "";
                        const own = entries.filter((en) => en.target === nm);
                        if (own.length) entries = own;
                    }
                    entries[0]?.run?.();
                } else if (tgt?.type === "entity") {
                    const selects = propsRef.current.selectOnClick;
                    entityLayer.selectedEntityKey = !selects || selects(tgt.kind) ? tgt.key : null;
                    if (tgt.kind === "bot") {
                        propsRef.current.onSelectBot?.(tgt.key.slice(4));
                        emitPick(null);
                    } else {
                        emitPick({
                            kind: tgt.kind,
                            name: tgt.name ?? null,
                            npcId: tgt.npcId ?? null,
                            key: tgt.key,
                            inCombat: !!tgt.inCombat,
                            appearance: tgt.appearance,
                        });
                    }
                } else if (tgt?.type === "object") {
                    emitPick({
                        kind: "object",
                        name: tgt.name,
                        npcId: tgt.id,
                        key: `obj:${tgt.ax},${tgt.az}`,
                        inCombat: false,
                    });
                } else if (tgt?.type === "wall") {
                    emitPick({
                        kind: "boundary",
                        name: tgt.name,
                        npcId: tgt.id,
                        key: `wall:${tgt.x},${tgt.z},${tgt.dir}`,
                        inCombat: false,
                    });
                } else {
                    entityLayer.selectedEntityKey = null;
                    emitPick(null);
                }
            }
            drag = null;
            if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
        };
        const onPointerUp = (e: PointerEvent) => {
            if (lockOwner === e.pointerId || isLocked()) dropLock();
            if (areaGrip) {
                areaGrip = null;
                el.style.cursor = "";
                if (host.hasPointerCapture(e.pointerId)) {
                    host.releasePointerCapture(e.pointerId);
                }
                return;
            }
            if (areaDrag) {
                areaDrag = false;
                if (host.hasPointerCapture(e.pointerId)) {
                    host.releasePointerCapture(e.pointerId);
                }
                if (areaRect) {
                    propsRef.current.onAreaRectChange?.({
                        x0: Math.min(areaRect.x0, areaRect.x1),
                        x1: Math.max(areaRect.x0, areaRect.x1),
                        z0: Math.min(areaRect.z0, areaRect.z1),
                        z1: Math.max(areaRect.z0, areaRect.z1),
                    });
                }
                return;
            }
            if (e.pointerType === "touch") {
                const wasTracked = pointers.delete(e.pointerId);
                if (gesture) {
                    if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                    if (pointers.size < 2) {
                        gesture = null;
                        // Down to one finger: it keeps panning without a new press.
                        if (pointers.size === 1) {
                            const [[remId, rem]] = [...pointers.entries()];
                            drag = {
                                mode: "pan",
                                button: 0,
                                pointerId: remId,
                                lastX: rem.x,
                                lastY: rem.y,
                                downX: rem.x,
                                downY: rem.y,
                                pivot: null,
                                plateKey: null,
                                noClick: true,
                            };
                        } else {
                            drag = null;
                        }
                    }
                    return;
                }
                if (!wasTracked) return;
            }
            endDrag(e);
        };
        host.addEventListener("pointerup", onPointerUp, {signal: listening.signal});
        host.addEventListener("pointercancel", onPointerUp, {signal: listening.signal});
        host.addEventListener(
            "pointerleave",
            () => {
                if (!drag) pendingHover = {kind: "none"};
            },
            {signal: listening.signal},
        );
        host.addEventListener(
            "lostpointercapture",
            (e) => {
                // Taking the lock can release the capture; under the lock the drag lives on. Only the drag whose
                // pointer lost capture ends: lifting one of two fingers releases that finger's alone.
                if (lockOwner === e.pointerId || isLocked()) return;
                if (drag?.pointerId === e.pointerId) drag = null;
            },
            {signal: listening.signal},
        );

        host.addEventListener(
            "wheel",
            (e) => {
                e.preventDefault();
                zoomAbout(e.clientX, e.clientY, Math.pow(1.0015, e.deltaY));
            },
            {passive: false, signal: listening.signal},
        );

        // Ground heights per plane on a corner grid, kept for every floor visited, so each keeps its own.
        const HGRID = 1009;
        let heightsRev = 0;
        let heightsChangedAt = 0;
        let heightsRebuildAt = 0;
        // Draped overlays rebuild on a height change, and every streamed terrain cell is one, so they wait
        // for the heights to stop moving, or until they are stale too long.
        const HEIGHTS_SETTLE_MS = 250;
        const HEIGHTS_MAX_STALE_MS = 2000;
        const heightsByPlane: (Float32Array | null)[] = [null, null, null, null];
        // Per plane, per tile: 1 where the terrain splits the tile along its main diagonal.
        const splitsByPlane: (Uint8Array | null)[] = [null, null, null, null];
        const splitsFor = (plane: number): Uint8Array => {
            let g = splitsByPlane[plane];
            if (!g) {
                g = new Uint8Array(HGRID * HGRID);
                splitsByPlane[plane] = g;
            }
            return g;
        };
        const heightsFor = (plane: number): Float32Array => {
            let g = heightsByPlane[plane];
            if (!g) {
                g = new Float32Array(HGRID * HGRID).fill(NaN);
                heightsByPlane[plane] = g;
            }
            return g;
        };
        // The plane whose cells are shown, advanced by the frame loop once the shown floor changes.
        let activePlane = kindsFor(stateRef.current.floor).plane;
        const stackedBelow = (): boolean => stateRef.current.below && (activePlane === 1 || activePlane === 2);
        // The active plane's groups always; with the floors beneath stacked, a lower plane's too, but not the
        // ground session's copies of the storeys those planes now draw themselves.
        const planeShown = (plane: number, kind: string): boolean => {
            if (plane === activePlane) return true;
            if (!stackedBelow() || plane > activePlane) return false;
            const storey = STOREY_OF_KIND[kind];
            return storey === undefined || storey > activePlane;
        };
        const groupShown = (g: THREE.Object3D): boolean =>
            planeShown(g.userData.plane as number, g.userData.kind as string);
        // How far a shown plane sits below its baked height: a storey per floor between it and the active one.
        const dropOf = (plane: number): number =>
            stackedBelow() && plane < activePlane ? (activePlane - plane) * STOREY_UNITS : 0;
        const sinkInto = (grid: Float32Array, cx: number, cz: number, y: number) => {
            if (cx >= 0 && cx < HGRID && cz >= 0 && cz < HGRID) {
                grid[cz * HGRID + cx] = y;
            }
        };
        const cornerHOf = (grid: Float32Array, cx: number, cz: number): number => {
            if (cx < 0 || cx >= HGRID || cz < 0 || cz >= HGRID) return 0;
            const h = grid[cz * HGRID + cx];
            return Number.isNaN(h) ? 0 : h;
        };
        // The ground's height as the client computes it: a tile is two triangles split along the anti-diagonal,
        // or the main one where the terrain says so; continuous across tiles, unlike bilinear.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/three/World.java#L1184
        const heightAtGrid = (grid: Float32Array, x: number, z: number, splits?: Uint8Array): number => {
            const sx = x + 0.5;
            const sz = z + 0.5;
            const x0 = Math.floor(sx);
            const z0 = Math.floor(sz);
            let fx = sx - x0;
            let fz = sz - z0;
            const h00 = cornerHOf(grid, x0, z0);
            const h10 = cornerHOf(grid, x0 + 1, z0);
            const h01 = cornerHOf(grid, x0, z0 + 1);
            const h11 = cornerHOf(grid, x0 + 1, z0 + 1);
            if (splits && x0 >= 0 && x0 < HGRID && z0 >= 0 && z0 < HGRID && splits[z0 * HGRID + x0] === 1) {
                return fx >= fz ? h00 + (h10 - h00) * fx + (h11 - h10) * fz : h00 + (h01 - h00) * fz + (h11 - h01) * fx;
            }
            if (fx <= 1 - fz) {
                return h00 + (h10 - h00) * fx + (h01 - h00) * fz;
            }
            fx = 1 - fx;
            fz = 1 - fz;
            return h11 + (h01 - h11) * fx + (h10 - h11) * fz;
        };
        let drapeFloor: FloorKey | null = null;
        let drapePlane = activePlane;
        // The plane draped overlays sit on: the shown floor's, as soon as a render has set it.
        const planeForDrape = (): number => {
            const f = stateRef.current.floor;
            if (f !== drapeFloor) {
                drapeFloor = f;
                drapePlane = kindsFor(f).plane;
            }
            return drapePlane;
        };
        const heightAt = (x: number, z: number): number =>
            heightAtGrid(heightsFor(planeForDrape()), x, z, splitsFor(planeForDrape()));
        const heightAtOf =
            (plane: number) =>
            (x: number, z: number): number =>
                heightAtGrid(heightsFor(plane), x, z, splitsFor(plane));
        // The terrain's normal in three space, x mirrored.
        const groundNormalAt = (x: number, z: number): THREE.Vector3 => {
            const dhdx = heightAt(x + 0.5, z) - heightAt(x - 0.5, z);
            const dhdz = heightAt(x, z + 0.5) - heightAt(x, z - 0.5);
            return new THREE.Vector3(dhdx / 128, 1, -dhdz / 128).normalize();
        };

        const entityHost = document.createElement("div");
        entityHost.style.cssText =
            // No text selection from a drag that starts on a nameplate.
            "position:absolute;inset:0;overflow:hidden;" + "pointer-events:none;user-select:none;";
        host.appendChild(entityHost);
        const entityLayer = new EntityLayer(scene, entityHost);
        let lastEntitiesRev = -1;
        let lastSightHeightsRev = -1;
        const sightLayer = new SightLayer(scene);
        const watchedSight = new WatchedSightLayer(scene, 0xff4a3d);
        let watchedAt = "";
        const wildernessLayer = new WildernessLayer(scene, entityHost);
        let lastWildStride = 0;
        let lastWildFloor: FloorKey | null = null;
        let lastWildHeightsRev = -1;

        const areaRibbon = new Ribbon(scene, 0x4ec9ff, 0.9, 0.3);
        let areaRect: {x0: number; z0: number; x1: number; z1: number} | null = null;
        let areaRectDirty = false;
        let lastAreaHeightsRev = -1;
        const areaEdges = () => (areaRect ? boxEdges(areaRect) : null);
        const rebuildAreaRect = (toWorld: (x: number, z: number) => THREE.Vector3) => {
            areaRibbon.extrude(areaRect ? [{pts: boxPerimeter(areaRect), closed: true}] : [], toWorld);
        };

        interface Grip {
            kx: "x0" | "x1" | null;
            kz: "z0" | "z1" | null;
        }
        const GRIPS: Grip[] = [
            {kx: "x0", kz: "z0"},
            {kx: "x1", kz: "z0"},
            {kx: "x0", kz: "z1"},
            {kx: "x1", kz: "z1"},
            {kx: "x0", kz: null},
            {kx: "x1", kz: null},
            {kx: null, kz: "z0"},
            {kx: null, kz: "z1"},
        ];
        const gripKey = (g: Grip) => `${g.kx}:${g.kz}`;
        // The committed rectangle's resize handles: DOM squares, one size at every zoom, hit-tested by the
        // browser and able to wear a resize cursor.
        const gripDivs = new Map<string, HTMLDivElement>();
        const gripTile = (g: Grip, e: {x0: number; x1: number; z0: number; z1: number}) => ({
            x: g.kx === "x0" ? e.x0 : g.kx === "x1" ? e.x1 : (e.x0 + e.x1) / 2,
            z: g.kz === "z0" ? e.z0 : g.kz === "z1" ? e.z1 : (e.z0 + e.z1) / 2,
        });
        let pinnedMenu: HTMLDivElement | null = null;
        let pinnedLines: readonly MenuLine[] | null = null;
        const framePinnedMenu = (toWorld: (x: number, z: number) => THREE.Vector3) => {
            const m = propsRef.current.menuAt;
            if (!m) {
                if (pinnedMenu) pinnedMenu.style.display = "none";
                return;
            }
            if (!pinnedMenu) {
                pinnedMenu = document.createElement("div");
                Object.assign(pinnedMenu.style, MENU_STYLE, {
                    position: "absolute",
                    zIndex: "2000000",
                    pointerEvents: "none",
                    transformOrigin: "0 0",
                    lineHeight: "15px",
                    textShadow: MENU_TEXT_SHADOW,
                });
                entityHost.appendChild(pinnedMenu);
            }
            if (m.lines !== pinnedLines) {
                pinnedLines = m.lines;
                const span = (text: string, color: string) => {
                    const el = document.createElement("span");
                    el.textContent = text;
                    el.style.color = color;
                    return el;
                };
                const row = (verb: string, color: string, l?: MenuLine) => {
                    const el = document.createElement("div");
                    el.append(span(verb, color));
                    // RuneLite's collapsed ground items: "Take Shark x 13", the count in the name's colour.
                    const count = l?.count != null && l.count > 1 ? ` x ${l.count}` : "";
                    if (l?.target) el.append(span(` ${l.target}${count}`, l.tcolor ?? "#fff"));
                    return el;
                };
                pinnedMenu.replaceChildren(row("Choose option", "#0ff"), ...m.lines.map((l) => row(l.verb, "#fff", l)));
            }
            camera.updateMatrixWorld();
            const v = toWorld(m.x, m.z % 944).project(camera);
            const px = ((v.x + 1) / 2) * (host.clientWidth || 800);
            const py = ((1 - v.y) / 2) * (host.clientHeight || 600);
            pinnedMenu.style.display = "";
            pinnedMenu.style.transform = `scale(${m.scale})`;
            // Kept on screen, as the client keeps its own.
            const w = pinnedMenu.offsetWidth * m.scale;
            const h = pinnedMenu.offsetHeight * m.scale;
            const left = Math.max(0, Math.min((host.clientWidth || 800) - w, px - 4 * m.scale));
            const top = Math.max(0, Math.min((host.clientHeight || 600) - h, py - 8 * m.scale));
            pinnedMenu.style.left = `${left}px`;
            pinnedMenu.style.top = `${top}px`;
        };
        // Picked from where a handle lands on screen relative to the box's centre, since the camera turns.
        const CURSORS = ["ew-resize", "nesw-resize", "ns-resize", "nwse-resize"];
        const frameAreaGrips = (toWorld: (x: number, z: number) => THREE.Vector3) => {
            const e = areaEdges();
            const armed = propsRef.current.areaTool === true;
            const show = e != null && !areaDrag && !armed;
            if (!show) {
                for (const d of gripDivs.values()) d.style.display = "none";
                return;
            }
            camera.updateMatrixWorld();
            const w = host.clientWidth || 800;
            const h = host.clientHeight || 600;
            const mid = toWorld((e.x0 + e.x1) / 2, (e.z0 + e.z1) / 2).project(camera);
            const mx = ((mid.x + 1) / 2) * w;
            const my = ((1 - mid.y) / 2) * h;
            for (const g of GRIPS) {
                const key = gripKey(g);
                let div = gripDivs.get(key);
                if (!div) {
                    div = document.createElement("div");
                    div.dataset.grip = key;
                    div.style.cssText =
                        "position:absolute;width:9px;height:9px;" +
                        "transform:translate(-50%,-50%);border-radius:2px;" +
                        "background:#4ec9ff;border:1px solid #0d1117;" +
                        "pointer-events:auto;z-index:906000;";
                    entityHost.appendChild(div);
                    gripDivs.set(key, div);
                }
                const t = gripTile(g, e);
                const v = toWorld(t.x, t.z).project(camera);
                if (v.z > 1 || v.z < -1 || v.x < -1.05 || v.x > 1.05 || v.y < -1.05 || v.y > 1.05) {
                    div.style.display = "none";
                    continue;
                }
                const px = ((v.x + 1) / 2) * w;
                const py = ((1 - v.y) / 2) * h;
                div.style.display = "";
                div.style.left = `${px}px`;
                div.style.top = `${py}px`;
                const a = Math.atan2(my - py, px - mx);
                const oct = ((Math.round(a / (Math.PI / 4)) % 4) + 4) % 4;
                div.style.cursor = CURSORS[oct];
            }
        };
        // Moves the edges a handle owns; dragged past the opposite edge the box flips and the handle follows
        // its edge. Reported live, so a host can track the drag.
        const resizeArea = (g: Grip, to: {x: number; z: number}): Grip => {
            if (!areaRect) return g;
            let {x0, x1, z0, z1} = areaRect;
            let {kx, kz} = g;
            if (kx === "x0") x0 = to.x;
            else if (kx === "x1") x1 = to.x;
            if (kz === "z0") z0 = to.z;
            else if (kz === "z1") z1 = to.z;
            if (x0 > x1) {
                [x0, x1] = [x1, x0];
                if (kx) kx = kx === "x0" ? "x1" : "x0";
            }
            if (z0 > z1) {
                [z0, z1] = [z1, z0];
                if (kz) kz = kz === "z0" ? "z1" : "z0";
            }
            const changed = x0 !== areaRect.x0 || x1 !== areaRect.x1 || z0 !== areaRect.z0 || z1 !== areaRect.z1;
            if (changed) {
                areaRect = {x0, x1, z0, z1};
                areaRectDirty = true;
                propsRef.current.onAreaRectChange?.({x0, x1, z0, z1});
            }
            return {kx, kz};
        };
        let areaGrip: Grip | null = null;
        let areaGripCursor = "";
        const sameArea = (
            a: {x0: number; z0: number; x1: number; z1: number} | null | undefined,
            b: {x0: number; z0: number; x1: number; z1: number} | null | undefined,
        ) =>
            !a || !b ? (a ?? null) === (b ?? null) : a.x0 === b.x0 && a.x1 === b.x1 && a.z0 === b.z0 && a.z1 === b.z1;

        const npcSprites = new NpcSpriteLayer(scene);
        const playerSprites = new PlayerSpriteLayer(scene);
        entityLayer.npcDims = (id) => npcSprites.dims(id);
        let chatFontLoading = false;
        // The atlases and the font are retried by the periodic loop until they load.
        const loadChatFont = () => {
            if (chatFontLoading || entityLayer.chatFont) return;
            chatFontLoading = true;
            loadGameFont()
                .then((f) => {
                    if (!disposed) entityLayer.chatFont = f;
                })
                .catch(() => {
                    chatFontLoading = false;
                });
        };
        loadChatFont();
        const groundItems = new GroundItemLayer(scene);
        const projectileLayer = new ProjectileLayer(scene);
        let npcAtlasLoading = false;
        const loadNpcAtlas = () => {
            if (npcAtlasLoading) return;
            npcAtlasLoading = true;
            void fetchNpcAtlas().then((a) => {
                if (disposed || !a) {
                    npcAtlasLoading = false;
                    return;
                }
                npcSprites.setAtlas(a);
            });
        };

        let itemAtlasLoading = false;
        const loadItemAtlas = () => {
            if (itemAtlasLoading) return;
            itemAtlasLoading = true;
            void fetchItemAtlas().then((a) => {
                if (disposed || !a) {
                    itemAtlasLoading = false;
                    return;
                }
                groundItems.setAtlas(a);
                entityLayer.itemFrame = (id) => {
                    const f = a.frames.get(id);
                    return f
                        ? {x: f.x, y: f.y, w: f.w, h: f.h, atlasW: a.width, atlasH: a.height, src: a.pngUrl}
                        : null;
                };
            });
        };

        let objLib: ObjectLibrary | null = null;
        const placementsByCell = new Map<string, SceneryPlacement[]>();
        const staticByTile = new Map<string, SceneryPlacement>();
        // Scenery is assembled per 48-tile cell from the static placements and the object library; a live
        // object at a tile overrides its id and rebuilds just that cell.
        const overrides = new Map<string, number>();
        const sceneryMeshes = new Map<string, THREE.Group>();
        const dirtyCells = new Set<string>();
        const terrainReady = new Set<string>();
        let lastObservedRev = -1;
        const cellKeyOf = (plane: number, x: number, z: number) =>
            `${plane}:${Math.floor((x - 24) / 48)},${Math.floor((z - 24) / 48)}`;
        const tileKeyOf = (plane: number, x: number, z: number) => `${plane}:${x},${z}`;

        // Animated scenery (fires, torches) gets one group per frame per cell, cycled every 120 ms as the
        // client does; windmill sails get a pivot each, their geometry recentred on the hub to spin in place.
        const animMeshes = new Map<string, THREE.Group[]>();
        const windmillMeshes = new Map<string, {group: THREE.Group; axis: THREE.Vector3}[]>();
        const disposeSceneryCell = (key: string) => {
            const g = sceneryMeshes.get(key);
            if (g) {
                scene.remove(g);
                g.traverse((o) => {
                    if (o instanceof THREE.Mesh) (o.geometry as THREE.BufferGeometry).dispose();
                });
                sceneryMeshes.delete(key);
            }
            const frames = animMeshes.get(key);
            if (frames) {
                for (const f of frames) {
                    scene.remove(f);
                    f.traverse((o) => {
                        if (o instanceof THREE.Mesh) (o.geometry as THREE.BufferGeometry).dispose();
                    });
                }
                animMeshes.delete(key);
            }
            const mills = windmillMeshes.get(key);
            if (mills) {
                for (const {group} of mills) {
                    scene.remove(group);
                    group.traverse((o) => {
                        if (o instanceof THREE.Mesh) (o.geometry as THREE.BufferGeometry).dispose();
                    });
                }
                windmillMeshes.delete(key);
            }
        };

        const sceneryIdFor = (pl: ResolvedPlacement) => packSceneryId(pl.x, pl.z);
        const buildSceneryCell = (key: string) => {
            const manifest = stateRef.current.manifest;
            const lib = objLib;
            if (!lib || !manifest) return;
            disposeSceneryCell(key);
            const statics = placementsByCell.get(key) ?? [];
            const plane = parseInt(key.split(":")[0], 10);
            const resolved: ResolvedPlacement[] = [];
            const staticTiles = new Set<string>();
            for (const pl of statics) {
                const tk = tileKeyOf(plane, pl.x, pl.z);
                staticTiles.add(tk);
                const ov = overrides.get(tk);
                // -1 is a removed object: nothing at the tile.
                if (ov === -1) continue;
                resolved.push({id: ov ?? pl.id, dir: pl.dir, x: pl.x, z: pl.z});
            }
            for (const [tk, id] of overrides) {
                if (id < 0) continue;
                const [p0, xz] = [tk.split(":")[0], tk.split(":")[1]];
                if (parseInt(p0, 10) !== plane || staticTiles.has(tk)) continue;
                const [x, z] = xz.split(",").map(Number);
                if (cellKeyOf(plane, x, z) !== key) continue;
                resolved.push({id, dir: 0, x, z});
            }
            if (resolved.length === 0) return;
            const worldWidthUnits = manifest.botXTiles * 128;
            // The cell's own plane's heights, since this may rebuild a hidden floor; its groups stay hidden
            // unless that floor is shown.
            const cellHeightAt = heightAtOf(plane);
            const animated = resolved.filter((pl) => lib.anims.has(pl.id));
            const windmills = resolved.filter((pl) => pl.id === WINDMILL_ID);
            const statics2 = resolved.filter((pl) => !lib.anims.has(pl.id) && pl.id !== WINDMILL_ID);
            if (statics2.length > 0) {
                const group = new THREE.Group();
                group.userData.kind = "scenery";
                group.userData.plane = plane;
                for (const {tex, translucent, geometry} of assembleCell(
                    lib,
                    statics2,
                    worldWidthUnits,
                    cellHeightAt,
                    sceneryIdFor,
                )) {
                    group.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked, translucent)));
                }
                group.visible = plane === activePlane;
                sceneryMeshes.set(key, group);
                scene.add(freezeTransform(group));
            }
            if (animated.length > 0) {
                const maxFrames = Math.max(...animated.map((pl) => lib.anims.get(pl.id)?.length ?? 0));
                const frames: THREE.Group[] = [];
                for (let f = 0; f < maxFrames; f++) {
                    const g = new THREE.Group();
                    g.userData.kind = "scenery";
                    g.userData.plane = plane;
                    const framed = animated.map((pl) => {
                        const models = need(lib.anims.get(pl.id), "animated scenery frames");
                        return {...pl, model: models[f % models.length]};
                    });
                    for (const {tex, translucent, geometry} of assembleCell(
                        lib,
                        framed,
                        worldWidthUnits,
                        cellHeightAt,
                        sceneryIdFor,
                    )) {
                        g.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked, translucent)));
                    }
                    g.visible = plane === activePlane && f === 0;
                    frames.push(g);
                    scene.add(freezeTransform(g));
                }
                animMeshes.set(key, frames);
            }
            if (windmills.length > 0) {
                const mills: {group: THREE.Group; axis: THREE.Vector3}[] = [];
                for (const pl of windmills) {
                    const hub = sceneryHub(lib, pl, worldWidthUnits, cellHeightAt);
                    if (!hub) continue;
                    const pivot = new THREE.Group();
                    pivot.userData.kind = "scenery";
                    pivot.userData.plane = plane;
                    pivot.userData.baseY = hub.y;
                    pivot.position.copy(hub);
                    for (const {tex, translucent, geometry} of assembleCell(
                        lib,
                        [pl],
                        worldWidthUnits,
                        cellHeightAt,
                        sceneryIdFor,
                    )) {
                        geometry.translate(-hub.x, -hub.y, -hub.z);
                        pivot.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked, translucent)));
                    }
                    const yawR = (pl.dir * 32 * 2 * Math.PI) / 256;
                    // The model's local x, turned by the placement's yaw as assembleCell turns it, x mirrored.
                    const axis = new THREE.Vector3(-Math.cos(yawR), 0, -Math.sin(yawR)).normalize();
                    pivot.visible = plane === activePlane;
                    mills.push({group: pivot, axis});
                    scene.add(pivot);
                }
                windmillMeshes.set(key, mills);
            }
        };

        let doorDefs: Map<number, DoorDefLite> | null = null;
        const doorsByCell = new Map<string, BoundaryPlacement[]>();
        const staticDoorByEdge = new Map<string, BoundaryPlacement>();
        const doorOverrides = new Map<string, number>();
        const doorMeshes = new Map<string, THREE.Group>();
        const edgeKeyOf = (plane: number, x: number, z: number, dir: number) => `${plane}:${x},${z},${dir}`;

        const disposeDoorCell = (key: string) => {
            const g = doorMeshes.get(key);
            if (!g) return;
            scene.remove(g);
            g.traverse((o) => {
                if (o instanceof THREE.Mesh) (o.geometry as THREE.BufferGeometry).dispose();
            });
            doorMeshes.delete(key);
        };

        const buildDoorCell = (key: string) => {
            const manifest = stateRef.current.manifest;
            if (!doorDefs || !manifest) return;
            disposeDoorCell(key);
            const statics = doorsByCell.get(key) ?? [];
            const plane = parseInt(key.split(":")[0], 10);
            const resolved: BoundaryPlacement[] = [];
            const staticEdges = new Set<string>();
            for (const b of statics) {
                const ek = edgeKeyOf(plane, b.x, b.z, b.dir);
                staticEdges.add(ek);
                const ov = doorOverrides.get(ek);
                // -1 is a removed boundary, such as an open door that has no open id.
                if (ov === -1) continue;
                resolved.push(ov != null ? {...b, id: ov} : b);
            }
            for (const [ek, id] of doorOverrides) {
                if (id < 0) continue;
                const [p0, rest] = [ek.split(":")[0], ek.split(":")[1]];
                if (parseInt(p0, 10) !== plane || staticEdges.has(ek)) continue;
                const [x, z, dir] = rest.split(",").map(Number);
                if (cellKeyOf(plane, x, z) !== key) continue;
                resolved.push({id, x, z, floor: plane, dir});
            }
            if (resolved.length === 0) return;
            const worldWidthUnits = manifest.botXTiles * 128;
            const group = new THREE.Group();
            group.userData.kind = "doors";
            group.userData.plane = plane;
            const planeHeightAt = heightAtOf(plane);
            const corner = (cx: number, cz: number) => planeHeightAt(cx - 0.5, cz - 0.5);
            for (const {tex, geometry} of assembleDoors(doorDefs, resolved, worldWidthUnits, corner, (b) =>
                packDoorId(b.x, b.z, b.dir),
            )) {
                group.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
            }
            group.visible = plane === activePlane;
            doorMeshes.set(key, group);
            scene.add(freezeTransform(group));
        };

        let doorDataLoading = false;
        const loadDoorData = () => {
            if (doorDefs || doorDataLoading) return;
            doorDataLoading = true;
            void fetchDoorData().then((d) => {
                doorDataLoading = false;
                if (disposed || !d) return;
                doorDefs = d.defs;
                for (const b of d.boundaries) {
                    const ck = cellKeyOf(b.floor, b.x, b.z);
                    let arr = doorsByCell.get(ck);
                    if (!arr) doorsByCell.set(ck, (arr = []));
                    arr.push(b);
                    staticDoorByEdge.set(edgeKeyOf(b.floor, b.x, b.z, b.dir), b);
                }
                for (const key of terrainReady) dirtyCells.add(key);
            });
        };

        type Target =
            | {
                  type: "entity";
                  key: string;
                  kind: string;
                  name?: string | null;
                  npcId?: number;
                  appearance?: string | null;
                  x: number;
                  z: number;
                  inCombat?: boolean;
                  ghost?: boolean;
              }
            | {type: "object"; id: number; name: string; ax: number; az: number; dir: number; w: number; h: number}
            | {type: "wall"; id: number; name: string; x: number; z: number; dir: number}
            | {type: "grounditem"; id: number; name?: string | null; x: number; z: number}
            | {type: "tile"; x: number; z: number}
            | null;

        // Hover highlight quads for the tiles under what the cursor is on.
        const TILE_POOL = 48;
        const tileGeo = new THREE.BufferGeometry();
        tileGeo.setAttribute("position", new THREE.BufferAttribute(new Float32Array(TILE_POOL * 4 * 3), 3));
        const tileIdx = new Uint16Array(TILE_POOL * 6);
        for (let i = 0; i < TILE_POOL; i++) {
            tileIdx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        tileGeo.setIndex(new THREE.BufferAttribute(tileIdx, 1));
        tileGeo.setDrawRange(0, 0);
        const tileMat = new THREE.MeshBasicMaterial({
            color: 0xffe45c,
            transparent: true,
            opacity: 0.28,
            depthWrite: false,
            polygonOffset: true,
            polygonOffsetFactor: -3,
            polygonOffsetUnits: -3,
            side: THREE.DoubleSide,
        });
        const tileMesh = new THREE.Mesh(tileGeo, tileMat);
        tileMesh.renderOrder = 9;
        tileMesh.frustumCulled = false;
        // Out of the picks, where it would cover the object beneath it.
        tileMesh.userData.noPick = true;
        scene.add(freezeTransform(tileMesh));

        const setTileHighlights = (tiles: {x: number; z: number}[]) => {
            const posA = tileGeo.getAttribute("position") as THREE.BufferAttribute;
            const manifest = stateRef.current.manifest;
            if (!manifest) return;
            const W = manifest.botXTiles * 128;
            let i = 0;
            for (const t of tiles) {
                if (i >= TILE_POOL) break;
                const c = (cx: number, cz: number) =>
                    new THREE.Vector3(W - cx * 128, heightAt(cx - 0.5, cz - 0.5) + 3, cz * 128);
                const v = [c(t.x, t.z), c(t.x + 1, t.z), c(t.x + 1, t.z + 1), c(t.x, t.z + 1)];
                for (let k = 0; k < 4; k++) posA.setXYZ(i * 4 + k, v[k].x, v[k].y, v[k].z);
                i++;
            }
            posA.needsUpdate = true;
            tileGeo.setDrawRange(0, i * 6);
        };

        let sceneryGlow: THREE.Group | null = null;
        let sceneryGlowKey = "";
        const glowMat = new THREE.MeshBasicMaterial({
            color: 0xffe45c,
            transparent: true,
            opacity: 0.4,
            blending: THREE.AdditiveBlending,
            depthWrite: false,
        });
        // The hovered object re-assembled and drawn additively; out of the picks, where it would fight the
        // real object and flicker its own hover.
        const setSceneryGlow = (o: {id: number; dir: number; ax: number; az: number} | null) => {
            const key = o ? `${o.id}:${o.ax},${o.az},${o.dir}` : "";
            if (key === sceneryGlowKey) return;
            sceneryGlowKey = key;
            if (sceneryGlow) {
                scene.remove(sceneryGlow);
                sceneryGlow.traverse((m) => {
                    if (m instanceof THREE.Mesh) (m.geometry as THREE.BufferGeometry).dispose();
                });
                sceneryGlow = null;
            }
            const manifest = stateRef.current.manifest;
            if (!o || !objLib || !manifest) return;
            const group = new THREE.Group();
            for (const {geometry} of assembleCell(
                objLib,
                [{id: o.id, dir: o.dir, x: o.ax, z: o.az}],
                manifest.botXTiles * 128,
                heightAt,
            )) {
                const mesh = new THREE.Mesh(geometry, glowMat);
                mesh.renderOrder = 9;
                group.add(mesh);
            }
            group.userData.noPick = true;
            sceneryGlow = group;
            scene.add(freezeTransform(group));
        };

        const overlayHost = new OverlayHost(scene, camera, entityHost);
        const clusterGrid = clusterOverlay();
        const dangerZones = dangerOverlay();
        const walkingComponents = componentsOverlay();
        let clusterList: {manifest: WorldMeshManifest; ids: number[]} | null = null;
        const clustersOf = (m: WorldMeshManifest): number[] => {
            if (clusterList?.manifest !== m) clusterList = {manifest: m, ids: manifestClusters(m)};
            return clusterList.ids;
        };
        // The heights revision as overlays see it: it moves only once the terrain has settled, so a draped
        // overlay redrapes once per pan rather than once per streamed cell.
        let settledHeightsRev = -1;
        let syncedOverlays: OverlayEntry[] = [];
        let hoverGround: OverlayPoint | null = null;

        // A countdown tag over each depleted object.
        const respawnTags = new TagPool(entityHost, {
            css:
                "font:10px monospace;color:rgba(196,200,208,.5);" +
                "background:rgba(10,14,24,.25);padding:0 4px;border-radius:4px;",
            offsetY: 26,
        });
        const frameRespawnTags = (
            plane: number,
            showTags: boolean,
            zoomTiles: number,
            view: ViewSize,
            toWorld: (x: number, z: number) => THREE.Vector3,
        ) => {
            const st = stateRef.current;
            respawnTags.begin(view, camera);
            if (showTags && zoomTiles <= 60) {
                for (const g of st.respawnGhosts) {
                    if (g.plane !== plane) continue;
                    respawnTags.put(
                        `${g.x},${g.z}`,
                        toWorld(g.x, g.z),
                        `${objLib?.objects.get(g.id)?.name ?? g.id} · ${g.t}t`,
                    );
                }
            }
            respawnTags.end();
        };

        interface TransportMarker {
            name: string;
            label?: string | null;
            req?: string | null;
            perEntry?: boolean;
            anchor: {x: number; z: number} | null;
            boundary?: {x: number; z: number; dir: number} | null;
            entries: {x: number; z: number}[];
            dx: number;
            dz: number;
            dfloor: number;
        }
        // One row of a transport label: transports with the same label, requirement and destination merged,
        // their entries pooled. Per-entry crossings merge whatever their destination, each entry its own.
        interface TransportRow {
            name: string;
            req: string | null;
            perEntry: boolean;
            dx: number;
            dz: number;
            dfloor: number;
            entries: {x: number; z: number}[];
        }
        // Everything one object carries, and the footprint the cursor may cross without dropping it.
        interface TransportGroup {
            key: string;
            title: string;
            at: {x: number; z: number};
            rows: TransportRow[];
            entries: {x: number; z: number}[];
            footprint: Set<string>;
        }
        const addToGroup = (g: TransportGroup, t: TransportMarker) => {
            const name = t.label != null && t.label !== "" ? t.label : t.name.replace(/ @\d+,\d+$/, "");
            const req = t.req ?? null;
            const perEntry = t.perEntry === true;
            let row = g.rows.find(
                (r) =>
                    r.name === name &&
                    r.req === req &&
                    r.perEntry === perEntry &&
                    (perEntry || (r.dx === t.dx && r.dz === t.dz && r.dfloor === t.dfloor)),
            );
            if (!row) {
                row = {name, req, perEntry, dx: t.dx, dz: t.dz, dfloor: t.dfloor, entries: []};
                g.rows.push(row);
            }
            for (const en of t.entries) {
                const k = `${en.x},${en.z}`;
                if (!g.footprint.has(k)) g.entries.push(en);
                if (!row.entries.some((e) => e.x === en.x && e.z === en.z)) row.entries.push(en);
                g.footprint.add(k);
            }
        };
        const transportOutlineMat = new THREE.LineBasicMaterial({
            color: 0x7ee3ff,
            transparent: true,
            opacity: 0.85,
        });
        const transportGlows: THREE.Group[] = [];
        // Outlines waiting on a terrain cell, by cell key: drawn before it lands, one would drape on height 0.
        const transportWaits = new Map<string, (() => void)[]>();
        // Runs `draw` once every cell under `tiles` has its terrain, at once if they all have.
        let bakedTerrain: Set<string> | null = null;
        // A cell the bake left without terrain never lands one, and has no ground to wait for.
        const terrainBaked = (ck: string): boolean => {
            const m = stateRef.current.manifest;
            if (!m) return true;
            bakedTerrain ??= new Set(
                m.cells.filter((c) => c.kinds.includes("terrain")).map((c) => cellKeyOf(c.plane, c.botX0, c.botZ0)),
            );
            return bakedTerrain.has(ck);
        };
        const whenTerrain = (plane: number, tiles: Iterable<{x: number; z: number}>, draw: () => void) => {
            const missing = new Set<string>();
            for (const t of tiles) {
                const ck = cellKeyOf(plane, t.x, t.z);
                if (!terrainReady.has(ck) && terrainBaked(ck)) missing.add(ck);
            }
            if (missing.size === 0) {
                draw();
                return;
            }
            let left = missing.size;
            for (const ck of missing) {
                let waits = transportWaits.get(ck);
                if (!waits) transportWaits.set(ck, (waits = []));
                waits.push(() => {
                    if (--left === 0) draw();
                });
            }
        };
        const addOutline = (geometries: Iterable<{geometry: THREE.BufferGeometry}>) => {
            const group = new THREE.Group();
            for (const {geometry} of geometries) {
                // An edge outline of the model, not a fill, so it marks the object without painting over it.
                const edges = new THREE.EdgesGeometry(geometry, 35);
                geometry.dispose();
                const line = new THREE.LineSegments(edges, transportOutlineMat);
                line.renderOrder = 8;
                group.add(line);
            }
            group.userData.noPick = true;
            transportGlows.push(group);
            scene.add(freezeTransform(group));
        };
        // A transport's anchor object, or the wall it crosses, is outlined in cyan; hovering it lights the
        // entry tiles and raises one label for every transport it carries, each row saying where it goes.
        const transportByAnchor = new Map<string, TransportGroup>();
        const rebuildTransportGlows = () => {
            for (const g of transportGlows) {
                scene.remove(g);
                g.traverse((m) => {
                    if (m instanceof THREE.Mesh || m instanceof THREE.Line)
                        (m.geometry as THREE.BufferGeometry).dispose();
                });
            }
            transportGlows.length = 0;
            transportWaits.clear();
            transportByAnchor.clear();
            transportByWall.clear();
            const manifest = stateRef.current.manifest;
            const shown = propsRef.current.layers?.transports !== false;
            if (!shown || !objLib || !manifest) return;
            const plane = kindsFor(stateRef.current.floor).plane;
            for (const t of propsRef.current.transports ?? []) {
                if (t.boundary && doorDefs) {
                    const wk = `${t.boundary.x},${t.boundary.z}`;
                    let g = transportByWall.get(wk);
                    if (!g) {
                        const title = outlineWall(t.boundary, plane, manifest);
                        if (title != null) {
                            g = {
                                key: `w:${wk}`,
                                title,
                                at: t.boundary,
                                rows: [],
                                entries: [],
                                footprint: new Set([wk]),
                            };
                            transportByWall.set(wk, g);
                        }
                    }
                    if (g) addToGroup(g, t);
                }
                const anchor = t.anchor;
                if (!anchor) continue;
                const objs = sceneryByTile.get(`${plane}:${anchor.x},${anchor.z}`);
                const o = objs?.find((oo) => oo.ax === anchor.x && oo.az === anchor.z) ?? objs?.[0];
                if (!o) continue;
                const ak = `${o.ax},${o.az}`;
                const known = transportByAnchor.get(ak);
                if (known) {
                    addToGroup(known, t);
                    continue;
                }
                const lib = objLib.objects.get(o.id);
                const turned = o.dir !== 0 && o.dir !== 4;
                const fw = lib ? (turned ? lib.h : lib.w) : 1;
                const fh = lib ? (turned ? lib.w : lib.h) : 1;
                const footprint = new Set<string>();
                for (let dx = 0; dx < fw; dx++) {
                    for (let dz = 0; dz < fh; dz++) footprint.add(`${o.ax + dx},${o.az + dz}`);
                }
                const g: TransportGroup = {
                    key: `a:${ak}`,
                    title: o.name,
                    at: {x: o.ax, z: o.az},
                    rows: [],
                    entries: [],
                    footprint,
                };
                transportByAnchor.set(ak, g);
                addToGroup(g, t);
                const objects = objLib;
                const tiles = [...footprint].map((k) => {
                    const [x, z] = k.split(",").map(Number);
                    return {x, z};
                });
                whenTerrain(plane, tiles, () => {
                    addOutline(
                        assembleCell(
                            objects,
                            [{id: o.id, dir: o.dir, x: o.ax, z: o.az}],
                            manifest.botXTiles * 128,
                            heightAtOf(plane),
                        ),
                    );
                });
            }
        };
        const transportByWall = new Map<string, TransportGroup>();
        // Outlines the wall a transport crosses: the one on its own edge, or every wall of the tile where the
        // edge is unknown.
        const outlineWall = (
            at: {x: number; z: number; dir: number},
            plane: number,
            manifest: WorldMeshManifest,
        ): string | null => {
            const walls = (doorsByCell.get(cellKeyOf(plane, at.x, at.z)) ?? []).filter(
                (b) => b.x === at.x && b.z === at.z && (at.dir < 0 || b.dir === at.dir),
            );
            if (walls.length === 0 || !doorDefs) return null;
            const defs = doorDefs;
            whenTerrain(plane, [at], () => {
                const planeHeightAt = heightAtOf(plane);
                const corner = (cx: number, cz: number) => planeHeightAt(cx - 0.5, cz - 0.5);
                addOutline(assembleDoors(defs, walls, manifest.botXTiles * 128, corner));
            });
            return defs.get(walls[0].id)?.name ?? "wall";
        };
        let lastTransports: unknown;
        let lastTransportsDoors = -1;
        let lastTransportsShown: boolean | undefined;
        let lastTransportsFloor: unknown;
        let lastTransportsLib: ObjectLibrary | null = null;
        let lastTransportsSceneryCount = -1;
        let hoverGroup: TransportGroup | null = null;
        let hoverRow: number | null = null;
        let hoverLeftAt = 0;
        // The label outlives the hover a moment: the pick falls through gaps in a model, and the way to the
        // label crosses whatever stands between.
        const TRANSPORT_HOVER_GRACE_MS = 450;
        let transportGen = 0;
        const clearTransportHover = () => {
            hoverGroup = null;
            hoverRow = null;
            hoverLeftAt = 0;
        };
        const entryRibbon = new Ribbon(scene, 0x7ee3ff, 0.9, 0.25);
        let lastEntryKey = "";
        let lastEntryHeightsRev = -1;
        const entryKey = () => (hoverGroup ? `${transportGen}:${hoverGroup.key}#${hoverRow ?? ""}` : "");
        const rebuildEntryOutline = (toWorld: (x: number, z: number) => THREE.Vector3) => {
            const g = hoverGroup;
            const entries = !g ? [] : ((hoverRow != null ? g.rows[hoverRow]?.entries : undefined) ?? g.entries);
            const chains: {pts: {x: number; z: number}[]; closed: boolean}[] = [];
            for (const en of entries) {
                chains.push({
                    closed: true,
                    pts: [
                        {x: en.x - 0.5, z: en.z - 0.5},
                        {x: en.x + 0.5, z: en.z - 0.5},
                        {x: en.x + 0.5, z: en.z + 0.5},
                        {x: en.x - 0.5, z: en.z + 0.5},
                    ],
                });
            }
            entryRibbon.extrude(chains, toWorld);
        };
        const transportChipTag = new TagPool(entityHost, {
            css:
                "font:10px monospace;color:#7ee3ff;background:rgba(10,24,32,.88);" +
                "padding:3px 7px;border:1px solid rgba(126,227,255,.5);border-radius:4px;" +
                "line-height:1.35;text-align:left;cursor:default;",
            band: "chrome",
            offsetY: 24,
            interactive: true,
            dataset: {transport: "1"},
        });
        const buildTransportLabel = (div: HTMLDivElement, g: TransportGroup) => {
            const head = document.createElement("div");
            head.textContent = g.title;
            head.style.cssText = "color:#c9d1d9;font-weight:bold;padding-bottom:2px;";
            div.append(head);
            g.rows.forEach((r, i) => {
                const row = document.createElement("div");
                row.dataset.transportRow = String(i);
                row.style.cssText =
                    "padding:2px 4px;margin:0 -4px;cursor:pointer;" +
                    "border-top:1px solid rgba(126,227,255,.18);border-radius:3px;";
                const name = document.createElement("div");
                name.textContent = `⇋ ${r.name}`;
                const dest = document.createElement("div");
                dest.textContent = r.perEntry
                    ? "→ straight from each entry tile"
                    : // A per-entry crossing lands each entry on its own tile, so it names the rule, not one landing; the
                      // landing prints absolute z, like every coordinate this view shows.
                      `→ ${r.dx}, ${r.dz + r.dfloor * 944}`;
                dest.style.textDecoration = "underline";
                const req = document.createElement("div");
                req.textContent = r.req ? `needs ${r.req}` : "no requirements";
                req.style.cssText = `color:${r.req ? "#e3b341" : "#8b949e"};` + "white-space:normal;max-width:320px;";
                row.append(name, dest, req);
                // No AbortSignal: the tag pool replaces the label's children before each rebuild, so these rows go
                // with their listeners; tying them to the view's signal would keep every row ever hovered alive.
                // eslint-disable-next-line @eslint-react/web-api-no-leaked-event-listener -- see above
                row.addEventListener("pointerenter", () => {
                    hoverRow = i;
                    row.style.background = "rgba(126,227,255,.14)";
                });
                // eslint-disable-next-line @eslint-react/web-api-no-leaked-event-listener -- see above
                row.addEventListener("pointerleave", () => {
                    if (hoverRow === i) hoverRow = null;
                    row.style.background = "";
                });
                div.append(row);
            });
        };
        const labelHovered = () => transportChipTag.get("chip")?.matches(":hover") === true;
        const transportRowAt = (target: EventTarget | null): TransportRow | null => {
            const el = target instanceof Element ? target : null;
            if (!hoverGroup || !el?.closest("[data-transport]")) return null;
            const row = el.closest<HTMLElement>("[data-transport-row]");
            if (row) return hoverGroup.rows[Number(row.dataset.transportRow)] ?? null;
            return hoverGroup.rows.length === 1 ? hoverGroup.rows[0] : null;
        };
        const frameTransportChip = (now: number, view: ViewSize, toWorld: (x: number, z: number) => THREE.Vector3) => {
            if (hoverGroup && hoverLeftAt !== 0) {
                if (labelHovered()) hoverLeftAt = 0;
                else if (now - hoverLeftAt > TRANSPORT_HOVER_GRACE_MS) clearTransportHover();
            }
            const g = hoverGroup;
            transportChipTag.begin(view, camera);
            if (g) {
                transportChipTag.put("chip", toWorld(g.at.x, g.at.z), {
                    stamp: `${transportGen}:${g.key}`,
                    build: (d) => buildTransportLabel(d, g),
                });
            }
            transportChipTag.end();
        };

        // Searches a few tiles back, so a footprint larger than a tile matches.
        const objectAtTile = (plane: number, x: number, z: number): Target => {
            for (let dx = 0; dx >= -4; dx--) {
                for (let dz = 0; dz >= -4; dz--) {
                    const pl = staticByTile.get(tileKeyOf(plane, x + dx, z + dz));
                    if (!pl) continue;
                    const id = overrides.get(tileKeyOf(plane, pl.x, pl.z)) ?? pl.id;
                    const obj = objLib?.objects.get(id);
                    if (!obj) continue;
                    const w = pl.dir === 0 || pl.dir === 4 ? obj.w : obj.h;
                    const h = pl.dir === 0 || pl.dir === 4 ? obj.h : obj.w;
                    if (x >= pl.x && x < pl.x + w && z >= pl.z && z < pl.z + h) {
                        return {type: "object", id, name: obj.name, ax: pl.x, az: pl.z, dir: pl.dir, w, h};
                    }
                }
            }
            return null;
        };

        const segDist = (px: number, pz: number, x1: number, z1: number, x2: number, z2: number) => {
            const dx = x2 - x1;
            const dz = z2 - z1;
            const len2 = dx * dx + dz * dz;
            const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len2));
            return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
        };

        // The boundary edge nearest a ground point, within 0.3 tiles. Corner c sits at c - 0.5 between tile
        // centres; dir 0 is the edge of constant z, 1 of constant x, 2 and 3 the diagonals.
        const wallAtPoint = (plane: number, fx: number, fz: number): Target => {
            if (!doorDefs) return null;
            const tx = Math.floor(fx + 0.5);
            const tz = Math.floor(fz + 0.5);
            let best: Target = null;
            let bestD = 0.3;
            for (let dx = -1; dx <= 1; dx++) {
                for (let dz = -1; dz <= 1; dz++) {
                    const bx = tx + dx;
                    const bz = tz + dz;
                    for (let dir = 0; dir < 4; dir++) {
                        const ek = edgeKeyOf(plane, bx, bz, dir);
                        const id = doorOverrides.get(ek) ?? staticDoorByEdge.get(ek)?.id;
                        if (id == null) continue;
                        const def = doorDefs.get(id);
                        if (!def || def.height <= 0) continue;
                        const x1 = bx - 0.5;
                        const z1 = bz - 0.5;
                        const [ex1, ez1, ex2, ez2] =
                            dir === 0
                                ? [x1, z1, x1 + 1, z1]
                                : dir === 1
                                  ? [x1, z1, x1, z1 + 1]
                                  : dir === 2
                                    ? [x1 + 1, z1, x1, z1 + 1]
                                    : [x1, z1, x1 + 1, z1 + 1];
                        const d = segDist(fx, fz, ex1, ez1, ex2, ez2);
                        if (d < bestD) {
                            bestD = d;
                            best = {type: "wall", id, name: def.name, x: bx, z: bz, dir};
                        }
                    }
                }
            }
            return best;
        };

        // The object or boundary drawn at the cursor, from the shared pick: frontmost, and correct through
        // cut-outs, so a tall gate answers across its whole silhouette.
        const idPick = (clientX: number, clientY: number): Target => {
            if (!objLib) return null;
            if (!ensurePickAt(clientX, clientY)) return null;
            const a = idBuf[3];
            // Only the marker nibble is a hit: 0 is sky, 255 terrain.
            if (a < 8 || a > 15) return null;
            const val = idBuf[0] | (idBuf[1] << 8) | (idBuf[2] << 16) | (a << 24);
            const x = (val >> 12) & 4095;
            const z = val & 4095;
            const plane = kindsFor(stateRef.current.floor).plane;
            if (((val >> 26) & 1) === 1) {
                const dir = (val >> 24) & 3;
                const ek = edgeKeyOf(plane, x, z, dir);
                const id = doorOverrides.get(ek) ?? staticDoorByEdge.get(ek)?.id;
                const def = id != null ? doorDefs?.get(id) : undefined;
                return def && id != null ? {type: "wall", id, name: def.name, x, z, dir} : null;
            }
            return objectAtTile(plane, x, z);
        };

        // Everything under the cursor, one candidate per layer: the menu offers them all, hover and selection
        // take the first by fixed precedence. A bot under the cursor always wins; other entities compete with
        // objects by depth.
        interface Layers {
            bot: Entity3D | null;
            ent: Entity3D | null;
            ground: GroundItem3D[];
            wall: Target;
            obj: Target;
            tile: {x: number; z: number} | null;
            entDepth: number | null;
            objDepth: number | null;
        }
        const resolveLayers = (clientX: number, clientY: number, stableTile = false): Layers | null => {
            const manifest = stateRef.current.manifest;
            if (!manifest) return null;
            const W = manifest.botXTiles * 128;
            const camPos = camera.position;
            const viewDir = new THREE.Vector3(
                -Math.cos(pitch) * Math.sin(yaw),
                -Math.sin(pitch),
                -Math.cos(pitch) * Math.cos(yaw),
            );
            const depthOf = (wx: number, wy: number, wz: number) =>
                (wx - camPos.x) * viewDir.x + (wy - camPos.y) * viewDir.y + (wz - camPos.z) * viewDir.z;
            let bestBot: Entity3D | null = null;
            let bestBotDepth = Infinity;
            let bestEnt: Entity3D | null = null;
            let bestEntDepth = Infinity;
            {
                const r = canvasRect;
                if (r.width && r.height) {
                    const px = clientX - r.left;
                    const py = clientY - r.top;
                    const pxPerUnit = r.width / (camera.right - camera.left);
                    for (const e of entityLayer.current()) {
                        const wx = W - (e.x * 128 + 64);
                        const wyFeet = heightAt(e.x, e.z);
                        const wz = e.z * 128 + 64;
                        const feet = new THREE.Vector3(wx, wyFeet, wz).project(camera);
                        const dims = e.kind === "npc" && e.npcId != null ? npcSprites.dims(e.npcId) : null;
                        const wUnits = dims ? Math.max(56, dims.w) : 90;
                        const hUnits = dims ? Math.max(70, dims.h) : 230;
                        const fighting = e.dir === 8 || e.dir === 9;
                        const shift = e.dir === 8 ? -30 : e.dir === 9 ? 30 : 0;
                        const halfW = (wUnits / 2 + (fighting ? 15 : 0)) * pxPerUnit;
                        const height = hUnits * pxPerUnit;
                        const sx = ((feet.x + 1) / 2) * r.width + shift * pxPerUnit;
                        const sy = ((1 - feet.y) / 2) * r.height;
                        const plateH = dims ? dims.h : e.appearance ? 220 : 130;
                        // The box reaches up to the nameplate, drawn 12 px above the sprite, so head and plate leave no
                        // gap.
                        const top = Math.min(sy - height, sy - plateH * pxPerUnit - 12);
                        const bottom = sy + 10;
                        const cy = (top + bottom) / 2;
                        const ry = (bottom - top) / 2;
                        const nx = (px - sx) / halfW;
                        const ny = (py - cy) / ry;
                        if (nx * nx + ny * ny > 1) continue;
                        // Depth at the cursor's height up the upright billboard.
                        const frac = Math.max(0, Math.min(1, (sy - py) / height));
                        const depth = depthOf(wx, wyFeet + frac * hUnits, wz);
                        if (e.kind === "bot") {
                            if (depth < bestBotDepth) {
                                bestBotDepth = depth;
                                bestBot = e;
                            }
                        } else if (depth < bestEntDepth) {
                            bestEntDepth = depth;
                            bestEnt = e;
                        }
                    }
                }
            }
            const p = surfacePoint(clientX, clientY) ?? groundPoint(clientX, clientY);
            if (!p) {
                return {
                    bot: bestBot,
                    ent: bestEnt,
                    ground: [],
                    wall: null,
                    obj: null,
                    tile: null,
                    entDepth: null,
                    objDepth: null,
                };
            }
            const objDepth = depthOf(p.x, p.y, p.z);
            const fx = (W - p.x) / 128 - 0.5;
            const fz = p.z / 128 - 0.5;
            // A click on an entity's ground ring selects it too. The radius stays under a tile, so objects on the
            // next tile keep their clicks.
            if (!bestBot && !bestEnt) {
                const ring = entityLayer.nearestEntity(fx, fz, 0.6);
                if (ring) {
                    if (ring.kind === "bot") bestBot = ring;
                    else {
                        bestEnt = ring;
                        bestEntDepth = depthOf(W - (ring.x * 128 + 64), heightAt(ring.x, ring.z), ring.z * 128 + 64);
                    }
                }
            }
            const stick = (f: number, prev: number | null) => {
                const r = Math.floor(f + 0.5);
                if (!stableTile || prev === null) return r;
                return f >= prev - 0.5 - HOVER_TILE_HYST && f <= prev + 0.5 + HOVER_TILE_HYST ? prev : r;
            };
            const tx = stick(fx, stableTile ? hoverTileX : null);
            const tz = stick(fz, stableTile ? hoverTileZ : null);
            if (stableTile) {
                hoverTileX = tx;
                hoverTileZ = tz;
            }
            // Every item within 0.7 tiles, nearest first, so a pile offers all of its items.
            const ground: {g: GroundItem3D; d: number}[] = [];
            for (const g of stateRef.current.groundItems) {
                const d = Math.hypot(g.x - fx, g.z - fz);
                if (d < 0.7) ground.push({g, d});
            }
            ground.sort((a, b) => a.d - b.d);
            const plane = kindsFor(stateRef.current.floor).plane;
            const idHit = idPick(clientX, clientY);
            let wall: Target = null;
            let obj: Target = null;
            if (idHit) {
                if (idHit.type === "wall") wall = idHit;
                else obj = idHit;
            } else if (!objLib) {
                // No id pixels until the object library loads: fall back to proximity.
                wall = wallAtPoint(plane, fx, fz);
                obj = objectAtTile(plane, tx, tz);
            } else {
                // The id pick covers every anchor it can pack, so a miss means no object is drawn under the cursor and
                // the tile stands, even beneath one. Only anchors past the 12-bit range fall back to the footprint.
                const fb = objectAtTile(plane, tx, tz);
                if (fb?.type === "object" && (fb.ax >= 4096 || fb.az >= 4096)) obj = fb;
            }
            return {
                bot: bestBot,
                ent: bestEnt,
                ground: ground.map((e) => e.g),
                wall,
                obj,
                tile: {x: tx, z: tz},
                entDepth: bestEnt ? bestEntDepth : null,
                objDepth,
            };
        };

        window.__w3dResolve = (x: number, y: number) => resolveTarget(x, y);
        const resolveTarget = (clientX: number, clientY: number, stableTile = false): Target => {
            const L = resolveLayers(clientX, clientY, stableTile);
            if (!L) {
                hoverGround = null;
                return null;
            }
            const hoverPlane2 = kindsFor(stateRef.current.floor).plane;
            const mf = stateRef.current.manifest;
            hoverGround =
                L.tile && mf
                    ? {
                          x: L.tile.x,
                          z: L.tile.z + hoverPlane2 * 944,
                          floor: hoverPlane2,
                          world: new THREE.Vector3(
                              mf.botXTiles * 128 - (L.tile.x * 128 + 64),
                              heightAt(L.tile.x, L.tile.z),
                              L.tile.z * 128 + 64,
                          ),
                      }
                    : null;
            if (L.bot) return {type: "entity", ...L.bot};
            const objTgt: Target = L.wall ?? L.obj;
            if (L.ent) {
                if (objTgt && L.objDepth != null && L.entDepth != null && L.objDepth < L.entDepth) return objTgt;
                return {type: "entity", ...L.ent};
            }
            if (L.ground.length > 0) {
                const g = L.ground[0];
                return {type: "grounditem", id: g.id, name: g.name, x: g.x, z: g.z};
            }
            if (objTgt) return objTgt;
            return L.tile ? {type: "tile", x: L.tile.x, z: L.tile.z} : null;
        };

        const applyHover = (tgt: Target) => {
            // Every overlay drawn this frame hears the hover, whatever the target: an entity standing in a zone
            // does not stop the zone highlighting.
            overlayHost.hover(hoverGround, syncedOverlays);
            entityLayer.hoverKey = tgt?.type === "entity" ? tgt.key : null;
            npcSprites.highlightKey = tgt?.type === "entity" && tgt.kind === "npc" ? tgt.key : null;
            playerSprites.highlightKey =
                tgt?.type === "entity" && (tgt.kind === "bot" || tgt.kind === "player") ? tgt.key : null;
            const hit =
                tgt?.type === "object"
                    ? transportByAnchor.get(`${tgt.ax},${tgt.az}`)
                    : tgt?.type === "wall"
                      ? transportByWall.get(`${tgt.x},${tgt.z}`)
                      : undefined;
            if (hit) {
                if (hit !== hoverGroup) hoverRow = null;
                hoverGroup = hit;
                hoverLeftAt = 0;
            } else if (hoverGroup) {
                const gp = hoverGround;
                const plane = kindsFor(stateRef.current.floor).plane;
                const onFootprint = gp?.floor === plane && hoverGroup.footprint.has(`${gp.x},${gp.z - plane * 944}`);
                // The transport's footprint and its label hold the hover; anywhere else starts the grace.
                if (onFootprint || labelHovered()) hoverLeftAt = 0;
                else if (hoverLeftAt === 0) hoverLeftAt = performance.now();
            }
            if (tgt?.type === "object") {
                setSceneryGlow(tgt);
                const tiles = [];
                for (let dx = 0; dx < tgt.w; dx++) {
                    for (let dz = 0; dz < tgt.h; dz++) {
                        tiles.push({x: tgt.ax + dx, z: tgt.az + dz});
                    }
                }
                setTileHighlights(tiles);
            } else {
                setSceneryGlow(null);
                if (tgt?.type === "entity" || tgt?.type === "grounditem") {
                    setTileHighlights([{x: Math.round(tgt.x), z: Math.round(tgt.z)}]);
                } else if (tgt?.type === "wall") {
                    setTileHighlights([{x: tgt.x, z: tgt.z}]);
                } else if (tgt?.type === "tile") {
                    setTileHighlights([{x: tgt.x, z: tgt.z}]);
                } else {
                    setTileHighlights([]);
                }
            }
            // A grip drag keeps its handle's cursor: with the pointer captured by the host, the handle's own CSS
            // no longer applies.
            el.style.cursor = areaGrip
                ? areaGripCursor
                : tgt?.type === "entity" || tgt?.type === "object" || tgt?.type === "wall"
                  ? "pointer"
                  : "";
        };

        // Keyed by texture, translucency and frame. The pick pass switches every material in this map, so
        // each variant lives in it.
        const materials = new Map<string, THREE.RawShaderMaterial>();
        const texLoader = new THREE.TextureLoader();
        // The ground plane's corner heights as the FLATTEN shader reads them: whole numbers, a corner not yet
        // streamed as 0, plus 0.5 where the tile splits along its main diagonal. Uploaded at most every 250 ms
        // while the plane streams in.
        const groundHeightsTex = new THREE.DataTexture(
            new Float32Array(HGRID * HGRID),
            HGRID,
            HGRID,
            THREE.RedFormat,
            THREE.FloatType,
        );
        groundHeightsTex.minFilter = THREE.NearestFilter;
        groundHeightsTex.magFilter = THREE.NearestFilter;
        groundHeightsTex.needsUpdate = true;
        let groundTexRev = -1;
        let groundTexAt = -Infinity;
        const refreshGroundHeights = (now: number) => {
            if (heightsRev === groundTexRev || now - groundTexAt < 250) return;
            groundTexRev = heightsRev;
            groundTexAt = now;
            const src = heightsFor(0);
            const splits = splitsFor(0);
            const dst = groundHeightsTex.image.data as Float32Array;
            for (let i = 0; i < src.length; i++) {
                dst[i] = (Number.isNaN(src[i]) ? 0 : Math.round(src[i])) + (splits[i] === 1 ? 0.5 : 0);
            }
            groundHeightsTex.needsUpdate = true;
        };
        // Frame 1 draws the ground beneath an upper floor, flattened into that floor's frame (FLATTEN); it
        // shares the frame-0 material's texture and scroll uniforms. Front faces only: the bake emits each
        // visible side of a face as its own triangle, wound and shaded for that side.
        const materialFor = (tex: number, baked: number, translucent = false, frame: 0 | 1 = 0) => {
            const key = `${tex}|${translucent ? 1 : 0}${frame ? "|flat" : ""}`;
            let m = materials.get(key);
            if (m) return m;
            const base = frame ? materialFor(tex, baked, translucent) : null;
            const uniforms: Record<string, THREE.IUniform> = base
                ? {
                      map: base.uniforms.map,
                      textured: base.uniforms.textured,
                      vScroll: base.uniforms.vScroll,
                      pickMode: {value: 0},
                      groundHeights: {value: groundHeightsTex},
                      worldWidth: {value: (stateRef.current.manifest?.botXTiles ?? 0) * 128},
                  }
                : {
                      map: {value: null},
                      textured: {value: tex >= 0},
                      vScroll: {value: 0},
                      pickMode: {value: 0},
                  };
            if (!base && tex >= 0) {
                const t = texLoader.load(worldMeshTextureUrl(tex, baked));
                t.magFilter = THREE.NearestFilter;
                t.minFilter = THREE.NearestFilter;
                t.wrapS = THREE.RepeatWrapping;
                t.wrapT = THREE.RepeatWrapping;
                t.flipY = false;
                uniforms.map.value = t;
            }
            m = new THREE.RawShaderMaterial({
                glslVersion: THREE.GLSL3,
                vertexShader: VERT,
                fragmentShader: FRAG,
                uniforms,
                side: THREE.FrontSide,
                ...(frame ? {defines: {FLATTEN: 1}} : {}),
            });
            m.userData.desc = {tex, baked, translucent};
            if (translucent) {
                m.transparent = true;
                m.depthWrite = false;
                // The client's translucent scanline adds the face's colour to half the background: src + dst / 2,
                // not an alpha blend. https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L96
                m.blending = THREE.CustomBlending;
                m.blendEquation = THREE.AddEquation;
                m.blendSrc = THREE.OneFactor;
                m.blendDst = THREE.ConstantColorFactor;
                m.blendColor = new THREE.Color(0.5, 0.5, 0.5);
            }
            // What the pick pass restores after forcing NoBlending and depth writes.
            m.userData.blending = m.blending;
            m.userData.depthWrite = m.depthWrite;
            materials.set(key, m);
            return m;
        };

        const WATER_TEX = 17;
        window.__w3dAnim = () => ({
            cells: animMeshes.size,
            anims: objLib ? objLib.anims.size : -1,
            visible: [...animMeshes.values()].map((fs) => fs.map((f) => (f.visible ? 1 : 0)).join("")),
        });
        window.__w3dSel = () => ({
            selectedEntityKey: entityLayer.selectedEntityKey,
            hoverKey: entityLayer.hoverKey,
            propSelected: propsRef.current.selectedBot ?? null,
        });
        window.__w3dWindmills = () => {
            const out: {plane: number; visible: boolean; quat: number[]}[] = [];
            for (const [, mills] of windmillMeshes) {
                for (const {group} of mills) {
                    out.push({
                        plane: group.userData.plane as number,
                        visible: group.visible,
                        quat: [group.quaternion.x, group.quaternion.y, group.quaternion.z, group.quaternion.w],
                    });
                }
            }
            return out;
        };
        window.__w3dPlanes = () => {
            let wrongVisible = 0;
            let retained = 0;
            const byPlane: Record<string, {groups: number; visible: number; ys: number[]; drawnYs: number[]}> = {};
            const check = (g: THREE.Group) => {
                if (!planeShown(g.userData.plane as number, g.userData.kind as string)) {
                    retained++;
                    if (g.visible) wrongVisible++;
                }
                const k = `p${g.userData.plane as number}:${g.userData.kind as string}`;
                const row = (byPlane[k] ??= {groups: 0, visible: 0, ys: [], drawnYs: []});
                row.groups++;
                if (g.visible) row.visible++;
                if (!row.ys.includes(g.position.y)) row.ys.push(g.position.y);
                const drawn = g.children.at(0)?.matrixWorld.elements[13];
                if (drawn !== undefined && !row.drawnYs.includes(drawn)) row.drawnYs.push(drawn);
            };
            for (const [, g] of sceneryMeshes) check(g);
            for (const [, g] of doorMeshes) check(g);
            for (const [, fs] of animMeshes) fs.forEach(check);
            for (const [, g] of loaded) check(g);
            const m = stateRef.current.manifest;
            const tx = m ? (m.botXTiles * 128 - target.x) / 128 : 0;
            const tz = target.z / 128;
            const heightsAtTarget = [0, 1, 2, 3].map((p) => heightAtOf(p)(tx, tz));
            return {
                activePlane,
                retained,
                wrongVisible,
                culled: culledCells,
                loaded: loaded.size,
                scenery: sceneryMeshes.size,
                byPlane,
                heightsAtTarget,
            };
        };
        // Terrain-kind cells by key. The whole plane is fetched, nearest the camera target first, and stays
        // resident: another floor's cells are hidden, not dropped, so a revisit is instant.
        const loaded = new Map<string, THREE.Group>();
        const inflight = new Set<string>();
        let queue: {cell: WorldMeshCell; kind: string; key: string; d: number}[] = [];
        let queuedFloor: FloorKey | null = null;
        let queuedBelow: boolean | null = null;
        const CONCURRENCY = 24;

        let pumpTimer: ReturnType<typeof setTimeout> | undefined;
        let reportedPending = -1;
        const pump = () => {
            const st = stateRef.current;
            const manifest = st.manifest;
            if (!manifest) return;
            const worldWidthUnits = manifest.botXTiles * 128;
            // The floor asked for loads while the shown one stays up.
            const {plane} = kindsFor(st.wantFloor);

            if (queuedFloor !== st.wantFloor || queuedBelow !== st.below) {
                queuedFloor = st.wantFloor;
                queuedBelow = st.below;
                const botX = (worldWidthUnits - target.x) / 128;
                const botZ = target.z / 128;
                const stacked = st.below && (plane === 1 || plane === 2);
                const planes = stacked ? Array.from({length: plane + 1}, (_, p) => p) : [plane];
                queue = [];
                for (const p of planes) {
                    const kinds = kindsFor(floorKeyForIndex(p)).kinds;
                    for (const cell of manifest.cells) {
                        if (cell.plane !== p) continue;
                        for (const kind of kinds) {
                            if (!cell.kinds.includes(kind)) continue;
                            const key = `p${cell.plane}_${cell.a}_${cell.b}_${kind}`;
                            if (loaded.has(key) || inflight.has(key)) continue;
                            const cx = cell.botX0 + manifest.cellTiles / 2;
                            const cz = cell.botZ0 + manifest.cellTiles / 2;
                            queue.push({cell, kind, key, d: Math.hypot(cx - botX, cz - botZ)});
                        }
                    }
                }
                queue.sort((x, y) => x.d - y.d);
            }

            while (inflight.size < CONCURRENCY && queue.length > 0) {
                const next = queue.shift();
                if (!next) break;
                const {cell, kind, key} = next;
                if (loaded.has(key) || inflight.has(key)) continue;
                inflight.add(key);
                fetch(worldMeshCellUrl(cell, kind, manifest.baked))
                    .then((r) => {
                        if (!r.ok) throw new Error(`${r.status}`);
                        return r.arrayBuffer();
                    })
                    .then((buf) => {
                        const group = new THREE.Group();
                        group.userData.kind = kind;
                        group.userData.plane = cell.plane;
                        const grid = heightsFor(cell.plane);
                        const splits = splitsFor(cell.plane);
                        const sink =
                            kind === "terrain"
                                ? (cx: number, cz: number, y: number) => sinkInto(grid, cx, cz, y)
                                : undefined;
                        const splitSink =
                            kind === "terrain"
                                ? (tx: number, tz: number, main: boolean) => {
                                      if (tx >= 0 && tx < HGRID && tz >= 0 && tz < HGRID) {
                                          splits[tz * HGRID + tx] = main ? 1 : 0;
                                      }
                                  }
                                : undefined;
                        for (const {tex, geometry} of parseCell(
                            buf,
                            worldWidthUnits,
                            cell.botX0,
                            cell.botZ0,
                            sink,
                            splitSink,
                        )) {
                            group.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
                        }
                        group.visible = cell.plane === activePlane;
                        loaded.set(key, group);
                        scene.add(freezeTransform(group));
                        if (kind === "terrain") {
                            heightsRev++;
                            heightsChangedAt = performance.now();
                            const ck = cellKeyOf(cell.plane, cell.botX0, cell.botZ0);
                            terrainReady.add(ck);
                            dirtyCells.add(ck);
                            const waits = transportWaits.get(ck);
                            transportWaits.delete(ck);
                            for (const w of waits ?? []) w();
                        }
                    })
                    .catch(() => {
                        /* transient — floor revisit re-queues */
                    })
                    .finally(() => {
                        inflight.delete(key);
                        // Chained at once, so loading is not paced by the frame rate.
                        if (queue.length > 0) pumpTimer = setTimeout(pump, 0);
                    });
            }

            // Overrides are exactly the observations that differ from the static placements: one no longer among
            // them reverts to the static object. Each change dirties only its own cell.
            if (st.observedRev !== lastObservedRev && objLib) {
                lastObservedRev = st.observedRev;
                const dirtyKey = (k: string) => {
                    const [p0, rest] = [k.split(":")[0], k.split(":")[1]];
                    const [x, z] = rest.split(",").map(Number);
                    dirtyCells.add(cellKeyOf(parseInt(p0, 10), x, z));
                };
                if (doorDefs) {
                    const freshDoors = new Map<string, number>();
                    for (const d of st.observedDoors) {
                        const ek = edgeKeyOf(d.plane, d.x, d.z, d.dir);
                        const stat = staticDoorByEdge.get(ek);
                        if (stat?.id !== d.id) {
                            freshDoors.set(ek, d.id);
                        }
                    }
                    for (const [ek, id] of freshDoors) {
                        if (doorOverrides.get(ek) !== id) {
                            doorOverrides.set(ek, id);
                            dirtyKey(ek);
                        }
                    }
                    for (const ek of [...doorOverrides.keys()]) {
                        if (!freshDoors.has(ek)) {
                            doorOverrides.delete(ek);
                            dirtyKey(ek);
                        }
                    }
                }
                const fresh = new Map<string, number>();
                for (const o of st.observed) {
                    const tk = tileKeyOf(o.plane, o.x, o.z);
                    const stat = staticByTile.get(tk);
                    if (stat?.id !== o.id) {
                        fresh.set(tk, o.id);
                    }
                }
                for (const [tk, id] of fresh) {
                    if (overrides.get(tk) !== id) {
                        overrides.set(tk, id);
                        dirtyKey(tk);
                    }
                }
                for (const tk of [...overrides.keys()]) {
                    if (!fresh.has(tk)) {
                        overrides.delete(tk);
                        dirtyKey(tk);
                    }
                }
            }
            if (objLib) {
                // A few dirty cells per pump, each only once its terrain is in, so scenery sits on real ground.
                let n = 0;
                for (const key of dirtyCells) {
                    if (n++ >= 8) break;
                    dirtyCells.delete(key);
                    if (terrainReady.has(key)) {
                        buildSceneryCell(key);
                        buildDoorCell(key);
                    }
                }
            }
            syncUrl();
            const total = loaded.size + inflight.size + queue.length;
            setStatus(inflight.size + queue.length > 0 ? `loading ${loaded.size}/${total} cells…` : "");
            const pending = inflight.size + queue.length + dirtyCells.size;
            if (pending !== reportedPending) {
                reportedPending = pending;
                propsRef.current.onStreamPending?.(pending);
            }
        };

        let disposed = false;
        let objLibLoading = false;
        // The library resolves null until it is baked; the retry interval below calls this again.
        const loadObjLib = (baked: number) => {
            if (objLib || objLibLoading) return;
            objLibLoading = true;
            void ObjectLibrary.fetch(baked).then((lib) => {
                objLibLoading = false;
                if (disposed || !lib) return;
                objLib = lib;
                for (const key of terrainReady) dirtyCells.add(key);
            });
        };
        fetchWorldMeshManifest()
            .then((m) => {
                if (disposed) return;
                stateRef.current.manifest = m;
                loadObjLib(m.baked);
                loadDoorData();
                loadNpcAtlas();
                loadItemAtlas();
                const worldWidthUnits = m.botXTiles * 128;
                worldMaxUnits = Math.max(m.botXTiles, m.botZTiles) * 128;
                const f = stateRef.current.focusBot;
                const fx = num(q.get("x")) ?? f.x;
                const fz = num(q.get("z")) ?? f.z;
                const tx = worldWidthUnits - fx * 128;
                const tz = fz * 128;
                target.set(tx, 0, tz);
                applyCamera();
                setStatus("");
                pump();
            })
            .catch((e: unknown) =>
                setStatus(`world mesh not ready (${e instanceof Error ? e.message : String(e)}) — retrying…`),
            );
        const retry = setInterval(() => {
            if (!stateRef.current.manifest) {
                fetchWorldMeshManifest()
                    .then((m) => {
                        stateRef.current.manifest = m;
                        setStatus("");
                        pump();
                    })
                    .catch(() => {
                        // Not baked yet: the next tick of this interval asks again.
                    });
            } else {
                if (!objLib) loadObjLib(stateRef.current.manifest.baked);
                loadDoorData();
                loadNpcAtlas();
                loadItemAtlas();
                loadChatFont();
            }
        }, 5000);

        // The tour is a closed loop: waypoint i leads to i + 1 mod N, in the arriving waypoint's `sec`. Yaw is
        // unwrapped by each leg's shortest turn, so the heading joins smoothly across the wrap.
        const flyPts = FLYBY.map((w) => ({...w}));
        const flyN = flyPts.length;
        const flyDyaw: number[] = [];
        for (let i = 0; i < flyN; i++) {
            let d = flyPts[(i + 1) % flyN].yaw - flyPts[i].yaw;
            d = ((((d + 180) % 360) + 360) % 360) - 180;
            flyDyaw.push(d);
        }
        const flyYawWind = flyDyaw.reduce((a, b) => a + b, 0);
        const flyYawCum = [flyPts[0].yaw];
        for (let i = 0; i < flyN; i++) flyYawCum.push(flyYawCum[i] + flyDyaw[i]);
        const flyYawU = (k: number): number => {
            const turns = Math.floor(k / flyN);
            const r = ((k % flyN) + flyN) % flyN;
            return flyYawCum[r] + turns * flyYawWind;
        };
        const flySeg: number[] = [];
        for (let i = 0; i < flyN; i++) flySeg.push(flyPts[(i + 1) % flyN].sec ?? 6);
        const flyT: number[] = [0];
        for (let i = 0; i < flyN; i++) flyT.push(flyT[i] + flySeg[i]);
        const flyTotal = flyT[flyN];
        const flyDtAround = (k: number) =>
            flySeg[((k % flyN) + flyN) % flyN] + flySeg[(((k - 1) % flyN) + flyN) % flyN];
        // Constant speed along a Catmull-Rom spline with time-based tangents over cyclic neighbours: position
        // and velocity stay continuous across every seam, the wrap included, so the loop never stalls.
        const flyAt = (tSec: number) => {
            const manifest = stateRef.current.manifest;
            if (!manifest) return;
            const tt = flyTotal > 0 ? ((tSec % flyTotal) + flyTotal) % flyTotal : 0;
            let i = 0;
            while (i < flyN - 1 && tt >= flyT[i + 1]) i++;
            const u = (tt - flyT[i]) / Math.max(1e-6, flySeg[i]);
            const P = (k: number) => flyPts[((k % flyN) + flyN) % flyN];
            const comp = (get: (w: FlyPoint) => number) => {
                const m0 = (get(P(i + 1)) - get(P(i - 1))) / flyDtAround(i);
                const m1 = (get(P(i + 2)) - get(P(i))) / flyDtAround(i + 1);
                return hermite(get(P(i)), m0, get(P(i + 1)), m1, flySeg[i], u);
            };
            const yawM0 = (flyYawU(i + 1) - flyYawU(i - 1)) / flyDtAround(i);
            const yawM1 = (flyYawU(i + 2) - flyYawU(i)) / flyDtAround(i + 1);
            const yawDeg = hermite(flyYawU(i), yawM0, flyYawU(i + 1), yawM1, flySeg[i], u);
            const worldWidthUnits = manifest.botXTiles * 128;
            target.set(worldWidthUnits - comp((w) => w.x) * 128, 0, comp((w) => w.z) * 128);
            yaw = (yawDeg * Math.PI) / 180;
            pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, (comp((w) => w.pitch) * Math.PI) / 180));
            viewHeightUnits = Math.exp(comp((w) => Math.log(w.zoom * 128)));
            resize();
            applyCamera();
        };

        let lastStream = 0;
        let followPrevT = 0;
        let pendingSnap = false;
        // Followed by name: a player who relogs under a new server index stays the same target, while a new
        // name snaps instead of easing across the map.
        let followTarget: string | null = null;
        const shotScratch = new THREE.Vector3();
        // Eases the camera towards the shot that frames `framing.also` around `at`. The fit is taken along the
        // screen's own axes, so it is exact at any yaw and pitch; the first frame (dt 0) cuts to it.
        const frameShot = (
            at: THREE.Vector3,
            framing: NonNullable<World3DViewProps["framing"]>,
            dt: number,
            toWorld: (x: number, z: number, out?: THREE.Vector3) => THREE.Vector3,
        ) => {
            const {right, up} = screenBasis();
            const entities = entityLayer.current();
            let x0 = 0;
            let x1 = 0;
            let y0 = 0;
            let y1 = 0;
            for (const name of framing.also) {
                const e = followedEntity(entities, name);
                if (!e) continue;
                const d = toWorld(e.x, e.z, shotScratch).sub(at);
                if (framing.reachTiles != null && Math.hypot(d.x, d.z) > framing.reachTiles * 128) continue;
                const sx = d.dot(right);
                const sy = d.dot(up);
                x0 = Math.min(x0, sx);
                x1 = Math.max(x1, sx);
                y0 = Math.min(y0, sy);
                y1 = Math.max(y1, sy);
            }
            // Room for a body around every point, more above its feet than below.
            const pad = 2 * 128;
            x0 -= pad;
            x1 += pad;
            y0 -= pad;
            y1 += 2.5 * pad;
            const toward = framing.toward ?? 0.5;
            const cx = ((x0 + x1) / 2) * toward;
            const cy = ((y0 + y1) / 2) * toward;
            const need = 2 * Math.max(y1 - cy, cy - y0, Math.max(x1 - cx, cx - x0) / aspect);
            const want = Math.min(framing.maxTiles * 128, Math.max(framing.minTiles * 128, need));
            // A group too wide for the frame is cut, never the followed entity: the offset is bounded by the
            // narrower of the frame now and the one eased towards, so the pan never outruns the zoom.
            const shown = Math.min(want, viewHeightUnits);
            const halfW = Math.max(0, (shown * aspect) / 2 - pad);
            const ox = Math.max(-halfW, Math.min(halfW, cx));
            const oy = Math.max(Math.min(0, 2.5 * pad - shown / 2), Math.min(Math.max(0, shown / 2 - pad), cy));
            // A ground step away from the camera rises sin(pitch) of itself on screen, and the target sits at
            // height 0 while the followed entity stands at at.y.
            const ahead = (oy + Math.cos(pitch) * at.y) / Math.max(0.05, Math.sin(pitch));
            const gx = at.x + right.x * ox - Math.sin(yaw) * ahead;
            const gz = at.z + right.z * ox - Math.cos(yaw) * ahead;
            const rate = framing.rate ?? 1;
            const kPan = dt > 0 ? 1 - Math.exp(-dt / ((framing.easeS ?? 0.35) * rate)) : 1;
            const kZoom = dt > 0 ? 1 - Math.exp(-dt / ((framing.easeS ?? 0.8) * rate)) : 1;
            target.x += (gx - target.x) * kPan;
            target.y = 0;
            target.z += (gz - target.z) * kPan;
            viewHeightUnits *= Math.pow(want / viewHeightUnits, kZoom);
            fitFrustum();
        };
        const cullFrustum = new THREE.Frustum();
        const cullViewProj = new THREE.Matrix4();
        const spriteViewProjM = new THREE.Matrix4();
        const cullViewInv = new THREE.Matrix4();
        // A group's box from its children's geometry, baked in world space; cached on the group. Null, for a
        // group without geometry, shows it rather than hide it on a guess.
        const cellBox = (g: THREE.Object3D): THREE.Box3 | null => {
            const cached = g.userData.cullBox as THREE.Box3 | null | undefined;
            if (cached !== undefined) return cached;
            let box: THREE.Box3 | null = null;
            for (const child of g.children) {
                const geo = (child as {geometry?: THREE.BufferGeometry}).geometry;
                if (!geo) continue;
                if (!geo.boundingBox) geo.computeBoundingBox();
                if (!geo.boundingBox) continue;
                box = box ? box.union(geo.boundingBox) : geo.boundingBox.clone();
            }
            g.userData.cullBox = box;
            return box;
        };
        const cullBoxScratch = new THREE.Box3();
        const inView = (g: THREE.Object3D): boolean => {
            const box = cellBox(g);
            if (box === null) return true;
            const frame = g.userData.frame as number | undefined;
            if (g.position.y === 0 && !frame) return cullFrustum.intersectsBox(box);
            cullBoxScratch.copy(box);
            if (frame) {
                // Flattened, a vertex keeps only its height over the ground beneath: at most the box's own height.
                cullBoxScratch.max.y = box.max.y - box.min.y + 128;
                cullBoxScratch.min.y = -128;
            }
            cullBoxScratch.min.y += g.position.y;
            cullBoxScratch.max.y += g.position.y;
            return cullFrustum.intersectsBox(cullBoxScratch);
        };
        // Only the ground has elevation to give up, and only while it is drawn beneath an upper floor.
        const frameOf = (plane: number): 0 | 1 => (plane === 0 && dropOf(plane) > 0 ? 1 : 0);
        // Moves a group to where its plane is drawn: as baked, or a storey lower per floor between it and the
        // active one. Groups are frozen and the frame's one scene walk has run, so a move re-bakes the
        // matrices here.
        const place = (g: THREE.Object3D) => {
            const plane = g.userData.plane as number;
            const drop = dropOf(plane);
            const frame = frameOf(plane);
            // A windmill pivot turns about its hub, so the shader cannot flatten it by position: it moves rigidly
            // by the ground under the hub, and its matrix is live.
            if (g.userData.baseY !== undefined) {
                const m = stateRef.current.manifest;
                const ground =
                    frame && m
                        ? heightAtOf(0)((m.botXTiles * 128 - g.position.x) / 128 - 0.5, g.position.z / 128 - 0.5)
                        : 0;
                g.position.y = g.userData.baseY - ground - drop;
                return;
            }
            const y = 0 - drop;
            if (g.position.y !== y) {
                g.position.y = y;
                g.updateMatrix();
                g.updateMatrixWorld(true);
            }
            if ((g.userData.frame ?? 0) !== frame) {
                g.userData.frame = frame;
                for (const child of g.children) {
                    const mesh = child as THREE.Mesh;
                    const d = (mesh.material as THREE.Material).userData.desc as
                        {tex: number; baked: number; translucent: boolean} | undefined;
                    if (!d) continue;
                    mesh.material = materialFor(d.tex, d.baked, d.translucent, frame);
                    // three culls by the sphere where the mesh was baked; flattened, it is drawn elsewhere, so the cell
                    // test stands alone.
                    mesh.frustumCulled = frame === 0;
                }
            }
        };
        let culledCells = 0;
        // Decides every cell group's visibility each frame: plane, roofs, animation frame and viewport. A
        // hidden group spares three the per-mesh culling of its whole subtree, and a cell panned back into
        // view shows on the next frame.
        const cullCells = (t: number) => {
            // The camera is outside the scene graph, so the frame's scene walk has not refreshed it.
            camera.updateMatrixWorld();
            cullViewInv.copy(camera.matrixWorld).invert();
            cullViewProj.multiplyMatrices(camera.projectionMatrix, cullViewInv);
            cullFrustum.setFromProjectionMatrix(cullViewProj);
            const roofs = stateRef.current.roofs;
            if (stackedBelow()) refreshGroundHeights(t);
            culledCells = 0;
            for (const [, g] of loaded) {
                const eligible = groupShown(g) && !(ROOF_KINDS.has(g.userData.kind as string) && !roofs);
                if (eligible) place(g);
                g.visible = eligible && inView(g);
                if (eligible && !g.visible) culledCells++;
            }
            for (const map of [sceneryMeshes, doorMeshes]) {
                for (const [, g] of map) {
                    const eligible = groupShown(g);
                    if (eligible) place(g);
                    g.visible = eligible && inView(g);
                    if (eligible && !g.visible) culledCells++;
                }
            }
            for (const [, frames] of animMeshes) {
                if (frames.length === 0) continue;
                const onPlane = groupShown(frames[0]);
                // The client steps animated scenery every sixth 20 ms frame. https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L12310
                const live = Math.floor(t / 120) % frames.length;
                for (let f = 0; f < frames.length; f++) {
                    if (onPlane) place(frames[f]);
                    frames[f].visible = onPlane && f === live && inView(frames[f]);
                }
            }
            for (const [, mills] of windmillMeshes) {
                for (const {group} of mills) {
                    group.visible = groupShown(group);
                    if (group.visible) place(group);
                }
            }
        };

        // A floor asked for is shown once each of its cells in view has its ground, walls and scenery and the
        // host's transports for it are in, or after FLOOR_HOLD_MS with whatever has landed. A shown floor never
        // loaded is not worth holding on.
        const FLOOR_HOLD_MS = 1500;
        let holdFloor: FloorKey | null = null;
        let holdSince = 0;
        let holdWorth = false;
        const holdBox = new THREE.Box3();
        const floorReady = (want: FloorKey, t: number): boolean => {
            if (want !== holdFloor) {
                holdFloor = want;
                holdSince = t;
                holdWorth = false;
                for (const [, g] of loaded) {
                    if (g.userData.plane === activePlane) {
                        holdWorth = true;
                        break;
                    }
                }
            }
            const st = stateRef.current;
            const manifest = st.manifest;
            if (!manifest || !holdWorth || t - holdSince >= FLOOR_HOLD_MS) return true;
            const {plane} = kindsFor(want);
            const stacked = st.below && (plane === 1 || plane === 2);
            const worldWidthUnits = manifest.botXTiles * 128;
            const span = manifest.cellTiles * 128;
            const missing = new Set<string>();
            let ready = propsRef.current.transports !== null;
            for (const cell of manifest.cells) {
                if (cell.plane !== plane && !(stacked && cell.plane < plane)) continue;
                const kinds = kindsFor(floorKeyForIndex(cell.plane)).kinds;
                const xMax = worldWidthUnits - cell.botX0 * 128;
                holdBox.min.set(xMax - span, -1024, cell.botZ0 * 128);
                holdBox.max.set(xMax, 2048, cell.botZ0 * 128 + span);
                if (!cullFrustum.intersectsBox(holdBox)) continue;
                for (const kind of kinds) {
                    if (!cell.kinds.includes(kind) || (ROOF_KINDS.has(kind) && !st.roofs)) continue;
                    const key = `p${cell.plane}_${cell.a}_${cell.b}_${kind}`;
                    if (!loaded.has(key)) missing.add(key);
                }
                if (objLib && dirtyCells.has(cellKeyOf(cell.plane, cell.botX0, cell.botZ0))) ready = false;
            }
            // The queue was sorted from where the camera stood when the floor was asked for.
            if (missing.size > 0) queue.sort((x, y) => Number(missing.has(y.key)) - Number(missing.has(x.key)));
            return ready && missing.size === 0;
        };
        let loopFloor = stateRef.current.floor;

        let raf = 0;
        const loop = (t: number) => {
            raf = requestAnimationFrame(loop);
            // Hidden: cells and the WebGL context stay resident for an instant return, but no render or
            // streaming work is spent, and the first visible frame renders at once, on the floor asked for.
            if (propsRef.current.visible === false || !stateRef.current.manifest) {
                const s0 = stateRef.current;
                holdFloor = null;
                if (s0.wantFloor !== s0.floor) setShownFloorIndex(floorIndexForKey(s0.wantFloor));
                if (propsRef.current.visible === false) return;
            }
            readLayout();
            const st = stateRef.current;
            const fl = flightRef.current;
            if (fl) {
                const tSec = (t - fl.start) / 1000;
                flyAt(tSec);
                if (tSec >= flyTotal) {
                    flightRef.current = null;
                    setFlying(false);
                }
            }
            if (st.manifest) {
                if (st.wantFloor !== queuedFloor || st.below !== queuedBelow) pump();
                if (st.wantFloor === st.floor) holdFloor = null;
                else if (floorReady(st.wantFloor, t)) setShownFloorIndex(floorIndexForKey(st.wantFloor));
                // The render that shows a floor assembles its entities, so its cells show on the frame they do.
                const floorSwapped = st.floor !== loopFloor;
                if (floorSwapped) {
                    loopFloor = st.floor;
                    activePlane = kindsFor(st.floor).plane;
                    // Every draped layer redrapes on the floor now shown.
                    heightsRev++;
                }
                sightLayer.visible = st.sight;
                watchedSight.visible = st.sight;
                entityLayer.showPlates = st.tags;
                entityLayer.plateFor = propsRef.current.nameplates ?? null;
                if (snapNextRef.current) {
                    snapNextRef.current = false;
                    pendingSnap = true;
                }
                const sightDirty = st.entitiesRev !== lastEntitiesRev;
                if (sightDirty) {
                    lastEntitiesRev = st.entitiesRev;
                    // Scrubbing snaps every update; steady playback lerps, replaying the motion as it happened.
                    const snap = propsRef.current.scrubbing === true || pendingSnap;
                    entityLayer.update(st.entities, t, snap);
                    if (pendingSnap) {
                        pendingSnap = false;
                        followPrevT = 0;
                    }
                }
                const worldWidthUnits = st.manifest.botXTiles * 128;
                // Plane-local z. `out` spares a caller placing one label per entity a fresh vector each call.
                const toWorld = (x: number, z: number, out?: THREE.Vector3) =>
                    (out ?? new THREE.Vector3()).set(worldWidthUnits - (x * 128 + 64), heightAt(x, z), z * 128 + 64);
                // Sight is drawn around live bots only: an offline bot sees nothing now.
                if (sightDirty) {
                    sightLayer.update(
                        st.entities.filter((e) => e.kind === "bot" && !e.offline),
                        toWorld,
                    );
                }
                // A floor is shown with its ground in view in, so its overlays drape at once.
                const heightsQuiet =
                    floorSwapped ||
                    t - heightsChangedAt >= HEIGHTS_SETTLE_MS ||
                    t - heightsRebuildAt >= HEIGHTS_MAX_STALE_MS;
                if (heightsQuiet) heightsRebuildAt = t;
                // The outline drapes over the terrain, so it redrapes once streamed heights settle.
                if (!sightDirty && heightsQuiet && heightsRev !== lastSightHeightsRev) {
                    sightLayer.update(
                        st.entities.filter((e) => e.kind === "bot" && !e.offline),
                        toWorld,
                    );
                }
                if (heightsQuiet || sightDirty) lastSightHeightsRev = heightsRev;
                const watched = followedEntity(st.entities, propsRef.current.watchedSight);
                const watchedKey = watched ? `${watched.x},${watched.z},${lastSightHeightsRev}` : "";
                if (watchedKey !== watchedAt) {
                    watchedAt = watchedKey;
                    watchedSight.update(watched, toWorld);
                }

                // The prop is the box. A sweep or grip drag in flight wins; when it ends the prop takes over again,
                // kept if the embedder committed it, gone if not.
                const ownedRect = propsRef.current.areaRect ?? null;
                if (!areaDrag && !areaGrip && !sameArea(ownedRect, areaRect)) {
                    areaRect = ownedRect ? {...ownedRect} : null;
                    areaRectDirty = true;
                }
                if (areaRectDirty || (heightsQuiet && heightsRev !== lastAreaHeightsRev)) {
                    areaRectDirty = false;
                    lastAreaHeightsRev = heightsRev;
                    rebuildAreaRect(toWorld);
                }
                frameAreaGrips(toWorld);
                framePinnedMenu(toWorld);
                const tagView = {w: hostW, h: hostH};
                frameRespawnTags(kindsFor(st.floor).plane, st.tags, viewHeightUnits / 128, tagView, toWorld);
                entityLayer.lerpMs = propsRef.current.tickMs ?? 640;
                if (heightsQuiet && heightsRev !== settledHeightsRev) settledHeightsRev = heightsRev;
                const plane = kindsFor(st.floor).plane;
                // The view's own draped layers go through the same overlay interface as an embedder's.
                syncedOverlays = [
                    ...(propsRef.current.layers?.dangerZones ? [overlay("danger", dangerZones, null)] : []),
                    overlay("route", routeOverlay, st.route),
                    overlay("plan", planOverlay, st.plan),
                    overlay("trail", trailOverlay, st.trail),
                    ...(st.clusters ? [overlay("clusters", clusterGrid, clustersOf(st.manifest))] : []),
                    ...(st.components || st.reachable ? [overlay("components", walkingComponents, st.reachable)] : []),
                    ...(propsRef.current.overlays ?? []),
                ];
                overlayHost.sync(syncedOverlays, {
                    floor: plane,
                    heightsRev: settledHeightsRev,
                    zoomTiles: viewHeightUnits / 128,
                    view: tagView,
                    // Overlays work in absolute z, the form their data arrives in.
                    toWorld: (x, zAbs, out) => toWorld(x, zAbs - plane * 944, out),
                    npcAt: (serverIndex) => {
                        const p = entityLayer.at(`npc:${serverIndex}`, t);
                        return p && {x: p.x, z: p.z + plane * 944, id: p.npcId};
                    },
                });
                {
                    const shown = propsRef.current.layers?.wilderness !== false;
                    wildernessLayer.visible = shown;
                    const stride = wildernessStrideFor(hostH / (viewHeightUnits / 128));
                    if (
                        shown &&
                        (stride !== lastWildStride ||
                            st.floor !== lastWildFloor ||
                            (heightsQuiet && heightsRev !== lastWildHeightsRev))
                    ) {
                        lastWildStride = stride;
                        lastWildFloor = st.floor;
                        lastWildHeightsRev = heightsRev;
                        wildernessLayer.rebuild(stride, toWorld);
                    }
                    wildernessLayer.frameLabels(shown, camera, hostW, hostH, toWorld);
                }
                {
                    const shown = propsRef.current.layers?.transports !== false;
                    // While a floor loads, the prop already holds its transports and the old floor's stay up.
                    if (
                        st.wantFloor === st.floor &&
                        (propsRef.current.transports !== lastTransports ||
                            shown !== lastTransportsShown ||
                            st.floor !== lastTransportsFloor ||
                            doorsByCell.size !== lastTransportsDoors ||
                            sceneryByTile.size !== lastTransportsSceneryCount ||
                            objLib !== lastTransportsLib)
                    ) {
                        lastTransports = propsRef.current.transports;
                        lastTransportsDoors = doorsByCell.size;
                        lastTransportsShown = shown;
                        lastTransportsFloor = st.floor;
                        lastTransportsSceneryCount = sceneryByTile.size;
                        lastTransportsLib = objLib;
                        clearTransportHover();
                        transportGen++;
                        rebuildTransportGlows();
                    }
                }
                frameTransportChip(t, tagView, toWorld);
                if (entryKey() !== lastEntryKey || (heightsQuiet && heightsRev !== lastEntryHeightsRev)) {
                    lastEntryKey = entryKey();
                    lastEntryHeightsRev = heightsRev;
                    rebuildEntryOutline(toWorld);
                }
                // The client scrolls the water texture a row per frame, https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L12308
                // and a frame is 20 ms, so its 64 rows loop in 1280 ms. https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L397
                const water = materials.get(`${WATER_TEX}|0`);
                if (water) {
                    water.uniforms.vScroll.value = -((t / 1280) % 1);
                }
                const tile = propsRef.current.followTile;
                const held = tile ? {x: tile.x, z: tile.z % 944} : null;
                const fol = tile ? `${tile.x},${tile.z}` : propsRef.current.follow;
                if (fol && !flightRef.current) {
                    if (fol !== followTarget) {
                        followTarget = fol;
                        // A new name cuts; a held tile eases there, unless it is far off.
                        if (!held) followPrevT = 0;
                    }
                    const b = held ?? followedEntity(entityLayer.current(), fol);
                    if (b) {
                        const wp = toWorld(b.x, b.z);
                        // Cut, rather than ease, to an entity first seen, seen again, or gone far off at once (a
                        // teleport, a ladder): easing would sweep the camera across the map.
                        const far = Math.hypot(wp.x - target.x, wp.z - target.z) > FOLLOW_CUT_TILES * 128;
                        const framing = propsRef.current.framing;
                        // A stall counts as a tenth of a second shown, so the camera never jumps after one.
                        const maxDt = 0.1 * (framing?.rate ?? 1);
                        const dt = followPrevT && !far ? Math.min(maxDt, (t - followPrevT) / 1000) : 0;
                        if (framing) {
                            frameShot(wp, framing, dt, toWorld);
                        } else {
                            // A frame-rate independent ease (time constant 0.12 s) glides over the pauses between
                            // entity updates; the first follow frame snaps.
                            const k = dt > 0 ? 1 - Math.exp(-dt / 0.12) : 1;
                            target.x += (wp.x - target.x) * k;
                            target.y = 0;
                            target.z += (wp.z - target.z) * k;
                        }
                        applyCamera();
                        followPrevT = t;
                    } else {
                        followPrevT = 0;
                    }
                } else {
                    followPrevT = 0;
                }
                if (projFlightsRef.current.length > 0) {
                    const posByKey = new Map<string, {x: number; z: number}>();
                    const hByKey = new Map<string, number>();
                    for (const c of entityLayer.current()) {
                        posByKey.set(c.key, {x: c.x, z: c.z});
                        const dims = c.kind === "npc" && c.npcId != null ? npcSprites.dims(c.npcId) : null;
                        hByKey.set(c.key, (dims ? dims.h : c.appearance ? 220 : 130) / 2);
                    }
                    projectileLayer.frame(
                        t,
                        projFlightsRef.current,
                        (k) => posByKey.get(k) ?? null,
                        toWorld,
                        (k) => hByKey.get(k) ?? 60,
                    );
                } else {
                    projectileLayer.frame(
                        t,
                        [],
                        () => null,
                        toWorld,
                        () => 60,
                    );
                }
                const millAngle = (t / WINDMILL_PERIOD_MS) * Math.PI * 2;
                for (const [, mills] of windmillMeshes) {
                    for (const {group, axis} of mills) {
                        if (group.visible) {
                            group.quaternion.setFromAxisAngle(axis, millAngle);
                        }
                    }
                }
                entityLayer.frame(t, toWorld, groundNormalAt, camera, hostW, hostH, viewHeightUnits / 128);
                const {right: screenRight, up: camU} = screenBasis();
                const camToward = new THREE.Vector3(
                    Math.cos(pitch) * Math.sin(yaw),
                    Math.sin(pitch),
                    Math.cos(pitch) * Math.cos(yaw),
                );
                const lift = 160 * Math.sin(pitch);
                const viewProj = spriteViewProj(camera, spriteViewProjM);
                npcSprites.frame(
                    t,
                    entityLayer.snapshot("npc", t) as NpcSpriteState[],
                    toWorld,
                    screenRight,
                    camU,
                    yaw,
                    camToward,
                    lift,
                    viewProj,
                );
                playerSprites.frame(
                    t,
                    [...entityLayer.snapshot("bot", t), ...entityLayer.snapshot("player", t)] as PlayerSpriteState[],
                    toWorld,
                    screenRight,
                    camU,
                    yaw,
                    camToward,
                    lift,
                    viewProj,
                );
                groundItems.frame(st.groundItems, toWorld, screenRight, camU, camToward, lift);
            }
            if (t - lastStream > 250) {
                lastStream = t;
                pump();
            }
            // The one world-matrix walk a frame: after everything that moves objects, before both renders.
            scene.updateMatrixWorld();
            // Before both renders: the pick's frustum lies inside the camera's, so a culled cell is never under
            // the cursor.
            cullCells(t);
            consumePick();
            // Resolved at the point the pick was taken, not the latest cursor, so the id and depth match the
            // billboard test.
            if (hoverPendingRead && pickReady) {
                hoverPendingRead = false;
                resolveHover(pickReady.x, pickReady.y);
            }
            if (canvasCursor) {
                const p = kindsFor(st.floor).plane;
                // A floor switch changes what lies under a still cursor and the z the readout prints: spend a pick.
                if (p !== hoverPlane) {
                    hoverPlane = p;
                    hoverWanted = true;
                }
            }
            if (pendingHover) {
                const h = pendingHover;
                pendingHover = null;
                if (h.kind === "canvas") {
                    canvasCursor = {x: h.x, y: h.y};
                    hoverWanted = true;
                } else {
                    canvasCursor = null;
                    hoverWanted = false;
                    if (h.kind === "plate") hoverPlate(h.key);
                    else commitHover(null);
                }
            }
            // While a tool picks tiles the highlight is its cursor, so the pick renders and reads before this
            // frame's render; a frame behind would read as lag.
            if (hoverWanted && canvasCursor && (propsRef.current.pickTool || propsRef.current.walkTool)) {
                hoverWanted = false;
                hoverPendingRead = false;
                if (renderPickAt(canvasCursor.x, canvasCursor.y)) {
                    consumePick();
                    resolveHover(canvasCursor.x, canvasCursor.y);
                }
            }
            renderer.render(scene, camera);
            compass.render();
            if (hoverWanted && canvasCursor) {
                hoverWanted = false;
                if (renderPickAt(canvasCursor.x, canvasCursor.y)) hoverPendingRead = true;
            }
        };

        // Records the flyby as PNG frames exactly 1/fps apart on a virtual clock, into a folder the user
        // picks, then restores the real clock. Call it straight from a click: the folder picker needs the
        // user gesture, so nothing awaits before it.
        async function runCapture(size: {w: number; h: number} | null): Promise<void> {
            if (capturing) return;
            capturing = true;
            cancelAnimationFrame(raf);
            let dir: FileSystemDirectoryHandle;
            try {
                dir = await pickCaptureDir();
            } catch (e) {
                console.error("[capture] no output folder — aborting.", e);
                capturing = false;
                raf = requestAnimationFrame(loop);
                return;
            }
            captureSize = size;
            resize();
            const canvas = renderer.domElement;
            const clock = new VirtualClock(captureFps);
            flightRef.current = {start: clock.now()};
            setFlying(true);
            requestAnimationFrame(loop);
            // One period, [0, flyTotal): the tour is cyclic, so the frame at flyTotal would repeat frame 0.
            const total = Math.max(1, Math.round(flyTotal * captureFps));
            let frame = 0;
            try {
                for (; frame < total; frame++) {
                    clock.tick();
                    await clock.yieldMacrotask();
                    await writeFrame(dir, frame, await canvasPng(canvas));
                }
            } finally {
                flightRef.current = null;
                setFlying(false);
                clock.uninstall();
                capturing = false;
                captureSize = null;
                resize();
                raf = requestAnimationFrame(loop);
            }
            console.info(
                `[capture] wrote ${frame} frames @ ${captureFps}fps. Encode e.g.:\n` +
                    `  ffmpeg -framerate ${captureFps} -i %06d.png -c:v libwebp ` +
                    "-loop 0 -lossless 0 -q:v 75 -fps_mode passthrough flyby.webp",
            );
        }
        startCaptureRef.current = runCapture;

        raf = requestAnimationFrame(loop);
        resize();
        applyCamera();

        return () => {
            disposed = true;
            listening.abort();
            document.removeEventListener("pointerlockchange", onLockChange);
            document.removeEventListener("pointerlockerror", onLockError);
            if (document.pointerLockElement === host) document.exitPointerLock();
            adoptUrlRef.current = null;
            startCaptureRef.current = null;
            for (const key of [...sceneryMeshes.keys(), ...animMeshes.keys(), ...windmillMeshes.keys()]) {
                disposeSceneryCell(key);
            }
            for (const key of [...doorMeshes.keys()]) disposeDoorCell(key);
            sightLayer.dispose();
            watchedSight.dispose();
            wildernessLayer.dispose();
            areaRibbon.dispose();
            projectileLayer.dispose();
            overlayHost.dispose();
            for (const pool of [respawnTags, transportChipTag]) pool.dispose();
            for (const g of transportGlows) {
                scene.remove(g);
                g.traverse((m) => {
                    if (m instanceof THREE.Mesh || m instanceof THREE.Line)
                        (m.geometry as THREE.BufferGeometry).dispose();
                });
            }
            transportOutlineMat.dispose();
            entryRibbon.dispose();
            for (const d of gripDivs.values()) d.remove();
            gripDivs.clear();
            npcSprites.dispose(scene);
            playerSprites.dispose();
            groundItems.dispose(scene);
            entityLayer.dispose(scene);
            entityHost.remove();
            if (urlTimer) clearTimeout(urlTimer);
            clearTimeout(pumpTimer);
            clearInterval(retry);
            cancelAnimationFrame(raf);
            ro.disconnect();
            for (const [, group] of loaded) {
                group.traverse((o) => {
                    if (o instanceof THREE.Mesh) (o.geometry as THREE.BufferGeometry).dispose();
                });
            }
            for (const [, m] of materials) {
                const t = (m.uniforms.map as THREE.IUniform<THREE.Texture | null> | undefined)?.value;
                t?.dispose();
                m.dispose();
            }
            groundHeightsTex.dispose();
            pickRT.dispose();
            compass.dispose();
            renderer.dispose();
            host.removeChild(renderer.domElement);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <div style={{position: "relative", width: "100%", height: "100%", isolation: "isolate"}}>
            {/* Its own stacking context: the view's tiers (tags up to 905000, toolbar and compass 1000000,
                the menu 2000000) stay inside it, so whatever the page floats over the view paints above. */}
            <div ref={hostRef} style={{position: "absolute", inset: 0, overflow: "hidden"}} />
            {(toolbar || status) && (
                <div
                    style={{
                        position: "absolute",
                        top: 8,
                        left: 8,
                        display: "flex",
                        gap: 6,
                        zIndex: 1000000,
                        background: "rgba(16,16,26,.8)",
                        padding: "6px 8px",
                        borderRadius: 6,
                        alignItems: "center",
                        fontSize: 13,
                        userSelect: "none",
                    }}
                >
                    {toolbar &&
                        FLOORS.map((f) => (
                            <button
                                type="button"
                                key={f.key}
                                className={floor === f.key ? "active" : ""}
                                onClick={() => setFloorIndex(FLOOR_KEYS_BY_INDEX.indexOf(f.key))}
                            >
                                {f.label}
                            </button>
                        ))}
                    {toolbar && (
                        <>
                            <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                                <input type="checkbox" checked={roofs} onChange={(e) => setRoofs(e.target.checked)} />
                                roofs
                            </label>
                            <label
                                style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}
                                title="on floor 1 or 2, draw the floors beneath as well, a storey lower each"
                            >
                                <input type="checkbox" checked={below} onChange={(e) => setBelow(e.target.checked)} />
                                floors below
                            </label>
                            {!props.hideSight && (
                                <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                                    <input
                                        type="checkbox"
                                        checked={sight}
                                        onChange={(e) => setSight(e.target.checked)}
                                    />
                                    sight
                                </label>
                            )}
                            {props.togglesAfterRoofs}
                            <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                                <input type="checkbox" checked={tags} onChange={(e) => setTags(e.target.checked)} />
                                tags
                            </label>
                            {props.togglesAfterTags}
                            <label
                                style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}
                                title="the pathfinder's clusters (64×64 tiles) and their ids"
                            >
                                <input
                                    type="checkbox"
                                    checked={clusters}
                                    onChange={(e) => setClusters(e.target.checked)}
                                />
                                clusters
                            </label>
                            <label
                                style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}
                                title="the collision map's walking components: tiles one can walk between share a colour and an id; outlines are where they end"
                            >
                                <input
                                    type="checkbox"
                                    checked={components}
                                    onChange={(e) => setComponents(e.target.checked)}
                                />
                                components
                            </label>
                            {props.extraToggles}
                            {captureFps > 0 && !flying && (
                                <input
                                    type="text"
                                    value={captureRes}
                                    onChange={(e) => setCaptureRes(e.target.value)}
                                    placeholder={`${window.innerWidth}x${window.innerHeight} (blank = window)`}
                                    title="Recording resolution WxH (blank = live canvas size)"
                                    style={{marginLeft: 8, width: 150, padding: "2px 6px", font: "inherit"}}
                                />
                            )}
                            <button
                                type="button"
                                style={{marginLeft: 8}}
                                onClick={() => {
                                    if (captureFps > 0) {
                                        if (flying) return;
                                        let size: {w: number; h: number} | null;
                                        try {
                                            size = parseCaptureSize(captureRes);
                                        } catch (err) {
                                            alert(String(err instanceof Error ? err.message : err));
                                            return;
                                        }
                                        void startCaptureRef.current?.(size);
                                        return;
                                    }
                                    toggleFly();
                                }}
                            >
                                {captureFps > 0
                                    ? flying
                                        ? `● Recording ${captureFps}fps…`
                                        : "● Record flyby"
                                    : flying
                                      ? "Stop"
                                      : "Fly"}
                            </button>
                        </>
                    )}
                    {status && <span style={{marginLeft: 8, opacity: 0.8}}>{status}</span>}
                </div>
            )}
            <div
                ref={compassRef}
                title={`North up, ${HOME_PITCH_DEG}° pitch`}
                onClick={() => homeViewRef.current?.()}
                style={{position: "absolute", top: 6, right: 6, zIndex: 1000000, cursor: "pointer"}}
            />
            {ctxMenu && (
                <div
                    style={{
                        position: "absolute",
                        left: Math.max(0, ctxMenu.x - 4),
                        top: Math.max(0, ctxMenu.y - 8),
                        zIndex: 2000000,
                        ...MENU_STYLE,
                    }}
                    onPointerDown={(e) => e.stopPropagation()}
                >
                    <div style={{color: "#0ff", textShadow: MENU_TEXT_SHADOW}}>Choose option</div>
                    {ctxMenu.entries.map((en, i) => (
                        <div
                            key={i}
                            style={{
                                textShadow: MENU_TEXT_SHADOW,
                                cursor: "pointer",
                                lineHeight: "15px",
                            }}
                            onMouseEnter={(e) => {
                                const v = e.currentTarget.querySelector<HTMLElement>("[data-verb]");
                                if (v) v.style.color = "#ff0";
                            }}
                            onMouseLeave={(e) => {
                                const v = e.currentTarget.querySelector<HTMLElement>("[data-verb]");
                                if (v) v.style.color = "#fff";
                            }}
                            onClick={() => {
                                en.run?.();
                                setCtxMenu(null);
                            }}
                        >
                            <span data-verb style={{color: "#fff"}}>
                                {en.verb}
                            </span>
                            {en.target && <span style={{color: en.tcolor ?? "#fff"}}> {en.target}</span>}
                            {en.suffix && <span style={{color: en.scolor ?? "#fff"}}>{en.suffix}</span>}
                        </div>
                    ))}
                </div>
            )}
            {hover && (
                <div
                    style={{
                        position: "absolute",
                        bottom: 8,
                        right: 8,
                        background: "rgba(16,16,26,.85)",
                        padding: "4px 10px",
                        borderRadius: 6,
                        fontSize: 13,
                        fontFamily: "monospace",
                        pointerEvents: "none",
                    }}
                >
                    {hover}
                </div>
            )}
        </div>
    );
}
