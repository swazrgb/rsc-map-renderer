import {useEffect, useRef, useState, type ReactNode} from "react";
import * as THREE from "three";
import type {Observer, MapEntity, NpcRespawn, ObjectRespawn, NpcSpawnInfo, RoutePoint} from "./api";
import {fetchNpcSpawns, sendInteract, sendWalk} from "./api";
import {fetchWearables} from "./api";
import {VirtualClock, pickCaptureDir, canvasPng, writeFrame} from "./capture";

/** Worn-equipment rows from an appearance token. Each layer holds the server's
 *  appearance-sprite id (0 = nothing); layers 0–2 default to the base
 *  head/body/pants sprites but are REPLACED by large helmets / plate bodies /
 *  plate legs (wornItemIndex = wearSlot on the server). Base sprites are
 *  skipped; everything else reverse-maps to candidate item names. */
const WEAR_SLOTS = ["head", "body", "legs", "shield", "weapon", "hat", "body",
    "legs", "gloves", "boots", "neck", "cape"];
const BASE_SPRITES: Record<number, number[]> = {
    0: [1, 4, 6, 7, 8], // hair styles
    1: [2, 5],          // male/female body
    2: [3],             // coloured pants
};
function wornItems(token: string, wearables: Record<number, string[]> | null):
    {slot: string; names: string}[] {
    const out: {slot: string; names: string}[] = [];
    const layers = token.split("|")[0].split(",").map(Number);
    for (let i = 0; i < layers.length; i++) {
        if (!layers[i] || BASE_SPRITES[i]?.includes(layers[i])) continue;
        const names = wearables?.[layers[i]];
        // Several items can share one sprite (all rune blades look alike) —
        // show the first few candidates rather than the whole family.
        const label = !names?.length ? `sprite ${layers[i]}`
            : names.slice(0, 3).join(" / ")
              + (names.length > 3 ? ` +${names.length - 3} more` : "");
        out.push({slot: WEAR_SLOTS[i] ?? `slot ${i}`, names: label});
    }
    return out;
}
import {Entity3D, EntityLayer} from "./World3DEntities";
import {ObjectLibrary, ResolvedPlacement, assembleCell, sceneryHub} from "./World3DScenery";
import {BoundaryPlacement, DoorDefLite, assembleDoors, fetchDoorData} from "./World3DDoors";
import {NpcSpriteLayer, NpcSpriteState, fetchNpcAtlas} from "./World3DNpcSprites";
import {PlayerSpriteLayer, PlayerSpriteState} from "./World3DPlayerSprites";
import {GroundItemLayer, GroundItem3D, fetchItemAtlas} from "./World3DGroundItems";
import {loadGameFont} from "./World3DChatFont";
import {Ribbon, SightLayer} from "./World3DSight";
import {ProjectileLayer, ProjectileFlight, PROJECTILE_FLIGHT_MS} from "./World3DProjectiles";
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

/**
 * 3D world viewer: streams the engine-baked mesh cells (terrain / walls /
 * roofs / scenery per 96×96-tile region) around the camera and renders them
 * with the engine's own lighting math in the fragment shader.
 *
 * Shading is the verified port of the software rasterizer: per-vertex shade
 * (0..255, side sign baked at export) interpolated across the face, then
 *  - flat fills:  base × ((255−shade)² / 65536)
 *  - textures:    {1, .875, .75, .625}[(shade>>4)&3] / 2^(shade>>6)
 * applied per fragment — the same quantities the engine's scanlines compute.
 *
 * Coordinates: map convention (east = +x right, north = −z up-screen), i.e.
 * the RSC westward +X mirrored, matching the Leaflet map. One three.js unit
 * = 1/128 tile · 128 = 1 engine unit; y up.
 */

const FLOORS = [
    {key: "underground", label: "Underground"},
    {key: "ground", label: "Ground"},
    {key: "floor1", label: "Floor 1"},
    {key: "floor2", label: "Floor 2"}
] as const;
type FloorKey = (typeof FLOORS)[number]["key"];

// The stock client's "show roofs" hides MORE than roofs when you're under
// one (mudclient.c draw loop): the current plane's roofs plus ALL
// upper-storey walls and roofs — that's what opens up building interiors.
const ROOF_KINDS = new Set(["roofs", "roofs1", "roofs2", "walls1", "walls2"]);

// Windmill sails (scenery id 74) spin continuously in the stock client
// (mudclient_animate_objects: pe(1,0,0) per frame). We rotate the pivot on a
// fixed wall-clock period so the speed is frame-rate-independent.
const WINDMILL_ID = 74;
const WINDMILL_PERIOD_MS = 4000; // one full revolution
// Hover tile hysteresis: how far (in tiles) the pick point must cross a tile
// boundary before the hovered tile switches. Wider than the depth-pick noise
// on a vertical wall face, narrower than half a tile so it never feels sticky.
const HOVER_TILE_HYST = 0.18;

/** Which cell plane + kind files each floor view uses. Roof kinds are always
 * loaded; the roofs checkbox flips their visibility. */
function kindsFor(floor: FloorKey): {plane: number; kinds: string[]} {
    // "scenery" is NOT a baked kind: the viewer assembles it client-side
    // from the object library + placements (enables live state overrides).
    switch (floor) {
        case "ground":
            return {plane: 0, kinds: ["terrain", "walls", "walls1", "walls2",
                "roofs", "roofs1", "roofs2"]};
        case "floor1":
            return {plane: 1, kinds: ["terrain", "walls", "roofs"]};
        case "floor2":
            return {plane: 2, kinds: ["terrain", "walls", "roofs"]};
        case "underground":
            return {plane: 3, kinds: ["terrain", "walls"]};
    }
}

// ---------------------------------------------------------------------------
// Fly-by tour: waypoints in URL units (bot tiles, degrees, view-height tiles).
// It's a CLOSED LOOP — the evaluator runs the list cyclically (…→ last → first
// →…) at constant speed, so recording exactly one period gives a seamless
// loop (record from the ?capture button). `sec` = seconds for the leg ARRIVING
// at this point; the first entry's `sec` is therefore the return leg (last →
// first). Edit freely — deliberately hardcoded for recording videos.
type FlyPoint = {x: number; z: number; yaw: number; pitch: number; zoom: number; sec?: number};

const FLYBY: FlyPoint[] = [
    {x: 120, z: 640, yaw: 45, pitch: 89, zoom: 500, sec: 10},  // high over Lumbridge (return leg lands here)
    {x: 120, z: 640, yaw: 45, pitch: 40, zoom: 40, sec: 4},    // dive to the castle (snappy)
    {x: 122, z: 640, yaw: 225, pitch: 35, zoom: 35, sec: 8},   // orbit it
    {x: 215, z: 630, yaw: 270, pitch: 45, zoom: 60, sec: 8},   // glide to Draynor
    {x: 310, z: 570, yaw: 315, pitch: 50, zoom: 80, sec: 8},   // onward north-west
    {x: 300, z: 560, yaw: 405, pitch: 89, zoom: 700, sec: 8},  // pull out to the world, then sweep home
];

/** Recording frame rate from `?capture=<fps>` (0 = not recording). */
function readCaptureFps(): number {
    const v = parseFloat(new URLSearchParams(location.search).get("capture") ?? "");
    return Number.isFinite(v) && v > 0 ? Math.round(v) : 0;
}

/** Parse a "WxH" recording resolution (blank = use the live canvas size).
 *  Returns null for blank; throws on malformed input so the caller can warn. */
function parseCaptureSize(s: string): {w: number; h: number} | null {
    if (!s.trim()) return null;
    const m = s.match(/^\s*(\d+)\s*[x×X]\s*(\d+)\s*$/);
    const w = m ? parseInt(m[1], 10) : 0;
    const h = m ? parseInt(m[2], 10) : 0;
    if (!w || !h) throw new Error(`bad resolution "${s}" — use e.g. 1920x1080`);
    return {w, h};
}

/**
 * Cubic Hermite between p0 (at s=0) and p1 (at s=1) with endpoint velocities
 * m0/m1 given per UNIT TIME and `dt` the segment's duration. Parameterizing by
 * time (not the [0,1] index) is what keeps the flyby C¹ across waypoints whose
 * legs have different durations — the shared endpoint velocity matches on both
 * sides, so no speed lurch. Pair it with Catmull-Rom-style tangents
 * `m_i = (p_{i+1} − p_{i−1}) / (t_{i+1} − t_{i−1})`.
 */
function hermite(p0: number, m0: number, p1: number, m1: number,
    dt: number, s: number): number {
    const s2 = s * s;
    const s3 = s2 * s;
    return (2 * s3 - 3 * s2 + 1) * p0 + (s3 - 2 * s2 + s) * dt * m0
        + (-2 * s3 + 3 * s2) * p1 + (s3 - s2) * dt * m1;
}

const VERT = /* glsl */ `
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
out float vShade;
out vec3 vBase;
out vec2 vUv;
out vec4 vPickId;
void main() {
    vShade = shade;
    vBase = base;
    vUv = uv;
    vPickId = pickId;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
}`;

const FRAG = /* glsl */ `
precision highp float;
uniform sampler2D map;
uniform bool textured;
// Water scroll (client scene_scroll_texture: texture 17 shifts 1px/20ms
// frame): the material's per-frame v offset; 0 for everything else.
uniform float vScroll;
// Pick pass: 0 = normal shading, 1 = emit packed placement id, 2 = emit packed
// window-space depth. BOTH pick outputs happen AFTER the alpha discard, so a
// transparent hole in a doorframe (or any textured cut-out) passes the pick
// through to whatever is visible behind it — the id AND the depth agree with
// the eye, so the object/entity tiebreak, rotate pivot and tile-under-cursor
// all see through holes too.
uniform int pickMode;
in float vShade;
in vec3 vBase;
in vec2 vUv;
in vec4 vPickId;
out vec4 outColor;
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
        if (pickMode == 1) { outColor = vPickId; return; }
        if (pickMode == 2) { outColor = packDepth(); return; }
        float bankIdx = mod(floor(s / 16.0), 4.0);
        float bank = bankIdx < 0.5 ? 1.0 : bankIdx < 1.5 ? 0.875 : bankIdx < 2.5 ? 0.75 : 0.625;
        float f = bank / pow(2.0, floor(s / 64.0));
        outColor = vec4(t.rgb * f, 1.0);
    } else {
        if (pickMode == 1) { outColor = vPickId; return; }
        if (pickMode == 2) { outColor = packDepth(); return; }
        float r = 255.0 - s;
        outColor = vec4(vBase * (r * r / 65536.0), 1.0);
    }
}`;

// GPU object-pick: render scenery + doors through the object material in
// pickMode (see FRAG) so each vertex's packed placement id is emitted as its
// colour — AFTER the material's own alpha discard, so a textured cut-out (a
// doorframe's hole) passes the pick through just like the visual. Read back the
// pixel under the cursor → the exact object drawn there (frontmost, depth- AND
// alpha-correct). Terrain has no `pickId` attribute → GL default (0,0,0,1) →
// alpha byte 255, decoded as "not an object"; real ids keep alpha ≤ 15.
// Packed-id layout (kept < 2^28 so the four bytes round-trip through an RGBA8
// target and 32-bit JS bit ops exactly): z=bits0-11, x=bits12-23, dir=bits24-25,
// type=bit26 (0 scenery, 1 door/boundary), marker=bit27. The marker lands in
// the alpha byte (8..15 for a real hit), distinct from terrain's 255 and the
// cleared sky's 0. x/z are the placement's anchor tile, so decode resolves it
// straight through the existing objectAtTile / door-edge maps — no id registry
// to keep in sync across cell rebuilds.
const ID_MARK = 1 << 27;
const packSceneryId = (x: number, z: number): number =>
    x >= 0 && x < 4096 && z >= 0 && z < 4096 ? (ID_MARK | (x << 12) | z) : 0;
const packDoorId = (x: number, z: number, dir: number): number =>
    x >= 0 && x < 4096 && z >= 0 && z < 4096
        ? (ID_MARK | (1 << 26) | ((dir & 3) << 24) | (x << 12) | z) : 0;

/** Parse one cell kind binary into BufferGeometries grouped by texture id.
 * The binary's origin field is in WORLD tiles (sector space); the viewer
 * works in bot tiles, so the caller passes the cell's bot-space origin. */
function parseCell(buf: ArrayBuffer, worldWidthUnits: number,
                   botX0: number, botZ0: number,
                   heightSink?: (botCornerX: number, botCornerZ: number, y: number) => void):
    {tex: number; geometry: THREE.BufferGeometry}[] {
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
        for (let i = 0; i < n; i++) {
            const rawX = dv.getInt16(off);
            const x = rawX + originX;
            const y = dv.getInt16(off + 2);
            const rawZ = dv.getInt16(off + 4);
            const z = rawZ + originZ;
            off += 6;
            if (heightSink && rawX % 128 === 0 && rawZ % 128 === 0) {
                heightSink(botX0 + rawX / 128, botZ0 + rawZ / 128, -y);
            }
            // Map orientation: mirror engine X (east right); engine y is
            // negative-up, three.js y is positive-up.
            pos[i * 3] = worldWidthUnits - x;
            pos[i * 3 + 1] = -y;
            pos[i * 3 + 2] = z;
        }
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
    // v2 terrain trailer: the UN-FLATTENED corner-elevation grid. RSC flattens
    // bridge/water tiles (tileValue==4) to y=0 in the terrain mesh so the water
    // surface draws under the bridge — but the engine (and rsc-c) place scenery
    // and characters at the un-flattened ground (getElevation → getTileElevation).
    // Driving the height grid from this trailer (overriding the flattened mesh
    // vertices sunk above) sits objects on the bridge deck instead of dropping
    // them into the water. Corners: i = x offset, j = z offset from cell origin.
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

export function World3DView(props: {
    focus?: {x: number; z: number} | null;
    /** Live per-tick view: one {@link Observer} per vantage point (your bots, or
     *  every online player for a whole-server view). The viewer merges,
     *  deduplicates and interpolates them into one world. */
    observers?: Observer[];
    /** Pending NPC respawns (tick-aligned via the SSE `npcRespawns` event) —
     *  rendered as ghost sprites at their spawns with a countdown. */
    npcRespawns?: NpcRespawn[];
    /** Pending scenery respawns (depleted rock, looted chest) — the object
     *  mirror of {@link npcRespawns}; each draws a countdown ghost of the object
     *  it will become. */
    objectRespawns?: ObjectRespawn[];
    selectedBot?: string | null;
    onSelectBot?: (username: string | null) => void;
    // ---- sidebar-consolidated controls (all optional: the standalone
    // harness runs uncontrolled with the in-view toolbar instead) ----
    /** Controlled floor (0=ground,1,2,3=underground — the app's shared floor
     *  state, same selector as the map). */
    floorIndex?: number;
    onFloorIndexChange?: (floor: number) => void;
    /** Controlled overlay toggles; when provided the in-view toolbar hides
     *  (the sidebar renders these instead). */
    overlays?: {roofs: boolean; sight: boolean; tags: boolean};
    onOverlaysChange?: (o: {roofs: boolean; sight: boolean; tags: boolean}) => void;
    /** Username the camera should follow (the sidebar's follow-bot), or null. */
    follow?: string | null;
    /** The selected bot's planned route (absolute tiles, hop = transport
     *  landing) — drawn as a draped ribbon like the map's polyline. */
    route?: RoutePoint[] | null;
    /** Walk/act tool: clicks command the selected bot instead of selecting.
     *  Left click runs the top menu entry by stock priority (walk, object
     *  command-1, Talk-to, Take…); right click opens the "Choose option"
     *  menu with every verb under the cursor. */
    walkTool?: boolean;
    /** Armed "Use <item> with …" mode (inspector inventory Use verb): the
     *  next world click dispatches use-item-on-object/npc/wall/ground. */
    useItem?: {id: number; name: string} | null;
    /** A use-item click was dispatched (or cancelled by a walk) — disarm. */
    onUseItemDone?: () => void;
    /** Bump to toggle the flyby (sidebar Fly button). */
    flyNonce?: number;
    onFlyingChange?: (flying: boolean) => void;
    /** False while another tab is showing: the component stays MOUNTED (so the
     *  WebGL context, baked cell geometry and fetched manifest survive for an
     *  instant switch-back), but the render loop parks itself to stop burning
     *  GPU on a hidden canvas. Defaults to true (the standalone harness). */
    visible?: boolean;
    /** Cross-tab camera sync with the 2D map: a mutable box (game tiles +
     *  zoom-in-tiles) that the active view writes on pan and the newly-revealed
     *  view reads once to re-centre. Replaces the old URL round-trip. */
    sharedView?: {current: {x: number; z: number; zoom: number} | null};
    /** Extra controls appended to the in-view toolbar (uncontrolled mode only)
     *  — e.g. the standalone demo's Simulation toggle. */
    extraToggles?: ReactNode;
    /** Hide the "sight" toggle (uncontrolled mode) — e.g. the demo, which has no
     *  bots to compute line-of-sight for. */
    hideSight?: boolean;
}) {
    const hostRef = useRef<HTMLDivElement | null>(null);
    const initialFloor = (): FloorKey => {
        const f = new URLSearchParams(location.search).get("floor");
        return FLOORS.some(x => x.key === f) ? (f as FloorKey) : "ground";
    };
    const [floorState, setFloorState] = useState<FloorKey>(initialFloor);
    const controlled = props.overlays != null;
    const floor: FloorKey = props.floorIndex != null
        ? FLOORS[Math.max(0, Math.min(3, props.floorIndex))].key : floorState;
    const setFloor = (k: FloorKey) => {
        if (props.onFloorIndexChange) {
            props.onFloorIndexChange(FLOORS.findIndex(f => f.key === k));
        } else {
            setFloorState(k);
        }
    };
    // Controlled mount: the URL's floor wins once (deep links keep working).
    useEffect(() => {
        if (props.floorIndex != null) {
            const k = initialFloor();
            if (k !== FLOORS[props.floorIndex]?.key) {
                props.onFloorIndexChange?.(FLOORS.findIndex(f => f.key === k));
            }
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    const [roofsState, setRoofsState] = useState(
        () => new URLSearchParams(location.search).get("roofs") !== "0");
    const [sightState, setSightState] = useState(
        () => new URLSearchParams(location.search).get("sight") !== "0");
    const [tagsState, setTagsState] = useState(
        () => new URLSearchParams(location.search).get("tags") === "1");
    const roofs = props.overlays?.roofs ?? roofsState;
    const sight = props.overlays?.sight ?? sightState;
    const tags = props.overlays?.tags ?? tagsState;
    const setRoofs = (v: boolean) => props.onOverlaysChange
        ? props.onOverlaysChange({roofs: v, sight, tags}) : setRoofsState(v);
    const setSight = (v: boolean) => props.onOverlaysChange
        ? props.onOverlaysChange({roofs, sight: v, tags}) : setSightState(v);
    const setTags = (v: boolean) => props.onOverlaysChange
        ? props.onOverlaysChange({roofs, sight, tags: v}) : setTagsState(v);
    const [status, setStatus] = useState("loading manifest…");
    const [hover, setHover] = useState<string>("");
    const [picked, setPicked] = useState<{kind: string; name: string | null;
        npcId: number | null; key: string; inCombat: boolean;
        appearance?: string | null} | null>(null);
    // appearance-sprite id → wearable item names, fetched once (null until then).
    const [wearables, setWearables] = useState<Record<number, string[]> | null>(null);
    useEffect(() => {
        fetchWearables().then(setWearables).catch(() => {});
    }, []);
    // Static NPC spawn table, fetched once (retried until the runner is up) —
    // drives the ghost markers for every spawn no bot currently observes.
    const [npcSpawns, setNpcSpawns] = useState<NpcSpawnInfo[]>([]);
    useEffect(() => {
        let stop = false;
        const load = () => fetchNpcSpawns().then(s => {
            if (!stop) setNpcSpawns(s);
        }).catch(() => {
            if (!stop) setTimeout(load, 5000);
        });
        load();
        return () => {
            stop = true;
        };
    }, []);
    const [flying, setFlying] = useState(false);
    const flightRef = useRef<{start: number} | null>(null);
    // Deterministic recording (?capture=<fps>). The Fly button starts it — the
    // folder picker needs a user gesture, so it can't auto-run from the URL. The
    // effect publishes its starter here for the button to call.
    const captureFps = readCaptureFps();
    const startCaptureRef = useRef<((size: {w: number; h: number} | null) => void) | null>(null);
    // Recording resolution field, pre-filled from ?size=WxH (blank = record at
    // the live canvas size). Forced onto the canvas at pixelRatio 1 while
    // recording, so the output is exactly this many pixels.
    const [captureRes, setCaptureRes] = useState(
        () => new URLSearchParams(location.search).get("size") ?? "");
    const projSeen = useRef(new Set<string>());
    const projFlights = useRef<ProjectileFlight[]>([]);
    // Stock right-click "Choose option" menu (walk/act tool): entries carry
    // their dispatch closures; null = closed. verb draws white, target in its
    // stock colour (@yel@ npcs, @cya@ scenery, @lre@ items, @whi@ players),
    // suffix is the level-coloured "(level-N)" tail on attack entries.
    const [ctxMenu, setCtxMenu] = useState<{x: number; y: number;
        entries: {verb: string; target?: string; tcolor?: string;
            suffix?: string; scolor?: string; prio: number;
            run?: () => void}[]} | null>(null);
    const ctxMenuOpen = useRef(false);
    ctxMenuOpen.current = ctxMenu != null;
    // Re-adopt the shared URL coords (x/z/zoom the map tab writes) when this
    // view becomes active again. The view stays mounted across tab switches
    // now, so — like MapView's `active` sync — it must explicitly re-read the
    // URL on reveal instead of relying on a remount. Set inside the setup
    // effect (closure over the camera target); called by the visibility effect.
    const adoptUrlRef = useRef<(() => void) | null>(null);
    useEffect(() => {
        props.onFlyingChange?.(flying);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [flying]);
    // Sidebar Fly button: each bump toggles the flyby.
    const flyNonceSeen = useRef(props.flyNonce ?? 0);
    useEffect(() => {
        if (props.flyNonce == null || props.flyNonce === flyNonceSeen.current) return;
        flyNonceSeen.current = props.flyNonce;
        if (flightRef.current) {
            flightRef.current = null;
            setFlying(false);
        } else {
            flightRef.current = {start: performance.now()};
            setFlying(true);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.flyNonce]);
    // Follow: keep the shared floor on the followed bot's floor.
    useEffect(() => {
        if (!props.follow) return;
        const b = (props.observers ?? []).find(x => x.username === props.follow);
        const f = b?.position?.floor;
        if (f != null && FLOORS[f] && FLOORS[f].key !== floor) {
            setFloor(FLOORS[f].key);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [props.follow, props.observers]);
    // Became the active tab → adopt the shared URL coords the map wrote while
    // we were hidden (the component no longer remounts to re-read them). The
    // 60ms beat lets the container regain real size after display:none→block,
    // matching MapView's UrlViewSync so both directions land on the same spot.
    useEffect(() => {
        if (props.visible === false) return;
        const t = setTimeout(() => adoptUrlRef.current?.(), 60);
        return () => clearTimeout(t);
    }, [props.visible]);
    // Mutable handles the render loop and streamers share.
    const stateRef = useRef<{
        manifest: WorldMeshManifest | null;
        floor: FloorKey;
        roofs: boolean;
        sight: boolean;
        tags: boolean;
        focusBot: {x: number; z: number};
        entities: Entity3D[];
        entitiesRev: number;
        groundItems: GroundItem3D[];
        observed: {plane: number; x: number; z: number; id: number}[];
        observedDoors: {plane: number; x: number; z: number; dir: number; id: number}[];
        observedRev: number;
        respawnGhosts: {plane: number; x: number; z: number; id: number; t: number}[];
        route: RoutePoint[] | null;
    }>({manifest: null, floor: "ground", roofs: true, sight: true, tags: true,
        focusBot: props.focus ?? {x: 120, z: 640}, // Lumbridge default
        entities: [], entitiesRev: 0, groundItems: [],
        observed: [], observedDoors: [], observedRev: 0, respawnGhosts: [],
        route: null});

    const propsRef = useRef(props);
    propsRef.current = props;
    stateRef.current.floor = floor;
    stateRef.current.roofs = roofs;
    stateRef.current.sight = sight;
    stateRef.current.tags = tags;
    stateRef.current.route = props.route ?? null;
    if (props.focus) stateRef.current.focusBot = props.focus;

    // Assemble the live entity set for the ACTIVE floor. MapEntity.z and
    // BotPosition.z are absolute (floor baked in as z + floor*944); rings use
    // floor-local tiles. NPC/player views dedupe across bots by serverIndex.
    {
        const plane = kindsFor(floor).plane;
        const out: Entity3D[] = [];
        const seenNpc = new Map<number, Entity3D>();
        const seenPlayer = new Map<number, Entity3D>();
        // First sighting wins per serverIndex, but a later sighting may carry
        // chat the first viewer missed (it walked into view after the npcsay)
        // — patch the newest line onto the kept entry.
        const patchMsg = (kept: Entity3D, e: MapEntity) => {
            if (e.msg != null && e.msgTick != null
                && (kept.msgTick == null || e.msgTick > kept.msgTick)) {
                kept.msg = e.msg;
                kept.msgTick = e.msgTick;
            }
        };
        // Our own bots appear in each other's in-view player lists — filter
        // them by server index so they don't double-render as "players".
        const ownIndexes = new Set<number>();
        for (const b of props.observers ?? []) {
            if (b.serverIndex != null) ownIndexes.add(b.serverIndex);
        }
        for (const b of props.observers ?? []) {
            const pos = b.position;
            if (pos && pos.floor === plane) {
                out.push({key: `bot:${b.username}`, kind: "bot",
                    x: pos.x, z: pos.z - pos.floor * 944, name: b.username,
                    selected: b.username === props.selectedBot,
                    inCombat: b.inCombat ?? false, appearance: b.appearance,
                    dir: b.dir, hp: b.hits, maxHp: b.maxHits,
                    dmg: b.dmg, dmgTick: b.dmg != null ? b.serverTick : null,
                    msg: b.msg, msgTick: b.msgTick,
                    bubble: b.bubble, bubbleTick: b.bubbleTick,
                    sleeping: b.sleeping});
            }
            for (const n of b.npcs ?? []) {
                if (Math.floor(n.z / 944) !== plane) continue;
                const kept = seenNpc.get(n.serverIndex);
                if (kept) {
                    patchMsg(kept, n);
                    continue;
                }
                const e: Entity3D = {key: `npc:${n.serverIndex}`, kind: "npc",
                    x: n.x, z: n.z % 944, name: n.name, inCombat: n.inCombat,
                    npcId: n.id, dir: n.dir, hp: n.hp, maxHp: n.maxHp,
                    dmg: n.dmg, dmgTick: n.dmgTick,
                    msg: n.msg, msgTick: n.msgTick};
                seenNpc.set(n.serverIndex, e);
                out.push(e);
            }
            for (const pl of b.players ?? []) {
                if (Math.floor(pl.z / 944) !== plane
                    || ownIndexes.has(pl.serverIndex)) continue;
                const kept = seenPlayer.get(pl.serverIndex);
                if (kept) {
                    patchMsg(kept, pl);
                    continue;
                }
                const e: Entity3D = {key: `pl:${pl.serverIndex}`, kind: "player",
                    x: pl.x, z: pl.z % 944, name: pl.name, inCombat: pl.inCombat,
                    appearance: pl.appearance, dir: pl.dir,
                    combatLvl: pl.combatLvl, skulled: pl.skulled,
                    hp: pl.hp, maxHp: pl.maxHp, dmg: pl.dmg, dmgTick: pl.dmgTick,
                    msg: pl.msg, msgTick: pl.msgTick,
                    bubble: pl.bubble, bubbleTick: pl.bubbleTick};
                seenPlayer.set(pl.serverIndex, e);
                out.push(e);
            }
        }
        // Ghost NPCs: every static spawn whose NPC no bot currently sees —
        // unobserved world state, like the sight outline's complement. A live
        // sighting of the same serverIndex always wins. Where the swarm
        // tracked the death, the tick-aligned npcRespawns frame adds the
        // respawn window (and the store clears it on the next live
        // sighting).
        const pendingByIdx = new Map<number, NpcRespawn>();
        for (const g of props.npcRespawns ?? []) {
            pendingByIdx.set(g.serverIndex, g);
        }
        for (const s of npcSpawns) {
            if (Math.floor(s.z / 944) !== plane || seenNpc.has(s.serverIndex)) continue;
            const pend = pendingByIdx.get(s.serverIndex);
            out.push({key: `npc:${s.serverIndex}`, kind: "npc",
                x: s.x, z: s.z % 944, name: s.name, inCombat: false,
                npcId: s.id, ghost: true,
                respawnTicks: pend?.t ?? null,
                respawnTicksMin: pend?.tMin ?? null,
                respawnAssumed: pend?.assumed ?? null,
                respawnCheckedAgo: pend?.checkedAgo ?? null});
        }
        stateRef.current.entities = out;
        stateRef.current.entitiesRev++;

        // Ground items on the active floor, deduped across bots by (id, tile).
        const ground: GroundItem3D[] = [];
        const seenGround = new Set<string>();
        for (const b of props.observers ?? []) {
            for (const g of b.groundItems ?? []) {
                if (Math.floor(g.z / 944) !== plane) continue;
                const k = `${g.id}:${g.x},${g.z}`;
                if (seenGround.has(k)) continue;
                seenGround.add(k);
                ground.push({id: g.id, x: g.x, z: g.z % 944, name: g.name});
            }
        }
        stateRef.current.groundItems = ground;

        // Live-observed scenery (any floor): every object a bot currently
        // sees. The pump diffs these against static placements and rebuilds
        // the affected scenery cells.
        const obs: {plane: number; x: number; z: number; id: number}[] = [];
        const seenObj = new Set<string>();
        for (const b of props.observers ?? []) {
            for (const o of b.objects ?? []) {
                const op = Math.floor(o.z / 944);
                const k = `${op}:${o.x},${o.z % 944}`;
                if (seenObj.has(k)) continue;
                seenObj.add(k);
                obs.push({plane: op, x: o.x, z: o.z % 944, id: o.id});
            }
        }
        stateRef.current.observed = obs;

        const obsDoors: {plane: number; x: number; z: number; dir: number; id: number}[] = [];
        const seenDoor = new Set<string>();
        for (const b of props.observers ?? []) {
            for (const w of b.wallObjects ?? []) {
                if (w.dir == null) continue; // pre-dir runner build
                const wp = Math.floor(w.z / 944);
                const k = `${wp}:${w.x},${w.z % 944},${w.dir}`;
                if (seenDoor.has(k)) continue;
                seenDoor.add(k);
                obsDoors.push({plane: wp, x: w.x, z: w.z % 944, dir: w.dir, id: w.id});
            }
        }
        stateRef.current.observedDoors = obsDoors;
        stateRef.current.observedRev++;

        // Spent scenery with a predicted respawn (depleted rock, looted chest):
        // ghost of the object it will BECOME + countdown — the scenery mirror of
        // the npc respawn ghosts, supplied as its own `objectRespawns` list. The
        // ghost's name is resolved from the object library at render time.
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

        // Projectiles: each (serverTick, sprite, from, to) launches ONE
        // flight — deduped across the bots that co-observed it — animated by
        // the projectile layer for the stock 0.8s.
        {
            const ownIdx = new Map<number, string>();
            for (const b of props.observers ?? []) {
                if (b.serverIndex != null) ownIdx.set(b.serverIndex, b.username);
            }
            const byKey = new Map<string, Entity3D>();
            for (const e of out) byKey.set(e.key, e);
            const now = performance.now();
            for (const b of props.observers ?? []) {
                for (const p of b.projectiles ?? []) {
                    const k = `${b.serverTick}:${p.sprite}:`
                        + `${p.fromNpc ? "n" : "p"}${p.from}:`
                        + `${p.toPlayer ? "p" : "n"}${p.to}`;
                    if (projSeen.current.has(k)) continue;
                    projSeen.current.add(k);
                    const fromKey = p.fromNpc ? `npc:${p.from}`
                        : ownIdx.has(p.from) ? `bot:${ownIdx.get(p.from)}`
                        : `pl:${p.from}`;
                    const toKey = !p.toPlayer ? `npc:${p.to}`
                        : ownIdx.has(p.to) ? `bot:${ownIdx.get(p.to)}`
                        : `pl:${p.to}`;
                    const fe = byKey.get(fromKey);
                    const te = byKey.get(toKey);
                    if (!fe || !te) continue; // endpoint not on this floor
                    projFlights.current.push({key: k, sprite: p.sprite,
                        fromKey, toKey, start: now,
                        fx: fe.x, fz: fe.z, tx: te.x, tz: te.z});
                }
            }
            projFlights.current = projFlights.current.filter(
                f => now - f.start < PROJECTILE_FLIGHT_MS + 200);
            if (projSeen.current.size > 2000) projSeen.current.clear();
        }
    }

    useEffect(() => {
        const host = hostRef.current;
        if (!host) return;

        // ---- Deterministic capture mode (?capture=<fps>) -----------------
        // A screen recorder samples the live canvas in real time, so it beats
        // against the display refresh and captures every hitch under GPU load —
        // that's the choppiness. Instead, when ?capture is set the Fly button
        // drives the WHOLE animation off a virtual clock (see capture.ts): both
        // this render loop AND the demo's NPC-tick loop derive all motion from
        // the rAF timestamp, so faking that clock advances flyby, NPC walks,
        // sprite animation, water and windmills together, one exact 1/fps step
        // per frame. We render each frame as slowly as the machine needs and
        // stream it to disk — perfectly smooth no matter the real frame rate.
        // Wired up just below the loop (runCapture).
        let capturing = false;
        // When set, resize() forces the canvas to exactly these pixels (at
        // pixelRatio 1) instead of tracking the window — the recording size.
        let captureSize: {w: number; h: number} | null = null;

        const renderer = new THREE.WebGLRenderer({
            antialias: true,
            // Frame-grabbing reads the drawing buffer after the draw call; keep
            // it alive for that. Only pay the cost while recording.
            preserveDrawingBuffer: captureFps > 0,
        });
        renderer.setPixelRatio(window.devicePixelRatio);
        host.appendChild(renderer.domElement);
        const scene = new THREE.Scene();
        scene.background = new THREE.Color(0x10101a);

        const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, -400000, 400000);

        // ---- CAD-style camera rig ----------------------------------------
        // Left-drag pans (the world follows the cursor), middle/right-drag
        // rotates around the GROUND POINT you grabbed (not a fixed target),
        // and the wheel zooms about the cursor. The look-at target only
        // serves as the camera anchor; every interaction pivots on what's
        // under the mouse.
        const target = new THREE.Vector3();
        const q0 = new URLSearchParams(location.search);
        // NOT `parseFloat(..) || dflt` — 0 is falsy, and yaw=0 is a perfectly
        // good camera angle.
        const num = (v: string | null): number | null => {
            if (v == null) return null;
            const n = parseFloat(v);
            return Number.isFinite(n) ? n : null;
        };
        let yaw = ((num(q0.get("yaw")) ?? 45) * Math.PI) / 180;
        let pitch = ((num(q0.get("pitch")) ?? 45.8) * Math.PI) / 180;
        const PITCH_MIN = 0.02; // ~1 deg: full frontal shots allowed
        const PITCH_MAX = 1.5533; // 89 deg — at exactly 90 yaw is atan2(0,0)
        pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, pitch));
        // Orientation only for ortho projection — but its height component
        // (DIST·sin(pitch)) must clear the grazing-pitch target.y clamp so
        // the camera stays above the world even at eye level.
        const DIST = 200000;

        // Updated from the manifest; generous default until it loads.
        let worldMaxUnits = 1024 * 128;
        // Keep the URL shareable: after the view settles, replaceState with
        // the exact camera (bot x/z, yaw/pitch in degrees, zoom in tiles,
        // floor + roofs). Debounced — browsers rate-limit replaceState.
        let urlTimer: ReturnType<typeof setTimeout> | null = null;
        const syncUrl = () => {
            // Trailing-edge throttle (NOT debounce — the streaming pass calls
            // this every 250ms, which would starve a debounce forever).
            if (urlTimer) return;
            urlTimer = setTimeout(() => {
                urlTimer = null;
                const manifest = stateRef.current.manifest;
                if (!manifest) return;
                const worldWidthUnits = manifest.botXTiles * 128;
                const p = new URLSearchParams(location.search);
                p.set("floor", stateRef.current.floor);
                p.set("roofs", stateRef.current.roofs ? "1" : "0");
                p.set("sight", stateRef.current.sight ? "1" : "0");
                p.set("tags", stateRef.current.tags ? "1" : "0");
                p.set("x", ((worldWidthUnits - target.x) / 128).toFixed(1));
                p.set("z", (target.z / 128).toFixed(1));
                p.set("zoom", (viewHeightUnits / 128).toFixed(1));
                p.set("yaw", ((yaw * 180) / Math.PI).toFixed(1));
                p.set("pitch", ((pitch * 180) / Math.PI).toFixed(1));
                history.replaceState(null, "", `${location.pathname}?${p}`);
            }, 400);
        };

        const applyCamera = () => {
            const MARGIN = 40 * 128;
            target.x = Math.max(-MARGIN, Math.min(worldMaxUnits + MARGIN, target.x));
            target.y = Math.max(-3000, Math.min(3000, target.y));
            target.z = Math.max(-MARGIN, Math.min(worldMaxUnits + MARGIN, target.z));
            const cp = Math.cos(pitch);
            camera.position.set(
                target.x + DIST * cp * Math.sin(yaw),
                target.y + DIST * Math.sin(pitch),
                target.z + DIST * cp * Math.cos(yaw));
            camera.up.set(0, 1, 0);
            camera.lookAt(target);
            // Publish to the cross-tab box immediately (the map adopts it on
            // reveal); the URL write below is debounced and only for F5/share.
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
            // Debug handle for headless tests.
            (window as any).__w3d = {
                target: {x: target.x, y: target.y, z: target.z},
                yaw, pitch, viewHeightUnits,
            };
        };

        /** Camera-frame basis in world space: right and screen-up vectors.
         * Screen-plane pan/zoom with these is EXACT in ortho at any pitch —
         * unlike ground-ray picking, which degenerates at grazing angles. */
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

        /** Slide the target along the view axis back to the ground plane —
         * visually a no-op in ortho, but essential after a screen-plane
         * pan/zoom: at shallow pitch most of a vertical drag lands in
         * target.y, and once applyCamera's ±3000 clamp binds, only
         * sin²(pitch) of each drag survives on screen (the "rubbery pan").
         * Re-grounding keeps target.y at 0 so the clamp never engages.
         * Skipped at grazing pitch, where the slide would shove the target
         * miles from the visible scene (same guard as rotateAboutPivot). */
        const regroundTarget = () => {
            const sp = Math.sin(pitch);
            if (sp < 0.15 || target.y === 0) return;
            // Forward view dir is (-cp·sy, -sp, -cp·cy); slide t = y/sp of it
            // zeroes y while leaving the ortho image identical.
            const t = target.y / sp;
            const cp = Math.cos(pitch);
            target.x -= cp * Math.sin(yaw) * t;
            target.z -= cp * Math.cos(yaw) * t;
            target.y = 0;
        };

        // ---- GPU depth pick -------------------------------------------
        // The rotation pivot must be the visible surface under the cursor
        // (wall, roof, tree, elevated terrain) — a y=0 plane intersection
        // lands far beyond what you clicked at low angles. Render the scene's
        // depth through a 1px sub-frustum at the cursor and reconstruct the
        // world point; ortho depth is linear so the math is exact.
        const pickRT = new THREE.WebGLRenderTarget(1, 1);
        const idRT = new THREE.WebGLRenderTarget(1, 1);
        const pickBuf = new Uint8Array(4);
        const idBuf = new Uint8Array(4);
        const surfacePoint = (clientX: number, clientY: number): THREE.Vector3 | null => {
            const r = renderer.domElement.getBoundingClientRect();
            if (!r.width || !r.height) return null;
            const ndcX = ((clientX - r.left) / r.width) * 2 - 1;
            const ndcY = -(((clientY - r.top) / r.height) * 2 - 1);
            const vx = ndcX * camera.right; // view-plane offset (camera.right = halfW)
            const vy = ndcY * camera.top;
            const eps = (2 * camera.right) / r.width; // ~1px
            const pc = camera.clone();
            pc.left = vx - eps;
            pc.right = vx + eps;
            pc.bottom = vy - eps;
            pc.top = vy + eps;
            pc.updateProjectionMatrix();
            const prevTarget = renderer.getRenderTarget();
            const prevClear = renderer.getClearColor(new THREE.Color());
            const prevAlpha = renderer.getClearAlpha();
            const prevBackground = scene.background;
            scene.background = null; // keep the white clear = miss sentinel
            // Same alpha-aware pass as idPick, but emitting packed DEPTH: the
            // object materials discard their transparent texels (so a doorframe
            // hole reveals the surface behind it), then pack gl_FragCoord.z.
            for (const m of materials.values()) {
                m.uniforms.pickMode.value = 2;
                m.blending = THREE.NoBlending;
            }
            // Entity overlays (rings, sprite billboards) must not write pick
            // depth: they'd become an invisible depth wall floating toward the
            // camera and skew every picked ground point near an entity.
            const hidden: THREE.Object3D[] = [];
            for (const child of scene.children) {
                if (child.userData.noPick && child.visible) {
                    child.visible = false;
                    hidden.push(child);
                }
            }
            renderer.setRenderTarget(pickRT);
            renderer.setClearColor(0xffffff, 1); // white = z≈1.02 → miss (sky)
            renderer.clear();
            renderer.render(scene, pc);
            renderer.readRenderTargetPixels(pickRT, 0, 0, 1, 1, pickBuf);
            for (const child of hidden) child.visible = true;
            renderer.setRenderTarget(prevTarget);
            renderer.setClearColor(prevClear, prevAlpha);
            scene.background = prevBackground;
            for (const m of materials.values()) {
                m.uniforms.pickMode.value = 0;
                m.blending = THREE.NormalBlending;
            }
            // Unpack packDepth()'s base-255 24-bit depth (R most significant).
            const d = pickBuf[0] / 255
                + pickBuf[1] / (255 * 255)
                + pickBuf[2] / (255 * 255 * 255);
            (window as any).__pickDebug = {
                buf: [pickBuf[0], pickBuf[1], pickBuf[2], pickBuf[3]], d,
                vx, vy, near: camera.near, far: camera.far,
            };
            if (d >= 0.9999) return null; // sky
            const distForward = camera.near + d * (camera.far - camera.near);
            const {right, up} = screenBasis();
            const viewDir = new THREE.Vector3(
                -Math.cos(pitch) * Math.sin(yaw),
                -Math.sin(pitch),
                -Math.cos(pitch) * Math.cos(yaw));
            return camera.position.clone()
                .addScaledVector(right, vx)
                .addScaledVector(up, vy)
                .addScaledVector(viewDir, distForward);
        };

        const raycaster = new THREE.Raycaster();
        /** Cursor position -> point on the ground plane (y=0), or null.
         * The point is clamped to the view's vicinity: at shallow pitch a
         * cursor near the horizon maps to ground absurdly far away, and
         * rotating/panning about such a pivot teleports the view. */
        const groundPoint = (clientX: number, clientY: number): THREE.Vector3 | null => {
            const r = renderer.domElement.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) return null;
            const ndc = new THREE.Vector2(
                ((clientX - r.left) / r.width) * 2 - 1,
                -((clientY - r.top) / r.height) * 2 + 1);
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

        /** Rigidly rotate the camera+target pair about `pivot`, then re-anchor
         * the target to where the view axis meets the ground plane. In an
         * orthographic camera, sliding along the view axis is visually a
         * no-op — and the re-anchor pins target.y to 0, which (with the pitch
         * clamp) makes camera.y = DIST·sin(pitch) > 0 by construction: the
         * camera can never drift or flip under the world. */
        const rotateAboutPivot = (pivot: THREE.Vector3, dYaw: number, dPitch: number) => {
            const dp = Math.max(PITCH_MIN, Math.min(PITCH_MAX, pitch + dPitch)) - pitch;
            const right = new THREE.Vector3(Math.cos(yaw), 0, -Math.sin(yaw));
            // Rotating by +a about `right` LOWERS pitch (R_right(a): sin(p) ->
            // sin(p-a)) — negate so dp means what it says and the clamp binds.
            const q = new THREE.Quaternion()
                .setFromAxisAngle(new THREE.Vector3(0, 1, 0), dYaw)
                .multiply(new THREE.Quaternion().setFromAxisAngle(right, -dp));
            const newCam = camera.position.clone().sub(pivot).applyQuaternion(q).add(pivot);
            const newTgt = target.clone().sub(pivot).applyQuaternion(q).add(pivot);
            const d = newCam.sub(newTgt);
            const len = d.length();
            pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX,
                Math.asin(Math.max(-1, Math.min(1, d.y / len)))));
            yaw = Math.atan2(d.x, d.z);
            // Re-anchor: intersect the view axis with y=0. At grazing pitch
            // the axis is near-horizontal and the slide would shove the
            // target miles from the visible scene — there, keep the target
            // where the rotation put it with a soft vertical clamp instead.
            // (Slides along the view axis are invisible in ortho, so the rig
            // re-grounds seamlessly on the next steeper-pitch rotation.)
            const dirY = Math.sin(pitch);
            if (dirY >= 0.15) {
                const t0 = -newTgt.y / dirY;
                target.set(
                    newTgt.x + (d.x / len) * t0,
                    0,
                    newTgt.z + (d.z / len) * t0);
            } else {
                target.copy(newTgt);
                target.y = Math.max(-3000, Math.min(3000, target.y));
            }
            applyCamera();
        };

        const q = new URLSearchParams(location.search);
        let viewHeightUnits = (num(q.get("zoom")) ?? 60) * 128; // view height in tiles
        const resize = () => {
            const w = captureSize ? captureSize.w : (host.clientWidth || 800);
            const h = captureSize ? captureSize.h : (host.clientHeight || 600);
            // While recording, pin pixelRatio to 1 and don't touch the canvas's
            // CSS size (updateStyle=false) — the backing store becomes exactly
            // w×h (what we grab) while the on-screen canvas keeps its layout.
            renderer.setPixelRatio(captureSize ? 1 : window.devicePixelRatio);
            renderer.setSize(w, h, !captureSize);
            const aspect = w / h;
            camera.left = -viewHeightUnits * aspect / 2;
            camera.right = viewHeightUnits * aspect / 2;
            camera.top = viewHeightUnits / 2;
            camera.bottom = -viewHeightUnits / 2;
            camera.updateProjectionMatrix();
        };
        const ro = new ResizeObserver(resize);
        ro.observe(host);

        // Re-centre on the shared camera box (x/z in game tiles, zoom in tiles)
        // that the map tab writes — called when this view becomes active again.
        // Adopts location + zoom only; the 3D yaw/pitch are ours to keep. NB the
        // manifest may not be loaded yet on the very first reveal (guarded).
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

        // ---- Hover info: tile + scenery under the cursor ----------------
        // Placements from /api/map/scenery.json; names + footprints from the
        // sprite-atlas index. Coverage-indexed so a 2x2 object matches on any
        // of its tiles, not just the anchor.
        type HoverObj = {id: number; dir: number; name: string; ax: number; az: number};
        const sceneryByTile = new Map<string, HoverObj[]>();
        Promise.all([fetchScenery(), fetchSceneryAtlasIndex()])
            .then(([placements, atlas]) => {
                for (const pl of placements) {
                    const ck = cellKeyOf(pl.floor, pl.x, pl.z);
                    let arr = placementsByCell.get(ck);
                    if (!arr) placementsByCell.set(ck, arr = []);
                    arr.push(pl);
                    staticByTile.set(tileKeyOf(pl.floor, pl.x, pl.z), pl);
                }
                // Cells whose terrain already streamed in can assemble now.
                for (const key of terrainReady) dirtyCells.add(key);
                const meta = new Map<string, {name: string; tw: number; th: number}>();
                for (const e of atlas.entries) {
                    meta.set(`${e.id}_${e.dir}`, {name: e.name, tw: e.tw, th: e.th});
                }
                for (const pl of placements) {
                    const m = meta.get(`${pl.id}_${pl.dir}`);
                    const tw = m?.tw ?? 1;
                    const th = m?.th ?? 1;
                    const obj: HoverObj = {id: pl.id, dir: pl.dir,
                        name: m?.name ?? `object ${pl.id}`, ax: pl.x, az: pl.z};
                    for (let dx = 0; dx < tw; dx++) {
                        for (let dz = 0; dz < th; dz++) {
                            const key = `${pl.floor}:${pl.x + dx},${pl.z + dz}`;
                            let arr = sceneryByTile.get(key);
                            if (!arr) sceneryByTile.set(key, arr = []);
                            arr.push(obj);
                        }
                    }
                }
            })
            .catch(() => { /* hover works without names */ });

        // Latest un-resolved cursor position. The render loop drains this once
        // per frame (see the `pendingHover` handling in `loop`). Coalescing to
        // one resolve per frame — instead of a fixed time gate — keeps hover a
        // single GPU depth-pick per rendered frame however fast the mouse
        // moves, so the highlight tracks the cursor within one frame (~16ms at
        // 60fps). The old 120ms throttle capped updates at ~8/sec, which
        // visibly skipped every 2nd–3rd tile on a quick drag.
        // A single hover request, drained once per frame. `canvas` resolves the
        // pick under the cursor; `plate` focuses the entity behind a hovered
        // nameplate; `none` clears. One code path (commitHover) applies whichever
        // it is, so exactly ONE thing is ever highlighted — moving between the
        // 3D scene and a nameplate transfers focus instead of lighting both.
        type HoverReq =
            | {kind: "canvas"; x: number; y: number}
            | {kind: "plate"; key: string}
            | {kind: "none"};
        let pendingHover: HoverReq | null = null;
        let lastHoverText = "";
        // Sticky hover tile (hysteresis). The GPU depth-pick under the cursor
        // lands on whatever surface is there; on a wall that's the vertical
        // face, whose ground x,z is pinned to the tile BOUNDARY (fx ≈ n+0.5),
        // so plain rounding coin-flips between the two adjacent tiles as depth
        // noise nudges it across the threshold. Once hover commits to a tile we
        // hold it until the pick moves past the boundary by HOVER_TILE_HYST —
        // killing the flicker. Reset on every fresh hover engagement so a jump
        // re-picks cleanly. (Clicks don't use this; they pick the exact tile.)
        let hoverTileX: number | null = null;
        let hoverTileZ: number | null = null;
        const hoverText = (tgt: Target): string => {
            if (!tgt) return "";
            if (tgt.type === "entity") {
                return `${tgt.kind} ${tgt.name ?? tgt.key}`
                    + (tgt.npcId != null ? ` (id ${tgt.npcId})` : "")
                    + ` · tile ${Math.round(tgt.x)},${Math.round(tgt.z)}`
                    + (tgt.inCombat ? " · in combat" : "");
            } else if (tgt.type === "object") {
                return `${tgt.name} (id ${tgt.id}, dir ${tgt.dir},`
                    + ` anchor ${tgt.ax},${tgt.az}, ${tgt.w}x${tgt.h})`;
            } else if (tgt.type === "wall") {
                return `${tgt.name} (boundary ${tgt.id}, dir ${tgt.dir},`
                    + ` edge ${tgt.x},${tgt.z})`;
            } else if (tgt.type === "grounditem") {
                return `${tgt.name ?? "item"} (id ${tgt.id})`
                    + ` · tile ${Math.round(tgt.x)},${Math.round(tgt.z)}`;
            }
            return `tile ${tgt.x},${tgt.z} · h ${heightAt(tgt.x, tgt.z).toFixed(0)}`;
        };
        // The one place hover state is applied: light the target (applyHover
        // clears whatever else was lit) and sync the label. Every hover source —
        // scene pick, nameplate, leave — funnels through here.
        const commitHover = (tgt: Target) => {
            applyHover(tgt);
            const text = hoverText(tgt);
            // The 3D highlight tracks every frame; only touch React state when
            // the label text actually changes, so moving within one tile doesn't
            // force a re-render on every frame.
            if (text !== lastHoverText) {
                lastHoverText = text;
                setHover(text);
            }
        };
        const resolveHover = (clientX: number, clientY: number) => {
            if (!stateRef.current.manifest) return;
            commitHover(resolveTarget(clientX, clientY, true));
        };
        // Nameplate hover: focus the entity the plate belongs to (no scene pick),
        // so it highlights identically to hovering the sprite itself.
        const hoverPlate = (key: string) => {
            const e = entityLayer.current().find(c => c.key === key);
            commitHover(e ? {type: "entity", ...e} : null);
        };

        type Drag = {mode: "rotate" | "pan"; button: number; pointerId: number;
            lastX: number; lastY: number;
            downX: number; downY: number; pivot: THREE.Vector3 | null;
            plateKey: string | null; noClick?: boolean};
        let drag: Drag | null = null;

        // --- Multi-touch camera gestures ---------------------------------
        // Active touch points by pointerId. One finger keeps the single-finger
        // pan/tap path below; a second finger down switches to a two-finger
        // gesture: pinch = zoom, twist = yaw, vertical drag = pitch (the
        // Google-Maps-style scheme — the only way to zoom or rotate on a touch
        // device, which has no wheel and no middle/right button).
        const pointers = new Map<number, {x: number; y: number}>();
        type Gesture = {dist: number; midX: number; midY: number;
            angle: number; pivot: THREE.Vector3 | null};
        let gesture: Gesture | null = null;

        // Scale the ortho frustum by `factor` about a screen point, keeping the
        // view-plane point under (clientX, clientY) fixed. Shared by wheel zoom
        // and pinch zoom.
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
            const {right, up} = screenBasis();
            target.addScaledVector(right, ndcX * halfW * (1 - f))
                  .addScaledVector(up, ndcY * halfH * (1 - f));
            regroundTarget();
            applyCamera();
        };

        // The two active fingers' distance / midpoint / angle — the baseline a
        // gesture takes deltas from.
        const twoPointer = () => {
            const [a, b] = [...pointers.values()];
            return {dist: Math.hypot(a.x - b.x, a.y - b.y),
                midX: (a.x + b.x) / 2, midY: (a.y + b.y) / 2,
                angle: Math.atan2(b.y - a.y, b.x - a.x)};
        };
        const startGesture = () => {
            const p = twoPointer();
            gesture = {...p,
                pivot: surfacePoint(p.midX, p.midY) ?? groundPoint(p.midX, p.midY)};
        };
        const applyGesture = () => {
            if (!gesture || pointers.size < 2) return;
            const p = twoPointer();
            // Pinch: spreading the fingers (dist grows) zooms IN, about the
            // midpoint so the world stays pinned under both fingertips.
            if (gesture.dist > 0 && p.dist > 0) {
                zoomAbout(p.midX, p.midY, gesture.dist / p.dist);
            }
            // Twist -> yaw (scene follows the fingers); two-finger vertical drag
            // -> pitch (drag DOWN tilts toward top-down, matching the rotate-drag
            // convention). A deliberate pinch/twist/tilt each moves mainly one of
            // dist/angle/midY, so applying all three per frame stays clean.
            let dYaw = p.angle - gesture.angle;
            if (dYaw > Math.PI) dYaw -= 2 * Math.PI;
            else if (dYaw < -Math.PI) dYaw += 2 * Math.PI;
            const dPitch = (p.midY - gesture.midY) * 0.008;
            rotateAboutPivot(gesture.pivot ?? target, -dYaw, dPitch);
            gesture.dist = p.dist;
            gesture.midX = p.midX;
            gesture.midY = p.midY;
            gesture.angle = p.angle;
        };

        const el = renderer.domElement;
        // Camera input listens on the HOST (canvas + overlay divs), not the
        // canvas: nameplates float above it, and events over them never
        // reach a canvas listener — wheel-zoom and drags died on hover.
        el.style.touchAction = "none";
        host.style.touchAction = "none";
        host.addEventListener("contextmenu", e => e.preventDefault());
        host.addEventListener("pointerdown", e => {
            if (flightRef.current) {
                flightRef.current = null;
                setFlying(false);
            }
            if (e.pointerType === "touch") {
                pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
                host.setPointerCapture(e.pointerId);
                if (pointers.size >= 2) {
                    // Second finger down: abandon the single-finger pan/tap and
                    // drive the camera by the two-finger gesture instead.
                    drag = null;
                    pendingHover = {kind: "none"};
                    if (pointers.size === 2) startGesture();
                    return;
                }
                // One finger: fall through to the normal pan-drag setup below.
            }
            if (e.button !== 0 && e.button !== 1 && e.button !== 2) return;
            // A press begins a drag; don't let a queued hover resolve mid-drag.
            pendingHover = {kind: "none"};
            // Stock-like controls: LEFT drag pans the map, MIDDLE or RIGHT
            // drag rotates the camera (shift+left = rotate fallback for
            // trackpads). A no-drag right CLICK still opens the option menu,
            // a no-drag left click still selects/commands.
            const mode = e.button === 0 && !e.shiftKey ? "pan" : "rotate";
            // Middle button would otherwise start the browser's autoscroll.
            if (e.button === 1) e.preventDefault();
            // A press on a nameplate still starts a camera drag; if it ends
            // within the click threshold, endDrag selects the plate's entity
            // instead (pointer capture retargets the click away from the
            // plate, so plates can't own their click handlers).
            const plate = (e.target as HTMLElement).closest?.(
                "[data-entity-key]") as HTMLElement | null;
            drag = {mode, button: e.button, pointerId: e.pointerId,
                lastX: e.clientX, lastY: e.clientY,
                downX: e.clientX, downY: e.clientY,
                pivot: mode === "rotate"
                    ? (surfacePoint(e.clientX, e.clientY) ?? groundPoint(e.clientX, e.clientY))
                    : null,
                plateKey: plate?.dataset.entityKey ?? null};
            (window as any).__w3dPivot = drag.pivot
                ? {x: drag.pivot.x, y: drag.pivot.y, z: drag.pivot.z} : null;
            host.setPointerCapture(e.pointerId);
        });
        host.addEventListener("pointermove", e => {
            if (e.pointerType === "touch" && pointers.has(e.pointerId)) {
                pointers.set(e.pointerId, {x: e.clientX, y: e.clientY});
                if (gesture && pointers.size >= 2) {
                    applyGesture();
                    return;
                }
            }
            if (!drag) {
                // Record what's under the cursor and let the render loop resolve
                // it once this frame — resolving inline would fire a synchronous
                // GPU pick per pointermove event (many per frame on a fast
                // mouse). A nameplate (or any [data-entity-key] chrome) focuses
                // its entity; the bare canvas runs a scene pick; anything else
                // (other overlays) clears — so only one thing is ever lit.
                const plate = (e.target as HTMLElement).closest?.(
                    "[data-entity-key]") as HTMLElement | null;
                pendingHover = plate?.dataset.entityKey
                    ? {kind: "plate", key: plate.dataset.entityKey}
                    : e.target === el
                        ? {kind: "canvas", x: e.clientX, y: e.clientY}
                        : {kind: "none"};
                return;
            }
            const dx = e.clientX - drag.lastX;
            const dy = e.clientY - drag.lastY;
            drag.lastX = e.clientX;
            drag.lastY = e.clientY;
            if (drag.mode === "rotate") {
                const pivot = drag.pivot ?? target;
                // Drag DOWN = tilt toward top-down, drag UP = toward
                // frontal (user preference, inverted from the grab metaphor).
                rotateAboutPivot(pivot, -dx * 0.008, dy * 0.008);
            } else {
                // Screen-plane pan: the world follows the cursor 1:1 at every
                // pitch (in ortho this equals grab-the-ground at steep angles
                // and stays exact at eye level, where ground rays degenerate).
                const wpp = viewHeightUnits / (renderer.domElement.clientHeight || 600);
                const {right, up} = screenBasis();
                target.addScaledVector(right, -dx * wpp)
                      .addScaledVector(up, dy * wpp);
                regroundTarget();
                applyCamera();
            }
        });
        // ---- Stock "Choose option" menu -------------------------------
        // Entries carry the stock client's MenuItemAction priorities, so
        // sorting reproduces the real menu order AND the real left-click:
        // the top entry after sorting is what a left click runs (Walk here
        // at 920 naturally beats Trade at 2810 but loses to Talk-to at 720
        // — exactly the stock behaviour).
        type MenuEntry = {verb: string; target?: string; tcolor?: string;
            suffix?: string; scolor?: string; prio: number; run?: () => void};

        // Stock level-difference colour ramp (mudclient addPlayerToMenu /
        // npc menu; colour values from GraphicsController's @code@ table).
        const levelColor = (delta: number) =>
            delta < -9 ? "#ff0000" : delta < -6 ? "#ff3000"
            : delta < -3 ? "#ff7000" : delta < 0 ? "#ffb000"
            : delta > 9 ? "#00ff00" : delta > 6 ? "#40ff00"
            : delta > 3 ? "#80ff00" : delta > 0 ? "#c0ff00" : "#fff";

        // Server Point.inWilderness ground truth: depth = 2203 − (y + 1776 −
        // 944·floor(y/944)); anything at x ≥ 336 (x + 2304 ≥ 2640) is out.
        const wildernessLevel = (x: number, zAbs: number) => {
            if (x + 2304 >= 2640) return 0;
            const wild = 2203 - (zAbs + (1776 - 944 * Math.floor(zAbs / 944)));
            return wild > 0 ? 1 + Math.floor(wild / 6) : 0;
        };

        // Click nonce for verbs whose dir field is not semantic: makes every
        // click a fresh bus target, so repeating the same command while the
        // bot still lingers on the previous one re-arms instead of being
        // swallowed by Target equality.
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
            const useDone = () => propsRef.current.onUseItemDone?.();
            const entries: MenuEntry[] = [];
            const send = (action: Parameters<typeof sendInteract>[1],
                          x: number, z: number, id: number, dir: number,
                          item = 0) =>
                sendInteract(user, action, x, z, id, dir, item).catch(() => {});

            // Bot billboards win outright (priority 1); otherwise the nearest
            // non-bot entity. Both feed the same menu entries.
            const ent = L.bot ?? L.ent;
            if (ent && !ent.ghost && ent.kind === "npc" && ent.npcId != null) {
                const idx = parseInt(ent.key.slice(4), 10);
                const nm = ent.name ?? npcSprites.info(ent.npcId)?.name ?? "npc";
                const x = Math.round(ent.x);
                const z = zAbs(Math.round(ent.z));
                const info = npcSprites.info(ent.npcId);
                if (useIt) {
                    entries.push({verb: `Use ${useIt.name} with`, target: nm,
                        tcolor: "#ff0", prio: 710,
                        run: () => {
                            send("use_item_on_npc", x, z, idx, clickNonce++,
                                useIt.id);
                            useDone();
                        }});
                } else {
                    const self = (propsRef.current.observers ?? [])
                        .find(b => b.username === user);
                    if (info?.atk) {
                        const delta = (self?.combatLvl ?? 0) - (info.lvl ?? 0);
                        // NPC_ATTACK1 (715) when we out-level it, NPC_ATTACK2
                        // (2715, below Walk here) when it out-levels us — the
                        // stock "don't left-click a monster above you" rule.
                        entries.push({verb: "Attack", target: nm, tcolor: "#ff0",
                            suffix: ` (level-${info.lvl})`,
                            scolor: levelColor(delta),
                            prio: delta >= 0 ? 715 : 2715,
                            run: () => send("npc_attack", x, z, idx,
                                clickNonce++)});
                    }
                    entries.push({verb: "Talk-to", target: nm, tcolor: "#ff0",
                        prio: 720,
                        run: () => send("npc_talk", x, z, idx, clickNonce++)});
                    if (info?.cmd1) {
                        entries.push({verb: info.cmd1, target: nm, tcolor: "#ff0",
                            prio: 725,
                            run: () => send("npc_command1", x, z, idx,
                                clickNonce++)});
                    }
                    if (info?.cmd2) {
                        entries.push({verb: info.cmd2, target: nm, tcolor: "#ff0",
                            prio: 833,
                            run: () => send("npc_command2", x, z, idx,
                                clickNonce++)});
                    }
                }
            } else if (ent && (ent.kind === "player" || ent.kind === "bot")
                       && !(ent.kind === "bot" && ent.name === user)) {
                // Player verbs need the target's server index: players carry
                // it in their key (pl:<idx>); own observers resolve via Observer.
                const idx = ent.kind === "player"
                    ? parseInt(ent.key.slice(3), 10)
                    : (propsRef.current.observers ?? [])
                        .find(b => b.username === ent.name)?.serverIndex;
                const nm = ent.name ?? "player";
                const x = Math.round(ent.x);
                const z = zAbs(Math.round(ent.z));
                if (idx != null && !useIt) {
                    const wild = wildernessLevel(x, z) > 0;
                    if (wild) {
                        // Stock: Attack only offered in the wilderness
                        // (PLAYER_ATTACK_SIMILAR; we don't know a foreign
                        // player's level, so the divergent-level demotion
                        // below Walk-here is approximated as always-similar).
                        entries.push({verb: "Attack", target: nm,
                            tcolor: "#fff", prio: 805,
                            run: () => send("player_attack", x, z, idx,
                                clickNonce++)});
                    } else {
                        entries.push({verb: "Duel with", target: nm,
                            tcolor: "#fff", prio: 2806,
                            run: () => send("player_duel", x, z, idx,
                                clickNonce++)});
                    }
                    entries.push({verb: "Trade with", target: nm,
                        tcolor: "#fff", prio: 2810,
                        run: () => send("player_trade", x, z, idx,
                            clickNonce++)});
                    entries.push({verb: "Follow", target: nm,
                        tcolor: "#fff", prio: 2820,
                        run: () => send("player_follow", x, z, idx,
                            clickNonce++)});
                }
            }

            for (const g of L.ground) {
                // One entry per pile item; same prio → the stable sort keeps
                // them nearest-first, exactly one Take per item like stock.
                const gx = Math.round(g.x);
                const gz = zAbs(Math.round(g.z));
                if (useIt) {
                    entries.push({verb: `Use ${useIt.name} with`,
                        target: g.name ?? "item", tcolor: "#ff9040", prio: 210,
                        run: () => {
                            send("use_item_on_ground_item", gx, gz, g.id,
                                clickNonce++, useIt.id);
                            useDone();
                        }});
                } else {
                    entries.push({verb: "Take", target: g.name ?? "item",
                        tcolor: "#ff9040", prio: 220,
                        run: () => send("ground_item", gx, gz, g.id,
                            clickNonce++)});
                }
            }

            if (L.wall?.type === "wall") {
                const wl = L.wall;
                const def = doorDefs?.get(wl.id);
                const z = zAbs(wl.z);
                if (useIt) {
                    entries.push({verb: `Use ${useIt.name} with`,
                        target: wl.name, tcolor: "#0ff", prio: 310,
                        run: () => {
                            send("use_item_on_wall_object", wl.x, z, wl.id,
                                wl.dir, useIt.id);
                            useDone();
                        }});
                } else {
                    // Stock rule: only the def's real commands appear (the
                    // "WalkTo"/"Examine" sentinels are stripped at bake) — a
                    // fence or window contributes no entries at all.
                    if (def?.cmd1) {
                        entries.push({verb: def.cmd1, target: wl.name,
                            tcolor: "#0ff", prio: 320,
                            run: () => send("wall_object", wl.x, z, wl.id,
                                wl.dir)});
                    }
                    if (def?.cmd2) {
                        entries.push({verb: def.cmd2, target: wl.name,
                            tcolor: "#0ff", prio: 2300,
                            run: () => send("wall_object2", wl.x, z, wl.id,
                                wl.dir)});
                    }
                }
            }

            if (L.obj?.type === "object") {
                const o = L.obj;
                const lib = objLib?.objects.get(o.id);
                const z = zAbs(o.az);
                if (useIt) {
                    entries.push({verb: `Use ${useIt.name} with`,
                        target: o.name, tcolor: "#0ff", prio: 410,
                        run: () => {
                            send("use_item_on_object", o.ax, z, o.id, o.dir,
                                useIt.id);
                            useDone();
                        }});
                } else {
                    // Stock rule: only the def's real commands appear —
                    // decorative scenery contributes no entries, so a left
                    // click near it still walks.
                    if (lib?.cmd1) {
                        entries.push({verb: lib.cmd1, target: o.name,
                            tcolor: "#0ff", prio: 420,
                            run: () => send("object", o.ax, z, o.id, o.dir)});
                    }
                    if (lib?.cmd2) {
                        entries.push({verb: lib.cmd2, target: o.name,
                            tcolor: "#0ff", prio: 2400,
                            run: () => send("object2", o.ax, z, o.id, o.dir)});
                    }
                }
            }

            if (L.tile) {
                const t = L.tile;
                entries.push({verb: "Walk here", prio: 920,
                    run: () => {
                        sendWalk(user, {x: t.x, z: zAbs(t.z)}).catch(() => {});
                        // Stock: a plain walk while an item is selected
                        // deselects it.
                        if (useIt) useDone();
                    }});
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

        const endDrag = (e: PointerEvent) => {
            const wasClick = drag && !drag.noClick
                && Math.hypot(e.clientX - drag.downX, e.clientY - drag.downY) < 4;
            // Any click while the option menu is open just closes it (the
            // menu's own entries stop propagation before reaching here).
            if (wasClick && ctxMenuOpen.current) {
                setCtxMenu(null);
                drag = null;
                if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                return;
            }
            // Stock right-click: the "Choose option" menu (walk/act tool on).
            if (wasClick && drag!.button === 2
                && (propsRef.current.walkTool || propsRef.current.useItem)
                && propsRef.current.selectedBot) {
                openMenu(e.clientX, e.clientY);
                drag = null;
                if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                return;
            }
            if (drag && drag.button === 0 && !drag.noClick
                && Math.hypot(e.clientX - drag.downX, e.clientY - drag.downY) < 4) {
                // A click, not a drag: select whatever is under the cursor.
                // A nameplate press selects its entity — same as clicking
                // the sprite itself.
                const plateEnt = drag.plateKey
                    ? entityLayer.current().find(c => c.key === drag!.plateKey)
                    : null;
                const tgt: Target = plateEnt
                    ? {type: "entity", ...plateEnt}
                    : resolveTarget(e.clientX, e.clientY);
                // Walk/act tool: a left click COMMANDS the selected bot by
                // running the TOP menu entry (stock semantics: entries sort
                // by MenuItemAction priority and left click = first) — walk,
                // object command-1, Talk-to, Take, Attack-when-outleveling…
                // A nameplate click still resolves that plate's entity.
                const user = propsRef.current.selectedBot;
                if ((propsRef.current.walkTool || propsRef.current.useItem)
                    && user && tgt) {
                    let entries = buildEntries(e.clientX, e.clientY);
                    if (plateEnt && entries.length) {
                        // The plate may float away from the sprite; prefer
                        // entries for the plate's entity when present, else
                        // fall back to what's under the cursor.
                        const nm = plateEnt.name ?? "";
                        const own = entries.filter(en => en.target === nm);
                        if (own.length) entries = own;
                    }
                    entries[0]?.run?.();
                } else if (tgt?.type === "entity") {
                    entityLayer.selectedEntityKey = tgt.key;
                    if (tgt.kind === "bot") {
                        propsRef.current.onSelectBot?.(tgt.key.slice(4));
                        setPicked(null);
                    } else {
                        setPicked({kind: tgt.kind, name: tgt.name ?? null,
                            npcId: tgt.npcId ?? null, key: tgt.key,
                            inCombat: !!tgt.inCombat, appearance: tgt.appearance});
                    }
                } else if (tgt?.type === "object") {
                    setPicked({kind: "object", name: tgt.name, npcId: tgt.id,
                        key: `obj:${tgt.ax},${tgt.az}`, inCombat: false});
                } else if (tgt?.type === "wall") {
                    setPicked({kind: "boundary", name: tgt.name, npcId: tgt.id,
                        key: `wall:${tgt.x},${tgt.z},${tgt.dir}`,
                        inCombat: false});
                } else {
                    entityLayer.selectedEntityKey = null;
                    setPicked(null);
                }
            }
            drag = null;
            if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
        };
        // Touch: keep the pointer map current and unwind the gesture before the
        // single-finger tap/drag logic runs. Lifting from two fingers to one
        // re-seeds a click-suppressed pan so the remaining finger keeps panning
        // without a re-press.
        const onPointerUp = (e: PointerEvent) => {
            if (e.pointerType === "touch") {
                const wasTracked = pointers.delete(e.pointerId);
                if (gesture) {
                    // Release only the lifted finger; the other keeps its capture
                    // so the re-seeded pan still receives its moves.
                    if (host.hasPointerCapture(e.pointerId)) host.releasePointerCapture(e.pointerId);
                    if (pointers.size < 2) {
                        gesture = null;
                        if (pointers.size === 1) {
                            const [[remId, rem]] = [...pointers.entries()];
                            drag = {mode: "pan", button: 0, pointerId: remId,
                                lastX: rem.x, lastY: rem.y,
                                downX: rem.x, downY: rem.y,
                                pivot: null, plateKey: null, noClick: true};
                        } else {
                            drag = null;
                        }
                    }
                    return;
                }
                if (!wasTracked) return;
                // Single-finger tap/drag: fall to endDrag, which consumes `drag`
                // for selection BEFORE releasing capture (releasing here first
                // could null the drag via lostpointercapture and eat the tap).
            }
            endDrag(e);
        };
        host.addEventListener("pointerup", onPointerUp);
        host.addEventListener("pointercancel", onPointerUp);
        // Leaving the view entirely drops the hover highlight.
        host.addEventListener("pointerleave", () => {
            if (!drag) pendingHover = {kind: "none"};
        });
        // Only drop the drag whose pointer actually lost capture — a two-finger
        // gesture releases the lifted finger's capture (firing this) while the
        // re-seeded pan is bound to the OTHER, still-captured finger.
        host.addEventListener("lostpointercapture", e => {
            if (drag && drag.pointerId === e.pointerId) drag = null;
        });

        host.addEventListener("wheel", e => {
            e.preventDefault();
            // Zoom about the cursor: the view-plane point under it stays fixed.
            zoomAbout(e.clientX, e.clientY, Math.pow(1.0015, e.deltaY));
        }, {passive: false});

        // Ground-height lookup built from parsed terrain cells (corner grid,
        // bot-tile indexed), kept PER PLANE: floors are retained (hidden)
        // across switches, so each keeps its own valid heights. ~4MB per
        // visited plane (1009×1009 floats).
        const HGRID = 1009;
        const heightsByPlane: (Float32Array | null)[] = [null, null, null, null];
        const heightsFor = (plane: number): Float32Array => {
            let g = heightsByPlane[plane];
            if (!g) {
                g = new Float32Array(HGRID * HGRID).fill(NaN);
                heightsByPlane[plane] = g;
            }
            return g;
        };
        // Mirror of the active floor's plane (pump syncs it): heightAt reads
        // the ACTIVE grid; assembly of hidden-floor cells uses the
        // plane-bound heightAtOf so their scenery sits on their own terrain.
        let activePlane = kindsFor(stateRef.current.floor).plane;
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
        // Bilinear over the 4 surrounding corners — nearest-corner sampling
        // stair-steps as entities cross tiles ("rings bounce").
        const heightAtGrid = (grid: Float32Array, x: number, z: number): number => {
            const sx = x + 0.5; // tile centre sits half a tile past its corner
            const sz = z + 0.5;
            const x0 = Math.floor(sx);
            const z0 = Math.floor(sz);
            const fx = sx - x0;
            const fz = sz - z0;
            const h00 = cornerHOf(grid, x0, z0);
            const h10 = cornerHOf(grid, x0 + 1, z0);
            const h01 = cornerHOf(grid, x0, z0 + 1);
            const h11 = cornerHOf(grid, x0 + 1, z0 + 1);
            return (h00 * (1 - fx) + h10 * fx) * (1 - fz)
                 + (h01 * (1 - fx) + h11 * fx) * fz;
        };
        const heightAt = (x: number, z: number): number =>
            heightAtGrid(heightsFor(activePlane), x, z);
        const heightAtOf = (plane: number) => (x: number, z: number): number =>
            heightAtGrid(heightsFor(plane), x, z);
        /** Terrain surface normal in three.js world space (x mirrored). */
        const groundNormalAt = (x: number, z: number): THREE.Vector3 => {
            const dhdx = (heightAt(x + 0.5, z) - heightAt(x - 0.5, z)); // per tile
            const dhdz = (heightAt(x, z + 0.5) - heightAt(x, z - 0.5));
            // world x3 = W − (x·128+64) → ∂x3/∂x = −128; z3 = z·128+64 → +128.
            return new THREE.Vector3(dhdx / 128, 1, -dhdz / 128).normalize();
        };

        const entityHost = document.createElement("div");
        // user-select:none: a drag that starts on a nameplate must never
        // turn into an awkward text selection.
        entityHost.style.cssText = "position:absolute;inset:0;overflow:hidden;"
            + "pointer-events:none;user-select:none;";
        host.appendChild(entityHost);
        const entityLayer = new EntityLayer(scene, entityHost);
        let lastEntitiesRev = -1;
        const sightLayer = new SightLayer(scene);
        // Selected bot's planned route — the 3D twin of the map's polyline.
        const routeRibbon = new Ribbon(scene, 0x4da3ff, 0.6, 0.16);
        let lastRoute: RoutePoint[] | null | undefined;
        let lastRouteFloor: FloorKey | null = null;
        const rebuildRoute = (plane: number,
                              toWorld: (x: number, z: number) => THREE.Vector3) => {
            const chains: {pts: {x: number; z: number}[]; closed: boolean}[] = [];
            let cur: {x: number; z: number}[] = [];
            for (const p of stateRef.current.route ?? []) {
                const onPlane = Math.floor(p.z / 944) === plane;
                // Break the line at transport hops and floor changes, like
                // the map does.
                if (p.hop || !onPlane) {
                    if (cur.length > 1) chains.push({pts: cur, closed: false});
                    cur = [];
                }
                if (onPlane) cur.push({x: p.x, z: p.z % 944});
            }
            if (cur.length > 1) chains.push({pts: cur, closed: false});
            routeRibbon.extrude(chains, toWorld);
        };
        const npcSprites = new NpcSpriteLayer(scene);
        const playerSprites = new PlayerSpriteLayer(scene);
        // Overlay anchors (bars/splats/plates) use each npc's real sprite size.
        entityLayer.npcDims = id => npcSprites.dims(id);
        // Stock overhead chat bubbles need the baked game font; retry until
        // the bake is ready (same pattern as the atlases).
        let chatFontLoading = false;
        const loadChatFont = () => {
            if (chatFontLoading || entityLayer.chatFont) return;
            chatFontLoading = true;
            loadGameFont().then(f => {
                if (!disposed) entityLayer.chatFont = f;
            }).catch(() => {
                chatFontLoading = false; // retry via the 5s loop
            });
        };
        loadChatFont();
        const groundItems = new GroundItemLayer(scene);
        const projectileLayer = new ProjectileLayer(scene);
        let npcAtlasLoading = false;
        const loadNpcAtlas = () => {
            if (npcAtlasLoading) return;
            npcAtlasLoading = true;
            fetchNpcAtlas().then(a => {
                if (disposed || !a) {
                    npcAtlasLoading = false; // retry via the 5s loop on failure
                    return;
                }
                npcSprites.setAtlas(a);
            });
        };

        let itemAtlasLoading = false;
        const loadItemAtlas = () => {
            if (itemAtlasLoading) return;
            itemAtlasLoading = true;
            fetchItemAtlas().then(a => {
                if (disposed || !a) {
                    itemAtlasLoading = false; // retry via the 5s loop on failure
                    return;
                }
                groundItems.setAtlas(a);
                // Nameplate action-bubble icons pull from the same atlas.
                entityLayer.itemFrame = id => {
                    const f = a.frames.get(id);
                    return f ? {x: f.x, y: f.y, w: f.w, h: f.h,
                        atlasW: a.width, atlasH: a.height} : null;
                };
            });
        };

        // ---- Client-side scenery assembly -------------------------------
        // Static placements come from scenery.json; geometry+shading from the
        // object library. One merged mesh group per 48-tile cell; live
        // overrides swap a tile's object id and mark just that cell dirty.
        let objLib: ObjectLibrary | null = null;
        const placementsByCell = new Map<string, SceneryPlacement[]>();
        const staticByTile = new Map<string, SceneryPlacement>();
        const overrides = new Map<string, number>(); // tileKey -> observed id
        const sceneryMeshes = new Map<string, THREE.Group>();
        const dirtyCells = new Set<string>();
        const terrainReady = new Set<string>();
        let lastObservedRev = -1;
        const cellKeyOf = (plane: number, x: number, z: number) =>
            `${plane}:${Math.floor((x - 24) / 48)},${Math.floor((z - 24) / 48)}`;
        const tileKeyOf = (plane: number, x: number, z: number) =>
            `${plane}:${x},${z}`;

        // Animated scenery (fires, torches…): per cell, one group per frame
        // index; the render loop toggles visibility on the client's 120ms
        // cycle (object_animation_count > 5 at 50fps).
        const animMeshes = new Map<string, THREE.Group[]>();
        // Windmill sails (id 74) spin in place: one pivot group per placement,
        // its geometry recentred on the model hub so a per-frame rotation about
        // the (yaw-baked) local axis reproduces the client's pe(1,0,0) spin.
        const windmillMeshes = new Map<string,
            {group: THREE.Group; axis: THREE.Vector3}[]>();
        const disposeSceneryCell = (key: string) => {
            const g = sceneryMeshes.get(key);
            if (g) {
                scene.remove(g);
                g.traverse(o => {
                    if (o instanceof THREE.Mesh) o.geometry.dispose();
                });
                sceneryMeshes.delete(key);
            }
            const frames = animMeshes.get(key);
            if (frames) {
                for (const f of frames) {
                    scene.remove(f);
                    f.traverse(o => {
                        if (o instanceof THREE.Mesh) o.geometry.dispose();
                    });
                }
                animMeshes.delete(key);
            }
            const mills = windmillMeshes.get(key);
            if (mills) {
                for (const {group} of mills) {
                    scene.remove(group);
                    group.traverse(o => {
                        if (o instanceof THREE.Mesh) o.geometry.dispose();
                    });
                }
                windmillMeshes.delete(key);
            }
        };

        const sceneryIdFor = (pl: ResolvedPlacement) =>
            packSceneryId(pl.x, pl.z);
        const buildSceneryCell = (key: string) => {
            const manifest = stateRef.current.manifest;
            if (!objLib || !manifest) return;
            disposeSceneryCell(key);
            const statics = placementsByCell.get(key) ?? [];
            const plane = parseInt(key.split(":")[0], 10);
            const resolved: ResolvedPlacement[] = [];
            const staticTiles = new Set<string>();
            for (const pl of statics) {
                const tk = tileKeyOf(plane, pl.x, pl.z);
                staticTiles.add(tk);
                const ov = overrides.get(tk);
                resolved.push({id: ov ?? pl.id, dir: pl.dir, x: pl.x, z: pl.z});
            }
            // Observed objects at tiles with NO static placement (spawned).
            for (const [tk, id] of overrides) {
                const [p0, xz] = [tk.split(":")[0], tk.split(":")[1]];
                if (parseInt(p0, 10) !== plane || staticTiles.has(tk)) continue;
                const [x, z] = xz.split(",").map(Number);
                if (cellKeyOf(plane, x, z) !== key) continue;
                resolved.push({id, dir: 0, x, z});
            }
            if (resolved.length === 0) return;
            const worldWidthUnits = manifest.botXTiles * 128;
            // The cell's OWN plane's heights (this may be a hidden floor's
            // rebuild) — and its groups start hidden unless its floor is the
            // active one, so cross-floor rebuilds can never leak on screen.
            const cellHeightAt = heightAtOf(plane);
            // Animated placements (fires, torches…) leave the static merge
            // and get one assembled group per frame index instead; windmill
            // sails (WINDMILL_ID) leave it too and get a per-placement spin
            // pivot.
            const animated = resolved.filter(pl => objLib!.anims.has(pl.id));
            const windmills = resolved.filter(pl => pl.id === WINDMILL_ID);
            const statics2 = resolved.filter(pl =>
                !objLib!.anims.has(pl.id) && pl.id !== WINDMILL_ID);
            if (statics2.length > 0) {
                const group = new THREE.Group();
                group.userData.kind = "scenery";
                group.userData.plane = plane;
                for (const {tex, geometry} of assembleCell(objLib, statics2,
                        worldWidthUnits, cellHeightAt, sceneryIdFor)) {
                    group.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
                }
                group.visible = plane === activePlane;
                sceneryMeshes.set(key, group);
                scene.add(group);
            }
            if (animated.length > 0) {
                const maxFrames = Math.max(...animated.map(
                    pl => objLib!.anims.get(pl.id)!.length));
                const frames: THREE.Group[] = [];
                for (let f = 0; f < maxFrames; f++) {
                    const g = new THREE.Group();
                    g.userData.kind = "scenery";
                    g.userData.plane = plane;
                    const framed = animated.map(pl => {
                        const models = objLib!.anims.get(pl.id)!;
                        return {...pl, model: models[f % models.length]};
                    });
                    for (const {tex, geometry} of assembleCell(objLib, framed,
                            worldWidthUnits, cellHeightAt, sceneryIdFor)) {
                        g.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
                    }
                    g.visible = plane === activePlane && f === 0;
                    frames.push(g);
                    scene.add(g);
                }
                animMeshes.set(key, frames);
            }
            if (windmills.length > 0) {
                const mills: {group: THREE.Group; axis: THREE.Vector3}[] = [];
                for (const pl of windmills) {
                    const hub = sceneryHub(objLib, pl, worldWidthUnits, cellHeightAt);
                    if (!hub) continue;
                    const pivot = new THREE.Group();
                    pivot.userData.kind = "scenery";
                    pivot.userData.plane = plane;
                    pivot.position.copy(hub);
                    for (const {tex, geometry} of assembleCell(
                        objLib, [pl], worldWidthUnits, cellHeightAt, sceneryIdFor)) {
                        // Recentre the baked (world-space) geometry on the hub
                        // so the pivot rotates the sails in place.
                        geometry.translate(-hub.x, -hub.y, -hub.z);
                        pivot.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
                    }
                    // Spin axis = the model's local x (client pitch), carried
                    // through assembleCell's yaw bake into world space, then
                    // mirrored into three-x. yawR matches assembleCell exactly.
                    const yawR = (pl.dir * 32 * 2 * Math.PI) / 256;
                    const axis = new THREE.Vector3(
                        -Math.cos(yawR), 0, -Math.sin(yawR)).normalize();
                    pivot.visible = plane === activePlane;
                    mills.push({group: pivot, axis});
                    scene.add(pivot);
                }
                windmillMeshes.set(key, mills);
            }
        };

        // ---- Client-side door/boundary assembly -------------------------
        let doorDefs: Map<number, DoorDefLite> | null = null;
        const doorsByCell = new Map<string, BoundaryPlacement[]>();
        const staticDoorByEdge = new Map<string, BoundaryPlacement>();
        const doorOverrides = new Map<string, number>(); // edgeKey -> observed id
        const doorMeshes = new Map<string, THREE.Group>();
        const edgeKeyOf = (plane: number, x: number, z: number, dir: number) =>
            `${plane}:${x},${z},${dir}`;

        const disposeDoorCell = (key: string) => {
            const g = doorMeshes.get(key);
            if (!g) return;
            scene.remove(g);
            g.traverse(o => {
                if (o instanceof THREE.Mesh) o.geometry.dispose();
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
                resolved.push(ov != null ? {...b, id: ov} : b);
            }
            for (const [ek, id] of doorOverrides) {
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
            // This cell's own plane's terrain (may be a hidden floor's rebuild).
            const planeHeightAt = heightAtOf(plane);
            const corner = (cx: number, cz: number) => planeHeightAt(cx - 0.5, cz - 0.5);
            for (const {tex, geometry} of assembleDoors(doorDefs, resolved,
                    worldWidthUnits, corner,
                    b => packDoorId(b.x, b.z, b.dir))) {
                group.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
            }
            group.visible = plane === activePlane;
            doorMeshes.set(key, group);
            scene.add(group);
        };

        let doorDataLoading = false;
        const loadDoorData = () => {
            if (doorDefs || doorDataLoading) return;
            doorDataLoading = true;
            fetchDoorData().then(d => {
                doorDataLoading = false;
                if (disposed || !d) return;
                doorDefs = d.defs;
                for (const b of d.boundaries) {
                    const ck = cellKeyOf(b.floor, b.x, b.z);
                    let arr = doorsByCell.get(ck);
                    if (!arr) doorsByCell.set(ck, arr = []);
                    arr.push(b);
                    staticDoorByEdge.set(edgeKeyOf(b.floor, b.x, b.z, b.dir), b);
                }
                for (const key of terrainReady) dirtyCells.add(key);
            });
        };

        // ---- Hover + universal selection --------------------------------
        // One resolver decides what's under the cursor (entity > scenery >
        // tile); hover outlines it and highlights its footprint tiles; click
        // selects (bots -> inspector, npcs/players -> info card).
        type Target =
            | {type: "entity"; key: string; kind: string; name?: string | null;
               npcId?: number; appearance?: string | null;
               x: number; z: number; inCombat?: boolean; ghost?: boolean}
            | {type: "object"; id: number; name: string; ax: number; az: number;
               dir: number; w: number; h: number}
            | {type: "wall"; id: number; name: string; x: number; z: number;
               dir: number}
            | {type: "grounditem"; id: number; name?: string | null;
               x: number; z: number}
            | {type: "tile"; x: number; z: number}
            | null;

        // Tile/footprint highlight: a small pool of translucent ground quads.
        const TILE_POOL = 16;
        const tileGeo = new THREE.BufferGeometry();
        tileGeo.setAttribute("position",
            new THREE.BufferAttribute(new Float32Array(TILE_POOL * 4 * 3), 3));
        const tileIdx = new Uint16Array(TILE_POOL * 6);
        for (let i = 0; i < TILE_POOL; i++) {
            tileIdx.set([i * 4, i * 4 + 1, i * 4 + 2, i * 4, i * 4 + 2, i * 4 + 3], i * 6);
        }
        tileGeo.setIndex(new THREE.BufferAttribute(tileIdx, 1));
        tileGeo.setDrawRange(0, 0);
        const tileMat = new THREE.MeshBasicMaterial({
            color: 0xffe45c, transparent: true, opacity: 0.28,
            depthWrite: false, polygonOffset: true,
            polygonOffsetFactor: -3, polygonOffsetUnits: -3,
            side: THREE.DoubleSide,
        });
        const tileMesh = new THREE.Mesh(tileGeo, tileMat);
        tileMesh.renderOrder = 9;
        tileMesh.frustumCulled = false;
        // Excluded from the depth/id picks: under an override material this
        // quad would write depth+colour and shadow the object beneath it.
        tileMesh.userData.noPick = true;
        scene.add(tileMesh);

        const setTileHighlights = (tiles: {x: number; z: number}[]) => {
            const posA = tileGeo.getAttribute("position") as THREE.BufferAttribute;
            const manifest = stateRef.current.manifest;
            if (!manifest) return;
            const W = manifest.botXTiles * 128;
            let i = 0;
            for (const t of tiles) {
                if (i >= TILE_POOL) break;
                // Corners at terrain height (corner grid: heightAt(x-.5,z-.5)).
                const c = (cx: number, cz: number) =>
                    new THREE.Vector3(W - cx * 128, heightAt(cx - 0.5, cz - 0.5) + 3, cz * 128);
                const v = [c(t.x, t.z), c(t.x + 1, t.z), c(t.x + 1, t.z + 1), c(t.x, t.z + 1)];
                for (let k = 0; k < 4; k++) posA.setXYZ(i * 4 + k, v[k].x, v[k].y, v[k].z);
                i++;
            }
            posA.needsUpdate = true;
            tileGeo.setDrawRange(0, i * 6);
        };

        // Hovered scenery glow: the single object re-assembled, additive.
        let sceneryGlow: THREE.Group | null = null;
        let sceneryGlowKey = "";
        const glowMat = new THREE.MeshBasicMaterial({
            color: 0xffe45c, transparent: true, opacity: 0.4,
            blending: THREE.AdditiveBlending, depthWrite: false,
        });
        const setSceneryGlow = (o: {id: number; dir: number; ax: number; az: number} | null) => {
            const key = o ? `${o.id}:${o.ax},${o.az},${o.dir}` : "";
            if (key === sceneryGlowKey) return;
            sceneryGlowKey = key;
            if (sceneryGlow) {
                scene.remove(sceneryGlow);
                sceneryGlow.traverse(m => {
                    if (m instanceof THREE.Mesh) m.geometry.dispose();
                });
                sceneryGlow = null;
            }
            const manifest = stateRef.current.manifest;
            if (!o || !objLib || !manifest) return;
            const group = new THREE.Group();
            for (const {geometry} of assembleCell(objLib,
                [{id: o.id, dir: o.dir, x: o.ax, z: o.az}],
                manifest.botXTiles * 128, heightAt)) {
                const mesh = new THREE.Mesh(geometry, glowMat);
                mesh.renderOrder = 9;
                group.add(mesh);
            }
            // Must be excluded from the id pick: this is the hovered object's
            // own geometry re-drawn additively, so under the id override it
            // would z-fight the real object and flicker the hover it depends on.
            group.userData.noPick = true;
            sceneryGlow = group;
            scene.add(group);
        };

        // ---- Object respawn tags (scenery mirror of npc spawn ghosts) ------
        // A grey countdown nameplate over each spent object (depleted rock,
        // looted chest). The translucent ghost MODEL was removed — users only
        // wanted the nametag, and the overlay read as visual noise. Tags
        // reposition every frame like the entity nameplates.
        const respawnTagPool = new Map<string, HTMLDivElement>();
        const frameRespawnTags = (plane: number, showTags: boolean,
                                  zoomTiles: number,
                                  toWorld: (x: number, z: number) => THREE.Vector3) => {
            const st = stateRef.current;
            const used = new Set<string>();
            if (showTags && zoomTiles <= 60) {
                // The camera's world matrix updates lazily during render;
                // projecting against last frame's matrix flings these tags
                // around while orbiting (same fix as the entity nameplates).
                camera.updateMatrixWorld();
                const w = host.clientWidth || 800;
                const h = host.clientHeight || 600;
                for (const g of st.respawnGhosts) {
                    if (g.plane !== plane) continue;
                    const k = `${g.x},${g.z}`;
                    const v = toWorld(g.x, g.z).project(camera);
                    // Cull off-screen AND behind-camera (|z|>1): a point behind
                    // the lens projects with flipped x/y and would otherwise
                    // teleport the tag to a bogus spot mid-rotation.
                    if (v.z > 1 || v.z < -1 || v.x < -1.05 || v.x > 1.05
                        || v.y < -1.05 || v.y > 1.05) continue;
                    used.add(k);
                    let div = respawnTagPool.get(k);
                    if (!div) {
                        div = document.createElement("div");
                        div.style.cssText =
                            "position:absolute;transform:translate(-50%,-100%);" +
                            "font:10px monospace;color:rgba(196,200,208,.5);" +
                            "background:rgba(10,14,24,.25);padding:0 4px;" +
                            "border-radius:4px;pointer-events:none;" +
                            "white-space:nowrap;z-index:880000;";
                        entityHost.appendChild(div);
                        respawnTagPool.set(k, div);
                    }
                    const text = `${objLib?.objects.get(g.id)?.name ?? g.id} · ${g.t}t`;
                    if (div.textContent !== text) div.textContent = text;
                    div.style.left = `${((v.x + 1) / 2) * w}px`;
                    div.style.top = `${((1 - v.y) / 2) * h - 26}px`;
                }
            }
            for (const [k, div] of respawnTagPool) {
                if (!used.has(k)) {
                    div.remove();
                    respawnTagPool.delete(k);
                }
            }
        };

        const objectAtTile = (plane: number, x: number, z: number): Target => {
            // Scan a small window so multi-tile footprints match.
            for (let dx = 0; dx >= -4; dx--) {
                for (let dz = 0; dz >= -4; dz--) {
                    const pl = staticByTile.get(tileKeyOf(plane, x + dx, z + dz));
                    if (!pl) continue;
                    const id = overrides.get(tileKeyOf(plane, pl.x, pl.z)) ?? pl.id;
                    const obj = objLib?.objects.get(id);
                    if (!obj) continue;
                    const w = (pl.dir === 0 || pl.dir === 4) ? obj.w : obj.h;
                    const h = (pl.dir === 0 || pl.dir === 4) ? obj.h : obj.w;
                    if (x >= pl.x && x < pl.x + w && z >= pl.z && z < pl.z + h) {
                        return {type: "object", id, name: obj.name,
                            ax: pl.x, az: pl.z, dir: pl.dir, w, h};
                    }
                }
            }
            return null;
        };

        /** Point-to-segment distance in fractional-tile space. */
        const segDist = (px: number, pz: number,
                         x1: number, z1: number, x2: number, z2: number) => {
            const dx = x2 - x1;
            const dz = z2 - z1;
            const len2 = dx * dx + dz * dz;
            const t = len2 === 0 ? 0
                : Math.max(0, Math.min(1, ((px - x1) * dx + (pz - z1) * dz) / len2));
            return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
        };

        // The boundary edge (door/gate/web) nearest the ground point, within
        // ~0.3 tiles. Edges live BETWEEN tiles: dir 0 = the constant-z edge
        // along the tile's low-z side, dir 1 = the constant-x edge, 2/3 the
        // diagonals (mudclient_create_wall_object) — corner c sits between
        // tile centres c-1 and c, i.e. at fractional coordinate c - 0.5.
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
                            dir === 0 ? [x1, z1, x1 + 1, z1]
                            : dir === 1 ? [x1, z1, x1, z1 + 1]
                            : dir === 2 ? [x1 + 1, z1, x1, z1 + 1]
                            : [x1, z1, x1 + 1, z1 + 1];
                        const d = segDist(fx, fz, ex1, ez1, ex2, ez2);
                        if (d < bestD) {
                            bestD = d;
                            best = {type: "wall", id, name: def.name,
                                x: bx, z: bz, dir};
                        }
                    }
                }
            }
            return best;
        };

        // GPU object-pick: render scenery + doors in pickMode into a 1px
        // sub-frustum at the cursor and read back the packed id — the exact
        // object/boundary drawn at that pixel, frontmost and depth- AND
        // alpha-correct. This is what makes "mouse over an object → that object"
        // literal: a tall gate is selectable across its whole silhouette,
        // overlapping posts / post-beside-wall each resolve to the one under the
        // cursor, a doorframe's transparent hole passes through to what's behind
        // it, and the visible floor around a gate still falls through to the
        // tile. Returns an object/wall Target, or null when the pixel isn't an
        // opaque object.
        const idPick = (clientX: number, clientY: number): Target => {
            if (!objLib) return null;
            const r = renderer.domElement.getBoundingClientRect();
            if (!r.width || !r.height) return null;
            const ndcX = ((clientX - r.left) / r.width) * 2 - 1;
            const ndcY = -(((clientY - r.top) / r.height) * 2 - 1);
            const vx = ndcX * camera.right;
            const vy = ndcY * camera.top;
            const eps = (2 * camera.right) / r.width; // ~1px
            const pc = camera.clone();
            pc.left = vx - eps; pc.right = vx + eps;
            pc.bottom = vy - eps; pc.top = vy + eps;
            pc.updateProjectionMatrix();
            const prevTarget = renderer.getRenderTarget();
            const prevClear = renderer.getClearColor(new THREE.Color());
            const prevAlpha = renderer.getClearAlpha();
            const prevBackground = scene.background;
            scene.background = null;
            // Object/terrain/door materials emit their pickId (after their own
            // alpha discard) instead of shaded colour; NoBlending so the packed
            // id bytes reach the buffer unblended.
            for (const m of materials.values()) {
                m.uniforms.pickMode.value = 1;
                m.blending = THREE.NoBlending;
            }
            // Sprites/overlays (noPick) must not occlude the object pass — the
            // billboard hit-test already owns entity precedence.
            const hidden: THREE.Object3D[] = [];
            for (const child of scene.children) {
                if (child.userData.noPick && child.visible) {
                    child.visible = false;
                    hidden.push(child);
                }
            }
            renderer.setRenderTarget(idRT);
            renderer.setClearColor(0x000000, 0); // alpha 0 = sky/miss
            renderer.clear();
            renderer.render(scene, pc);
            renderer.readRenderTargetPixels(idRT, 0, 0, 1, 1, idBuf);
            for (const child of hidden) child.visible = true;
            renderer.setRenderTarget(prevTarget);
            renderer.setClearColor(prevClear, prevAlpha);
            scene.background = prevBackground;
            for (const m of materials.values()) {
                m.uniforms.pickMode.value = 0;
                m.blending = THREE.NormalBlending;
            }
            const a = idBuf[3];
            // Real hit alpha is the marker nibble (8..15); 0 = sky, 255 =
            // terrain (GL default attrib), both "not an object".
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
                return def ? {type: "wall", id: id!, name: def.name, x, z, dir}
                    : null;
            }
            return objectAtTile(plane, x, z);
        };

        // Everything under the cursor, one candidate per layer — the menu
        // builder offers ALL of them (like the stock client's menu); hover
        // and plain selection pick by fixed precedence via resolveTarget.
        type Layers = {
            /** Bot billboard under the cursor — highest priority, always wins
             *  (you must be able to grab your own bot even behind scenery). */
            bot: Entity3D | null;
            /** Nearest-to-camera NON-bot entity (npc/player) whose sprite box is
             *  under the cursor. Competes with obj/wall by true depth. */
            ent: Entity3D | null;
            /** EVERY ground item within reach of the click, nearest first —
             *  stock lists a Take entry per item in a pile. */
            ground: GroundItem3D[];
            wall: Target;
            obj: Target;
            tile: {x: number; z: number} | null;
            /** View-space depths (bigger = farther) for the ent/obj arbitration
             *  so whatever is visually in front wins, matching the render. */
            entDepth: number | null;
            objDepth: number | null;
        };
        const resolveLayers = (clientX: number, clientY: number,
                               stableTile = false): Layers | null => {
            const manifest = stateRef.current.manifest;
            if (!manifest) return null;
            const W = manifest.botXTiles * 128;
            // Camera view axis (into the scene): view-space depth = distance of
            // a world point along it. Bigger = farther from the camera.
            const camPos = camera.position;
            const viewDir = new THREE.Vector3(
                -Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch),
                -Math.cos(pitch) * Math.cos(yaw));
            const depthOf = (wx: number, wy: number, wz: number) =>
                (wx - camPos.x) * viewDir.x + (wy - camPos.y) * viewDir.y
                + (wz - camPos.z) * viewDir.z;
            // Billboard hit-test: clicking a tall sprite's BODY must select the
            // entity — the ground behind it is tiles away, so a ground-point
            // test can't capture it. Bots are collected separately (they always
            // win); among non-bot entities whose box holds the cursor, the one
            // NEAREST the camera wins, and its depth is measured at the cursor's
            // height on the sprite so it can be compared to the object surface
            // depth (→ whatever is visually in front is targeted).
            let bestBot: Entity3D | null = null;
            let bestBotDepth = Infinity;
            let bestEnt: Entity3D | null = null;
            let bestEntDepth = Infinity;
            {
                const r = renderer.domElement.getBoundingClientRect();
                if (r.width && r.height) {
                    const px = clientX - r.left;
                    const py = clientY - r.top;
                    const pxPerUnit = r.width / (camera.right - camera.left);
                    for (const e of entityLayer.current()) {
                        const wx = W - (e.x * 128 + 64);
                        const wyFeet = heightAt(e.x, e.z);
                        const wz = e.z * 128 + 64;
                        const feet = new THREE.Vector3(wx, wyFeet, wz)
                            .project(camera);
                        // Hit box from the entity's REAL sprite size (npc
                        // atlas meta), so a rat next to a knight is
                        // individually clickable. Players/bots use a BODY
                        // width (90), not the 145-unit def box — the box has
                        // wide empty margins that swallowed clicks meant for
                        // objects beside the sprite.
                        const dims = e.kind === "npc" && e.npcId != null
                            ? npcSprites.dims(e.npcId) : null;
                        const wUnits = dims ? Math.max(56, dims.w) : 90;
                        const hUnits = dims ? Math.max(70, dims.h) : 230;
                        // Fighting sprites draw shifted ±30 units screen-x
                        // (combat pair separation) and swing wider frames.
                        const fighting = e.dir === 8 || e.dir === 9;
                        // Stock sides: stance A (npcs) screen-left, B
                        // (players) screen-right — the sprite layers draw
                        // this way, so overlays shift identically.
                        const shift = e.dir === 8 ? -30 : e.dir === 9 ? 30 : 0;
                        const halfW = (wUnits / 2 + (fighting ? 15 : 0))
                            * pxPerUnit;
                        const height = hUnits * pxPerUnit;
                        const sx = ((feet.x + 1) / 2) * r.width
                            + shift * pxPerUnit;
                        const sy = ((1 - feet.y) / 2) * r.height;
                        // Elliptical hit area inscribed in the (stable) sprite
                        // box: an oval hugs a humanoid silhouette — solid
                        // through the body and the gap between walking legs (so
                        // the hit never flickers mid-stride), but it drops the
                        // box's transparent CORNERS, which is where a rectangle
                        // stole clicks meant for objects/tiles beside the
                        // sprite. Shape is animation- and gear-independent
                        // because the box comes from def dims, not the frame.
                        // The top is pulled up to the entity's nametag so there
                        // is no dead gap between head and tag: the plate's bottom
                        // edge sits at feetY − spriteH·pxPerUnit − 12 (see
                        // World3DEntities); min() only ever raises the top.
                        const plateH = dims ? dims.h : (e.appearance ? 220 : 130);
                        const top = Math.min(sy - height,
                            sy - plateH * pxPerUnit - 12);
                        const bottom = sy + 10;
                        const cy = (top + bottom) / 2;
                        const ry = (bottom - top) / 2;
                        const nx = (px - sx) / halfW;
                        const ny = (py - cy) / ry;
                        if (nx * nx + ny * ny > 1) continue;
                        // Depth at the cursor's height up the (vertical)
                        // billboard: feet at sy, head hUnits up at sy-height.
                        const frac = Math.max(0, Math.min(1,
                            (sy - py) / height));
                        const depth = depthOf(wx, wyFeet + frac * hUnits, wz);
                        if (e.kind === "bot") {
                            if (depth < bestBotDepth) {
                                bestBotDepth = depth; bestBot = e;
                            }
                        } else if (depth < bestEntDepth) {
                            bestEntDepth = depth; bestEnt = e;
                        }
                    }
                }
            }
            const p = surfacePoint(clientX, clientY) ?? groundPoint(clientX, clientY);
            if (!p) {
                return {bot: bestBot, ent: bestEnt, ground: [], wall: null,
                    obj: null, tile: null, entDepth: null, objDepth: null};
            }
            const objDepth = depthOf(p.x, p.y, p.z);
            const fx = (W - p.x) / 128 - 0.5;
            const fz = p.z / 128 - 0.5;
            // Ground-ring fallback: a click right around an entity's feet (its
            // selection ring) with no billboard-box hit still selects it. Tight
            // radius — the body is already covered above, and a wide net stole
            // clicks from objects on the NEXT tile. Feeds the same bot/non-bot
            // split; depth = the feet point.
            if (!bestBot && !bestEnt) {
                const ring = entityLayer.nearestEntity(fx, fz, 0.6);
                if (ring) {
                    if (ring.kind === "bot") bestBot = ring;
                    else {
                        bestEnt = ring;
                        bestEntDepth = depthOf(W - (ring.x * 128 + 64),
                            heightAt(ring.x, ring.z), ring.z * 128 + 64);
                    }
                }
            }
            // Sticky rounding for hover (see hoverTileX/Z): hold the current
            // tile until the pick clears its boundary by the hysteresis band,
            // so a pick pinned to a wall's base line doesn't chatter between
            // the two tiles it divides. Clicks pass stableTile=false → exact.
            const stick = (f: number, prev: number | null) => {
                const r = Math.floor(f + 0.5);
                if (!stableTile || prev === null) return r;
                return (f >= prev - 0.5 - HOVER_TILE_HYST
                    && f <= prev + 0.5 + HOVER_TILE_HYST) ? prev : r;
            };
            const tx = stick(fx, stableTile ? hoverTileX : null);
            const tz = stick(fz, stableTile ? hoverTileZ : null);
            if (stableTile) { hoverTileX = tx; hoverTileZ = tz; }
            // Ground items: ALL billboards within ~0.7 tiles of the click,
            // nearest first — a pile lists every item, like the stock menu.
            const ground: {g: GroundItem3D; d: number}[] = [];
            for (const g of stateRef.current.groundItems) {
                const d = Math.hypot(g.x - fx, g.z - fz);
                if (d < 0.7) ground.push({g, d});
            }
            ground.sort((a, b) => a.d - b.d);
            const plane = kindsFor(stateRef.current.floor).plane;
            // Objects/doors come from the GPU id-pick (the pixel under the
            // cursor), so "mouse over it → select it" is literal and gates are
            // grabbable across their whole height. objectAtTile is kept only as
            // a footprint fallback for the pixel-missed cases (out-of-range
            // coords). The old proximity wallAtPoint is used ONLY until the id
            // system is ready — once it is, we trust "no object pixel here" so
            // gates never steal the floor tiles beside them.
            const idHit = idPick(clientX, clientY);
            let wall: Target = null;
            let obj: Target = null;
            if (idHit) {
                if (idHit.type === "wall") wall = idHit; else obj = idHit;
            } else if (!objLib) {
                wall = wallAtPoint(plane, fx, fz);
                obj = objectAtTile(plane, tx, tz);
            } else {
                // id-pick is authoritative for every object it can encode
                // (anchor tile < 4096). A miss here means the cursor is NOT over
                // such an object's pixels → keep the tile, even if an object
                // stands on it (this is what stops a gate lighting up when you
                // hover the floor tile beneath it). Footprint-fall-back ONLY for
                // objects the id-pick is physically blind to — anchors past the
                // packable range, which carry no baked id.
                const fb = objectAtTile(plane, tx, tz);
                if (fb && fb.type === "object"
                    && (fb.ax >= 4096 || fb.az >= 4096)) obj = fb;
            }
            return {bot: bestBot, ent: bestEnt, ground: ground.map(e => e.g),
                wall, obj, tile: {x: tx, z: tz},
                entDepth: bestEnt ? bestEntDepth : null, objDepth};
        };

        (window as any).__w3dResolve =
            (x: number, y: number) => resolveTarget(x, y);
        const resolveTarget = (clientX: number, clientY: number,
                               stableTile = false): Target => {
            const L = resolveLayers(clientX, clientY, stableTile);
            if (!L) return null;
            // 1. A bot billboard always wins — you must be able to grab your own
            //    bot even when scenery is drawn in front of it.
            if (L.bot) return {type: "entity", ...L.bot};
            // 2. Everything else is "what's visible": an npc/player competes
            //    with the object/door surface by true depth, so whichever is
            //    drawn in front is targeted (object in front of npc → object;
            //    npc in front of player → npc; …).
            const objTgt: Target = L.wall ?? L.obj;
            if (L.ent) {
                if (objTgt && L.objDepth != null && L.entDepth != null
                    && L.objDepth < L.entDepth) return objTgt;
                return {type: "entity", ...L.ent};
            }
            if (L.ground.length > 0) {
                const g = L.ground[0];
                return {type: "grounditem", id: g.id, name: g.name,
                    x: g.x, z: g.z};
            }
            if (objTgt) return objTgt;
            return L.tile ? {type: "tile", x: L.tile.x, z: L.tile.z} : null;
        };

        const applyHover = (tgt: Target) => {
            entityLayer.hoverKey = tgt?.type === "entity" ? tgt.key : null;
            npcSprites.highlightKey =
                tgt?.type === "entity" && tgt.kind === "npc" ? tgt.key : null;
            playerSprites.highlightKey =
                tgt?.type === "entity"
                && (tgt.kind === "bot" || tgt.kind === "player")
                    ? tgt.key : null;
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
            el.style.cursor =
                (tgt?.type === "entity" || tgt?.type === "object"
                 || tgt?.type === "wall") ? "pointer" : "";
        };

        const materials = new Map<number, THREE.RawShaderMaterial>();
        const texLoader = new THREE.TextureLoader();
        const materialFor = (tex: number, baked: number) => {
            let m = materials.get(tex);
            if (m) return m;
            const uniforms: Record<string, THREE.IUniform> = {
                map: {value: null},
                textured: {value: tex >= 0},
                vScroll: {value: 0},
                // 0 normal · 1 id-pick · 2 depth-pick (see idPick/surfacePoint).
                pickMode: {value: 0},
            };
            if (tex >= 0) {
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
                side: THREE.DoubleSide,
            });
            materials.set(tex, m);
            return m;
        };

        // Water texture id (scrolled per frame — rivers, sea, fountains).
        const WATER_TEX = 17;
        let lastAnimCycle = -1;
        (window as any).__w3dAnim = () => ({
            cells: animMeshes.size,
            anims: objLib ? objLib.anims.size : -1,
            visible: [...animMeshes.values()].map(fs =>
                fs.map(f => (f.visible ? 1 : 0)).join("")),
        });
        // Selection audit: the layer's persistent selection/hover keys, to
        // debug "selected char stuck highlighted through walls".
        (window as any).__w3dSel = () => ({
            selectedEntityKey: entityLayer.selectedEntityKey,
            hoverKey: entityLayer.hoverKey,
            propSelected: propsRef.current.selectedBot ?? null,
        });
        // Windmill audit for the harness: pivot count + each visible pivot's
        // current quaternion (should change frame-to-frame while spinning).
        (window as any).__w3dWindmills = () => {
            const out: {plane: number; visible: boolean; quat: number[]}[] = [];
            for (const [, mills] of windmillMeshes) {
                for (const {group} of mills) {
                    out.push({plane: group.userData.plane,
                        visible: group.visible,
                        quat: [group.quaternion.x, group.quaternion.y,
                            group.quaternion.z, group.quaternion.w]});
                }
            }
            return out;
        };
        // Cross-floor audit for the harness: counts of retained groups and
        // any VISIBLE group whose plane isn't the active one (must be 0 —
        // the "leaked staircase" class of bug).
        (window as any).__w3dPlanes = () => {
            let wrongVisible = 0;
            let retained = 0;
            const check = (g: THREE.Group) => {
                if (g.userData.plane !== activePlane) {
                    retained++;
                    if (g.visible) wrongVisible++;
                }
            };
            for (const [, g] of sceneryMeshes) check(g);
            for (const [, g] of doorMeshes) check(g);
            for (const [, fs] of animMeshes) fs.forEach(check);
            for (const [, g] of loaded) check(g);
            return {activePlane, retained, wrongVisible,
                loaded: loaded.size, scenery: sceneryMeshes.size};
        };
        // cellKey -> group of meshes currently in the scene
        const loaded = new Map<string, THREE.Group>();
        const inflight = new Set<string>();
        // Fetch queue for the active floor, nearest cells first. The WHOLE
        // plane is loaded (the world is static and fits in GPU memory);
        // three.js frustum-culls per mesh, so distant cells only cost VRAM.
        let queue: {cell: WorldMeshCell; kind: string; key: string; d: number}[] = [];
        let queuedFloor: FloorKey | null = null;
        const CONCURRENCY = 24;

        const pump = () => {
            const st = stateRef.current;
            const manifest = st.manifest;
            if (!manifest) return;
            const worldWidthUnits = manifest.botXTiles * 128;
            const {plane, kinds} = kindsFor(st.floor);

            if (queuedFloor !== st.floor) {
                queuedFloor = st.floor;
                // Floor switched: other planes' meshes are RETAINED and just
                // hidden (the visibility sweep below) — revisits are instant
                // instead of a full refetch+reassemble, and a stale dirty
                // cell of another plane can only ever rebuild hidden (the
                // cross-floor "leaked staircase" bug). heightAt follows via
                // activePlane; per-plane grids keep every floor's terrain.
                activePlane = plane;
                const botX = (worldWidthUnits - target.x) / 128;
                const botZ = target.z / 128;
                queue = [];
                for (const cell of manifest.cells) {
                    if (cell.plane !== plane) continue;
                    for (const kind of kinds) {
                        if (!cell.kinds.includes(kind)) continue;
                        const key = `p${cell.plane}_${cell.a}_${cell.b}_${kind}`;
                        if (loaded.has(key) || inflight.has(key)) continue;
                        const cx = cell.botX0 + manifest.cellTiles / 2;
                        const cz = cell.botZ0 + manifest.cellTiles / 2;
                        queue.push({cell, kind, key, d: Math.hypot(cx - botX, cz - botZ)});
                    }
                }
                queue.sort((x, y) => x.d - y.d);
            }

            while (inflight.size < CONCURRENCY && queue.length > 0) {
                const {cell, kind, key} = queue.shift()!;
                if (loaded.has(key) || inflight.has(key)) continue;
                inflight.add(key);
                fetch(worldMeshCellUrl(cell, kind, manifest.baked))
                    .then(r => {
                        if (!r.ok) throw new Error(`${r.status}`);
                        return r.arrayBuffer();
                    })
                    .then(buf => {
                        // A cell landing after a floor switch still lands —
                        // it just joins hidden (visibility sweep by plane).
                        const group = new THREE.Group();
                        group.userData.kind = kind;
                        group.userData.plane = cell.plane;
                        const grid = heightsFor(cell.plane);
                        const sink = kind === "terrain"
                            ? (cx: number, cz: number, y: number) =>
                                sinkInto(grid, cx, cz, y)
                            : undefined;
                        for (const {tex, geometry} of parseCell(buf, worldWidthUnits, cell.botX0, cell.botZ0, sink)) {
                            group.add(new THREE.Mesh(geometry, materialFor(tex, manifest.baked)));
                        }
                        group.visible = cell.plane === activePlane;
                        loaded.set(key, group);
                        scene.add(group);
                        if (kind === "terrain") {
                            // Heights for this cell are in — scenery can sit
                            // on real ground now.
                            const ck = cellKeyOf(cell.plane, cell.botX0, cell.botZ0);
                            terrainReady.add(ck);
                            dirtyCells.add(ck);
                        }
                    })
                    .catch(() => { /* transient — floor revisit re-queues */ })
                    .finally(() => {
                        inflight.delete(key);
                        // Chain the next fetch immediately — don't wait for the
                        // next animation frame (frame rate ≠ load rate).
                        if (queue.length > 0) setTimeout(pump, 0);
                    });
            }

            // Visibility sweep (cheap; every pass): only the active plane's
            // meshes show — retained other-floor meshes and hidden-floor
            // rebuilds stay dark — with the roof toggle on top.
            for (const [, group] of loaded) {
                const roofHidden = ROOF_KINDS.has(group.userData.kind)
                    && !stateRef.current.roofs;
                group.visible = group.userData.plane === plane && !roofHidden;
            }
            for (const [, g] of sceneryMeshes) {
                g.visible = g.userData.plane === plane;
            }
            for (const [, g] of doorMeshes) {
                g.visible = g.userData.plane === plane;
            }
            for (const [, frames] of animMeshes) {
                if (frames.length > 0 && frames[0].userData.plane !== plane) {
                    for (const f of frames) f.visible = false;
                }
            }
            for (const [, mills] of windmillMeshes) {
                for (const {group} of mills) {
                    group.visible = group.userData.plane === plane;
                }
            }

            // Live object-state overrides: diff what bots observe against the
            // static placements; changed tiles dirty their cell only.
            if (st.observedRev !== lastObservedRev && objLib) {
                lastObservedRev = st.observedRev;
                if (doorDefs) {
                    for (const d of st.observedDoors) {
                        const ek = edgeKeyOf(d.plane, d.x, d.z, d.dir);
                        const stat = staticDoorByEdge.get(ek);
                        if (!stat || stat.id !== d.id) {
                            if (doorOverrides.get(ek) !== d.id) {
                                doorOverrides.set(ek, d.id);
                                dirtyCells.add(cellKeyOf(d.plane, d.x, d.z));
                            }
                        } else if (doorOverrides.has(ek)) {
                            doorOverrides.delete(ek);
                            dirtyCells.add(cellKeyOf(d.plane, d.x, d.z));
                        }
                    }
                }
                const fresh = new Map<string, number>();
                for (const o of st.observed) {
                    const tk = tileKeyOf(o.plane, o.x, o.z);
                    const stat = staticByTile.get(tk);
                    if (!stat || stat.id !== o.id) {
                        fresh.set(tk, o.id); // replaced (or spawned) object
                    }
                }
                // New/changed overrides
                for (const [tk, id] of fresh) {
                    if (overrides.get(tk) !== id) {
                        overrides.set(tk, id);
                        const [p0, xz] = tk.split(":");
                        const [x, z] = xz.split(",").map(Number);
                        dirtyCells.add(cellKeyOf(parseInt(p0, 10), x, z));
                    }
                }
                // Overrides contradicted by a fresh original-id observation
                for (const o of st.observed) {
                    const tk = tileKeyOf(o.plane, o.x, o.z);
                    const stat = staticByTile.get(tk);
                    if (stat && stat.id === o.id && overrides.has(tk)) {
                        overrides.delete(tk);
                        dirtyCells.add(cellKeyOf(o.plane, o.x, o.z));
                    }
                }
            }
            // Rebuild a few dirty scenery cells per pass.
            if (objLib) {
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
            setStatus(inflight.size + queue.length > 0
                ? `loading ${loaded.size}/${total} cells…` : "");
        };

        let disposed = false;
        let objLibLoading = false;
        // The library 503s while the runner is mid-bake — keep retrying (the
        // 5s interval below re-calls this until it lands).
        const loadObjLib = (baked: number) => {
            if (objLib || objLibLoading) return;
            objLibLoading = true;
            ObjectLibrary.fetch(baked).then(lib => {
                objLibLoading = false;
                if (disposed || !lib) return;
                objLib = lib;
                for (const key of terrainReady) dirtyCells.add(key);
            });
        };
        fetchWorldMeshManifest()
            .then(m => {
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
            .catch(e => setStatus(`world mesh not ready (${e.message ?? e}) — retrying…`));
        const retry = setInterval(() => {
            if (!stateRef.current.manifest) {
                fetchWorldMeshManifest().then(m => {
                    stateRef.current.manifest = m;
                    setStatus("");
                    pump();
                }).catch(() => {});
            } else {
                if (!objLib) loadObjLib(stateRef.current.manifest.baked);
                loadDoorData();
                loadNpcAtlas();
                loadItemAtlas();
                loadChatFont();
            }
        }, 5000);

        // ---- Fly-by evaluation (cyclic) ---------------------------------
        // The tour is a closed loop: waypoint i connects to (i+1) mod N, the
        // last wrapping back to the first. Precompute cyclic segment times and a
        // continuously-unwrapped yaw so both the spline and the heading join
        // seamlessly across the wrap.
        const flyPts = FLYBY.map(w => ({...w}));
        const flyN = flyPts.length;
        // Shortest per-leg yaw deltas around the whole cycle (incl. the wrap
        // leg). Their sum is the net turn per loop — always a multiple of 360,
        // so the heading returns to its start with matching velocity.
        const flyDyaw: number[] = [];
        for (let i = 0; i < flyN; i++) {
            let d = flyPts[(i + 1) % flyN].yaw - flyPts[i].yaw;
            d = ((d + 180) % 360 + 360) % 360 - 180;
            flyDyaw.push(d);
        }
        const flyYawWind = flyDyaw.reduce((a, b) => a + b, 0);
        const flyYawCum = [flyPts[0].yaw];
        for (let i = 0; i < flyN; i++) flyYawCum.push(flyYawCum[i] + flyDyaw[i]);
        // Continuous unwrapped yaw at any (cyclic) waypoint index.
        const flyYawU = (k: number): number => {
            const turns = Math.floor(k / flyN);
            const r = ((k % flyN) + flyN) % flyN;
            return flyYawCum[r] + turns * flyYawWind;
        };
        // Duration of the leg ARRIVING at waypoint (i+1); index 0's `sec` is the
        // wrap leg (last → first).
        const flySeg: number[] = [];
        for (let i = 0; i < flyN; i++) flySeg.push(flyPts[(i + 1) % flyN].sec ?? 6);
        const flyT: number[] = [0];
        for (let i = 0; i < flyN; i++) flyT.push(flyT[i] + flySeg[i]);
        const flyTotal = flyT[flyN];
        // t_{k+1} − t_{k−1} around (cyclic) knot k — the denominator for the
        // time-based Hermite tangents.
        const flyDtAround = (k: number) =>
            flySeg[((k % flyN) + flyN) % flyN] + flySeg[(((k - 1) % flyN) + flyN) % flyN];
        const flyAt = (tSec: number) => {
            const manifest = stateRef.current.manifest;
            if (!manifest) return;
            // Constant-speed, cyclic: wrap time into one period and run the
            // Catmull-Rom over cyclic neighbours. No global ease — easing to zero
            // velocity at the ends would STALL the camera at the wrap, breaking
            // the loop; constant flow keeps position AND velocity continuous
            // across the seam. (The earlier per-segment easing that caused the
            // "lag"/hard-stop is gone either way.) Uneven `sec` still shapes the
            // pacing; equal `sec`s give perfectly uniform speed.
            const tt = flyTotal > 0 ? ((tSec % flyTotal) + flyTotal) % flyTotal : 0;
            let i = 0;
            while (i < flyN - 1 && tt >= flyT[i + 1]) i++;
            const u = (tt - flyT[i]) / Math.max(1e-6, flySeg[i]);
            const P = (k: number) => flyPts[((k % flyN) + flyN) % flyN];
            // Time-based Catmull-Rom tangents → C¹ in time across every seam.
            const comp = (get: (w: FlyPoint) => number) => {
                const m0 = (get(P(i + 1)) - get(P(i - 1))) / flyDtAround(i);
                const m1 = (get(P(i + 2)) - get(P(i))) / flyDtAround(i + 1);
                return hermite(get(P(i)), m0, get(P(i + 1)), m1, flySeg[i], u);
            };
            const yawM0 = (flyYawU(i + 1) - flyYawU(i - 1)) / flyDtAround(i);
            const yawM1 = (flyYawU(i + 2) - flyYawU(i)) / flyDtAround(i + 1);
            const yawDeg = hermite(
                flyYawU(i), yawM0, flyYawU(i + 1), yawM1, flySeg[i], u);
            const worldWidthUnits = manifest.botXTiles * 128;
            target.set(worldWidthUnits - comp(w => w.x) * 128, 0, comp(w => w.z) * 128);
            yaw = (yawDeg * Math.PI) / 180;
            pitch = Math.max(PITCH_MIN, Math.min(PITCH_MAX, (comp(w => w.pitch) * Math.PI) / 180));
            viewHeightUnits = Math.exp(comp(w => Math.log(w.zoom * 128)));
            resize();
            applyCamera();
        };

        let lastStream = 0;
        // Follow-cam smoothing: timestamp of the previous follow frame (0 =
        // not currently following → next follow frame snaps instead of easing).
        let followPrevT = 0;
        let raf = 0;
        const loop = (t: number) => {
            raf = requestAnimationFrame(loop);
            // Parked while another tab is showing: the scene, WebGL context and
            // all baked cells stay resident (instant switch-back) but we spend
            // zero GPU/stream work on a hidden canvas. The RAF keeps ticking so
            // the first visible frame renders immediately when the tab returns.
            if (propsRef.current.visible === false) return;
            const st = stateRef.current;
            if (st.manifest) {
                sightLayer.visible = st.sight;
                entityLayer.showPlates = st.tags;
                const sightDirty = st.entitiesRev !== lastEntitiesRev;
                if (sightDirty) {
                    lastEntitiesRev = st.entitiesRev;
                    entityLayer.update(st.entities, t);
                }
                const worldWidthUnits = st.manifest.botXTiles * 128;
                const toWorld = (x: number, z: number) => new THREE.Vector3(
                    worldWidthUnits - (x * 128 + 64), heightAt(x, z), z * 128 + 64);
                if (sightDirty) {
                    // Combined line-of-sight outline around BOTS only
                    // (they're whose view the server streams); entities are
                    // already filtered to the active floor.
                    sightLayer.update(
                        st.entities.filter(e => e.kind === "bot"), toWorld);
                }
                if (st.route !== lastRoute || st.floor !== lastRouteFloor) {
                    lastRoute = st.route;
                    lastRouteFloor = st.floor;
                    rebuildRoute(kindsFor(st.floor).plane, toWorld);
                }
                frameRespawnTags(kindsFor(st.floor).plane, st.tags,
                    viewHeightUnits / 128, toWorld);
                // Scenery animation, stock cadence: water texture scrolls
                // 1px per 20ms client frame (64px loop = 1.28s); fires/
                // torches step their model frame every 6 client frames.
                const water = materials.get(WATER_TEX);
                if (water) {
                    water.uniforms.vScroll.value = -((t / 1280) % 1);
                }
                // Sidebar follow-bot: the camera target tracks the followed
                // bot's (lerped) position unless the user is dragging or a
                // flyby is running.
                const fol = propsRef.current.follow;
                if (fol && !drag && !flightRef.current) {
                    const b = entityLayer.current()
                        .find(c => c.key === `bot:${fol}`);
                    if (b) {
                        const wp = toWorld(b.x, b.z);
                        // Ease the camera toward the bot instead of hard-snapping
                        // to its per-tick lerped position: when server updates
                        // arrive >LERP_MS apart (tick jitter/lag) the lerp
                        // finishes and the bot SITS on a tile till the next
                        // update — a snap makes the whole world stutter stop-go.
                        // A frame-rate-independent critically-damped ease
                        // (τ≈0.12s) glides across those pauses. First follow
                        // frame (followPrevT 0) snaps so enabling follow is
                        // instant.
                        const dt = followPrevT
                            ? Math.min(0.1, (t - followPrevT) / 1000) : 0;
                        const k = dt > 0 ? 1 - Math.exp(-dt / 0.12) : 1;
                        target.x += (wp.x - target.x) * k;
                        target.y = 0;
                        target.z += (wp.z - target.z) * k;
                        applyCamera();
                    }
                    followPrevT = t;
                } else {
                    followPrevT = 0;
                }
                if (projFlights.current.length > 0) {
                    const posByKey = new Map<string, {x: number; z: number}>();
                    const hByKey = new Map<string, number>();
                    for (const c of entityLayer.current()) {
                        posByKey.set(c.key, {x: c.x, z: c.z});
                        const dims = c.kind === "npc" && c.npcId != null
                            ? npcSprites.dims(c.npcId) : null;
                        hByKey.set(c.key,
                            (dims ? dims.h : c.appearance ? 220 : 130) / 2);
                    }
                    projectileLayer.frame(t, projFlights.current,
                        k => posByKey.get(k) ?? null, toWorld,
                        k => hByKey.get(k) ?? 60);
                } else {
                    projectileLayer.frame(t, [], () => null, toWorld, () => 60);
                }
                const animCycle = Math.floor(t / 120);
                if (animCycle !== lastAnimCycle) {
                    lastAnimCycle = animCycle;
                    for (const [, frames] of animMeshes) {
                        // Retained hidden-floor cells stay dark: only the
                        // active plane's frames cycle.
                        const onPlane = frames.length > 0
                            && frames[0].userData.plane === activePlane;
                        for (let f = 0; f < frames.length; f++) {
                            frames[f].visible = onPlane
                                && f === animCycle % frames.length;
                        }
                    }
                }
                // Windmill sails: spin every visible pivot (only the active
                // plane's are shown) about its baked-in axis, wall-clock paced.
                const millAngle = (t / WINDMILL_PERIOD_MS) * Math.PI * 2;
                for (const [, mills] of windmillMeshes) {
                    for (const {group, axis} of mills) {
                        if (group.visible) {
                            group.quaternion.setFromAxisAngle(axis, millAngle);
                        }
                    }
                }
                entityLayer.frame(t, toWorld, groundNormalAt, camera,
                    host.clientWidth || 800, host.clientHeight || 600,
                    viewHeightUnits / 128);
                const {right: camR, up: camU} = screenBasis();
                // Mirrored world: the on-screen right vector is -right.
                const camToward = new THREE.Vector3(
                    Math.cos(pitch) * Math.sin(yaw),
                    Math.sin(pitch),
                    Math.cos(pitch) * Math.cos(yaw));
                const screenRight = camR.multiplyScalar(-1);
                const lift = 160 * Math.sin(pitch);
                npcSprites.frame(t,
                    entityLayer.snapshot("npc", t) as NpcSpriteState[],
                    toWorld, screenRight, camU, yaw, camToward, lift);
                playerSprites.frame(t, [
                    ...entityLayer.snapshot("bot", t),
                    ...entityLayer.snapshot("player", t),
                ] as PlayerSpriteState[], toWorld, screenRight, camU, yaw,
                    camToward, lift);
                groundItems.frame(st.groundItems, toWorld, screenRight, camU,
                    camToward, lift);
            }
            const fl = flightRef.current;
            if (fl) {
                const tSec = (t - fl.start) / 1000;
                flyAt(tSec);
                // Clearing flightRef ends the flyby; in capture mode this is
                // also runCapture's signal that the last frame has rendered.
                if (tSec >= flyTotal) {
                    flightRef.current = null;
                    setFlying(false);
                }
            }
            if (t - lastStream > 250) {
                lastStream = t;
                pump();
            }
            // Drain the coalesced hover: one GPU depth-pick per frame at most,
            // using the freshest cursor position (camera is settled after any
            // follow/pan above). This is what makes hover feel instant without
            // firing a pick per pointermove event.
            if (pendingHover) {
                const h = pendingHover;
                pendingHover = null;
                if (h.kind === "canvas") resolveHover(h.x, h.y);
                else if (h.kind === "plate") hoverPlate(h.key);
                else commitHover(null);
            }
            renderer.render(scene, camera);
        };

        // Deterministic recorder (see the ?capture note by the renderer). Drives
        // the render loop and the demo's NPC-tick loop off one virtual clock so
        // every frame is exactly 1/fps apart, streaming lossless PNGs to a
        // user-picked folder. Runs the whole scripted flyby, then restores the
        // real clock. MUST be invoked straight from a click handler: the folder
        // picker below needs the user gesture, so nothing may `await` before it.
        async function runCapture(size: {w: number; h: number} | null): Promise<void> {
            if (capturing) return;
            capturing = true;
            cancelAnimationFrame(raf);   // take the loop off the real clock
            let dir: FileSystemDirectoryHandle;
            try {
                dir = await pickCaptureDir();   // first await — gesture spent here
            } catch (e) {
                console.error("[capture] no output folder — aborting.", e);
                capturing = false;
                raf = requestAnimationFrame(loop);   // resume the normal loop
                return;
            }
            // Force the recording resolution (if any) before the first frame.
            captureSize = size;
            resize();
            const canvas = renderer.domElement;
            const clock = new VirtualClock(captureFps);
            flightRef.current = {start: clock.now()};
            setFlying(true);
            requestAnimationFrame(loop);   // enqueue the loop on the virtual clock
            // Record exactly one period, [0, flyTotal). We stop one frame short
            // of flyTotal on purpose: the tour is cyclic, so the frame at
            // flyTotal equals the frame at 0 — including it would double the seam
            // and hitch the loop.
            const total = Math.max(1, Math.round(flyTotal * captureFps));
            let frame = 0;
            try {
                for (; frame < total; frame++) {
                    clock.tick();                    // render loop + NPC ticks
                    await clock.yieldMacrotask();    // let React commit positions
                    await writeFrame(dir, frame, await canvasPng(canvas));
                }
            } finally {
                flightRef.current = null;
                setFlying(false);
                clock.uninstall();
                capturing = false;
                captureSize = null;
                resize();                            // restore the live canvas size
                raf = requestAnimationFrame(loop);   // back to the real clock
            }
            console.info(`[capture] wrote ${frame} frames @ ${captureFps}fps. Encode e.g.:\n`
                + `  ffmpeg -framerate ${captureFps} -i %06d.png -c:v libwebp `
                + "-loop 0 -lossless 0 -q:v 75 -fps_mode passthrough flyby.webp");
        }
        // Publish the starter for the Fly button (invoked within its click, so
        // the folder picker's user-gesture requirement is satisfied).
        startCaptureRef.current = runCapture;

        raf = requestAnimationFrame(loop);
        resize();
        applyCamera();

        return () => {
            disposed = true;
            adoptUrlRef.current = null;
            startCaptureRef.current = null;
            for (const key of [...sceneryMeshes.keys(), ...animMeshes.keys(),
                ...windmillMeshes.keys()]) {
                disposeSceneryCell(key);
            }
            for (const key of [...doorMeshes.keys()]) disposeDoorCell(key);
            sightLayer.dispose(scene);
            routeRibbon.dispose(scene);
            projectileLayer.dispose();
            for (const [, div] of respawnTagPool) div.remove();
            respawnTagPool.clear();
            npcSprites.dispose(scene);
            playerSprites.dispose();
            groundItems.dispose(scene);
            entityLayer.dispose(scene);
            entityHost.remove();
            if (urlTimer) clearTimeout(urlTimer);
            clearInterval(retry);
            cancelAnimationFrame(raf);
            ro.disconnect();
            for (const [, group] of loaded) {
                group.traverse(o => {
                    if (o instanceof THREE.Mesh) o.geometry.dispose();
                });
            }
            for (const [, m] of materials) {
                const t = m.uniforms.map?.value as THREE.Texture | null;
                t?.dispose();
                m.dispose();
            }
            renderer.dispose();
            host.removeChild(renderer.domElement);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Floor/roof changes only need a stream pass, which the loop does on its
    // 500ms cadence — but nudge state so it feels immediate.
    return (
        <div style={{position: "relative", width: "100%", height: "100%"}}>
            <div ref={hostRef} style={{position: "absolute", inset: 0, overflow: "hidden"}}/>
            <div style={{
                position: "absolute", top: 8, left: 8, display: "flex", gap: 6,
                // Above the nameplate/overlay tiers (they top out at ~990000),
                // below the right-click menu (2000000), so the controls stay
                // clickable over a crowd of nametags.
                zIndex: 1000000,
                background: "rgba(16,16,26,.8)", padding: "6px 8px", borderRadius: 6,
                alignItems: "center", fontSize: 13, userSelect: "none",
            }}>
                {!controlled && FLOORS.map(f => (
                    <button key={f.key}
                            className={floor === f.key ? "active" : ""}
                            onClick={() => setFloor(f.key)}>
                        {f.label}
                    </button>
                ))}
                {!controlled && <>
                    <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                        <input type="checkbox" checked={roofs}
                               onChange={e => setRoofs(e.target.checked)}/>
                        roofs
                    </label>
                    {!props.hideSight && (
                        <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                            <input type="checkbox" checked={sight}
                                   onChange={e => setSight(e.target.checked)}/>
                            sight
                        </label>
                    )}
                    <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                        <input type="checkbox" checked={tags}
                               onChange={e => setTags(e.target.checked)}/>
                        tags
                    </label>
                    {props.extraToggles}
                    {captureFps > 0 && !flying && (
                        <input type="text" value={captureRes}
                               onChange={e => setCaptureRes(e.target.value)}
                               placeholder={`${window.innerWidth}x${window.innerHeight} (blank = window)`}
                               title="Recording resolution WxH (blank = live canvas size)"
                               style={{marginLeft: 8, width: 150, padding: "2px 6px",
                                   font: "inherit"}}/>
                    )}
                    <button style={{marginLeft: 8}}
                            onClick={() => {
                                // ?capture: record the flyby deterministically.
                                // Called straight from this click so the folder
                                // picker gets its required user gesture.
                                if (captureFps > 0) {
                                    if (flying) return;
                                    let size: {w: number; h: number} | null;
                                    try {
                                        size = parseCaptureSize(captureRes);
                                    } catch (err) {
                                        alert(String(err instanceof Error ? err.message : err));
                                        return;
                                    }
                                    startCaptureRef.current?.(size);
                                    return;
                                }
                                if (flightRef.current) {
                                    flightRef.current = null;
                                    setFlying(false);
                                } else {
                                    flightRef.current = {start: performance.now()};
                                    setFlying(true);
                                }
                            }}>
                        {captureFps > 0
                            ? (flying ? `● Recording ${captureFps}fps…` : "● Record flyby")
                            : (flying ? "Stop" : "Fly")}
                    </button>
                </>}
                {status && <span style={{marginLeft: 8, opacity: .8}}>{status}</span>}
            </div>
            {ctxMenu && (
                <div
                    style={{
                        // Stock right-click menu (rsc-c ui/menu.c): grey D0
                        // box @ alpha 160, cyan "Choose option" header, white
                        // entries turning yellow on hover, bold 12px.
                        position: "absolute",
                        left: Math.max(0, ctxMenu.x - 4),
                        top: Math.max(0, ctxMenu.y - 8),
                        // Above every overlay tier (plates/bars/bubbles top
                        // out at ~990000 and share this stacking context).
                        zIndex: 2000000,
                        background: "rgba(208,208,208,0.63)",
                        padding: "2px 6px 4px 4px",
                        font: "bold 12px sans-serif",
                        whiteSpace: "nowrap",
                        userSelect: "none",
                    }}
                    onPointerDown={e => e.stopPropagation()}
                >
                    <div style={{color: "#0ff", textShadow: "1px 0 #000, 0 1px #000"}}>
                        Choose option
                    </div>
                    {ctxMenu.entries.map((en, i) => (
                        <div key={i}
                             style={{
                                 textShadow: "1px 0 #000, 0 1px #000",
                                 cursor: "pointer",
                                 lineHeight: "15px",
                             }}
                             onMouseEnter={e => {
                                 const v = e.currentTarget
                                     .querySelector("[data-verb]") as HTMLElement;
                                 if (v) v.style.color = "#ff0";
                             }}
                             onMouseLeave={e => {
                                 const v = e.currentTarget
                                     .querySelector("[data-verb]") as HTMLElement;
                                 if (v) v.style.color = "#fff";
                             }}
                             onClick={() => {
                                 en.run?.();
                                 setCtxMenu(null);
                             }}>
                            <span data-verb style={{color: "#fff"}}>{en.verb}</span>
                            {en.target && <span style={{color: en.tcolor ?? "#fff"}}>
                                {" "}{en.target}</span>}
                            {en.suffix && <span style={{color: en.scolor ?? "#fff"}}>
                                {en.suffix}</span>}
                        </div>
                    ))}
                </div>
            )}
            {hover && (
                <div style={{
                    position: "absolute", bottom: 8, left: 8,
                    background: "rgba(16,16,26,.85)", padding: "4px 10px",
                    borderRadius: 6, fontSize: 13, fontFamily: "monospace",
                    pointerEvents: "none",
                }}>
                    {hover}
                </div>
            )}
            {picked && (
                <div style={{
                    position: "absolute", bottom: 8, right: 8, minWidth: 180,
                    background: "rgba(16,16,26,.92)", padding: "8px 12px",
                    borderRadius: 8, fontSize: 13, fontFamily: "monospace",
                    border: "1px solid #3a4664",
                }}>
                    <div style={{display: "flex", justifyContent: "space-between", gap: 12}}>
                        <b>{picked.name ?? picked.key}</b>
                        <span style={{cursor: "pointer", opacity: .7}}
                              onClick={() => setPicked(null)}>✕</span>
                    </div>
                    <div style={{opacity: .85, marginTop: 4}}>
                        {picked.kind}
                        {picked.npcId != null ? ` · id ${picked.npcId}` : ""}
                        {picked.inCombat ? " · in combat" : ""}
                    </div>
                    {picked.appearance && (
                        <div style={{marginTop: 6, borderTop: "1px solid #3a4664",
                                     paddingTop: 6, maxWidth: 260}}>
                            {wornItems(picked.appearance, wearables).map(w => (
                                <div key={w.slot} style={{display: "flex", gap: 8,
                                                          lineHeight: "18px"}}>
                                    <span style={{opacity: .55, width: 52,
                                                  flexShrink: 0}}>{w.slot}</span>
                                    <span>{w.names}</span>
                                </div>
                            ))}
                            {wornItems(picked.appearance, wearables).length === 0 && (
                                <div style={{opacity: .55}}>nothing equipped</div>
                            )}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
