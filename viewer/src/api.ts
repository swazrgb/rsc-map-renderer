import type {World3DViewProps} from "./World3DView";

/** `path`, an absolute `/api/...` path, under the deploy base (`VITE_BASE`). */
export function asset(path: string): string {
    return import.meta.env.BASE_URL.replace(/\/$/, "") + path;
}

export interface BotPosition {
    x: number;
    z: number;
    floor: number;
}

/**
 * One thing an observer sees: an NPC, player, scenery object, wall object or ground item. Fields
 * left out are unknown. Tile z is absolute: `z = tileZ + floor * 944`, floor 0 ground to 3
 * underground. Event fields (`dmg`, `msg`, `bubble`) show once, at the server tick stamped beside them.
 */
export interface MapEntity {
    /** The server's handle for it, stable while it exists. */
    serverIndex: number;
    /** The definition id: NPC, item or scenery id; 0 for a player. */
    id: number;
    /** Null falls back to the definition's name. */
    name: string | null;
    x: number;
    z: number;
    inCombat: boolean;
    /** The tile edge of a wall object; the facing of anything else. */
    dir?: number | null;
    /** A player's appearance token, as {@link formatAppearance} writes it. */
    appearance?: string | null;
    combatLvl?: number | null;
    skulled?: boolean;
    hp?: number | null;
    maxHp?: number | null;
    /** Damage just taken, 0 for a blocked hit. */
    dmg?: number | null;
    dmgTick?: number | null;
    msg?: string | null;
    msgTick?: number | null;
    bubble?: number | null;
    bubbleTick?: number | null;
    /**
     * An NPC's or player's ground ring colour (0xRRGGBB). Hover, selection and combat colours win over
     * it unless `ringColorFirst`.
     */
    ringColor?: number;
    ringColorFirst?: boolean;
    /** An NPC's or player's nameplate fill (0xRRGGBB), drawn with dark text. */
    tagColor?: number;
    /** Shows the nameplate whatever the tag toggle and zoom say. */
    tagAlways?: boolean;
    /** An outline round the nameplate (0xRRGGBB); hover and selection outlines win over it. */
    tagOutline?: number;
    /** The nameplate drawn bold and bright, its outline thick: one to pick out at a glance. */
    tagStrong?: boolean;
}

/**
 * A player's appearance as the server's appearance packet carries it. `sprites` holds one entry per
 * worn slot in draw order, up to 12: 0 for empty, else the animation id plus 1. `hair`, `top`, `bottom`
 * and `skin` are indices into the hair, clothing, clothing and skin palettes.
 */
export interface Appearance {
    sprites: number[];
    hair: number;
    top: number;
    bottom: number;
    skin: number;
}

/** The appearance as a token, `"s0,s1,…|hair,top,bottom,skin"`. */
export function formatAppearance(a: Appearance): string {
    return a.sprites.join(",") + "|" + [a.hair, a.top, a.bottom, a.skin].join(",");
}

export function parseAppearance(token: string): Appearance {
    const [l = "", c = ""] = token.split("|");
    const sprites = l ? l.split(",").map((n) => parseInt(n, 10) || 0) : [];
    const [hair = 0, top = 0, bottom = 0, skin = 0] = c.split(",").map((n) => parseInt(n, 10) || 0);
    return {sprites, hair, top, bottom, skin};
}

/**
 * Everything the observers see between them, one entry per thing: the feed deduplicates, NPCs and
 * players by server index and the rest by tile. `objects` are live scenery overriding the baked
 * world; `wallObjects` are addressed by tile edge.
 */
export interface WorldEntities {
    npcs: MapEntity[];
    players: MapEntity[];
    objects: MapEntity[];
    wallObjects: MapEntity[];
    groundItems: MapEntity[];
}

/**
 * One vantage point on the world, such as one of your bots, as of one server tick; what it sees
 * arrives pooled across all observers as {@link WorldEntities}. `status`, `scriptClass`, `task`,
 * `description`, `fatiguePercent`, `sessionId`, `dialog` and `groups` are for the host's own UI.
 */
export interface Observer {
    /** The observer's key in the `observers` array. */
    username: string;
    status: string;
    scriptClass: string | null;
    task?: string | null;
    description: string | null;
    serverTick: number | null;
    /** Null draws no sprite of its own. */
    position: BotPosition | null;
    /** No longer live: `position` is where it was last seen, and it draws as a faded ghost. */
    offline?: boolean;
    fatiguePercent: number | null;
    sleeping: boolean | null;
    inCombat: boolean | null;
    /** Its own server index, so it is not drawn again as a player it sees. */
    serverIndex?: number;
    /** Changes when the position jumps rather than walks, so the sprite is placed instead of glided. */
    snapSeq?: number;
    /** Its own ring colour (0xRRGGBB); with `ringColorFirst` it wins over the offline and combat colours. */
    ringColor?: number;
    ringColorFirst?: boolean;
    appearance?: string | null;
    dir?: number | null;
    hits?: number | null;
    skulled?: boolean;
    maxHits?: number | null;
    dmg?: number | null;
    sessionId?: number | null;
    msg?: string | null;
    msgTick?: number | null;
    bubble?: number | null;
    bubbleTick?: number | null;
    /** Projectiles launched this tick, each animated once. */
    projectiles?: {
        /** 0 orb, 1 magic, 2 ranged, 3 gnomeball, 4 skull, 5 spikeball. */
        sprite: number;
        from: number;
        fromNpc: boolean;
        to: number;
        toPlayer: boolean;
    }[];
    dialog?: string[];
    combatLvl?: number | null;
    groups?: string[];
}

/** A dead NPC at its spawn, expected back between `tMin` and `t` ticks from now. */
export interface NpcRespawn {
    serverIndex: number;
    id: number;
    name: string | null;
    x: number;
    z: number;
    t: number;
    tMin?: number;
    assumed?: boolean;
    checkedAgo?: number;
}

/** A depleted scenery object that comes back as `id` in `t` ticks (0 when due); z absolute. */
export interface ObjectRespawn {
    x: number;
    z: number;
    id: number;
    t: number;
}

/** One NPC spawn from the spawn table, which fixes its server index; its wander box is inclusive, z absolute. */
export interface NpcSpawnInfo {
    serverIndex: number;
    id: number;
    name: string | null;
    x: number;
    z: number;
    minX: number;
    minZ: number;
    maxX: number;
    maxZ: number;
}

/** `respawnSeconds` is the baked world's respawn time, 0 for an NPC that never respawns. */
export interface NpcDefInfo {
    id: number;
    name: string | null;
    description: string | null;
    combatLevel: number;
    hits: number;
    attack: number;
    strength: number;
    defense: number;
    maxHit: number;
    attackable: boolean;
    aggressive: boolean;
    ranged: boolean;
    members: boolean;
    respawnSeconds: number;
}

export interface RoutePoint {
    x: number;
    z: number;
    hop: boolean;
}

/** One walked tile of a recorded trail, z absolute; a teleport shows as a jump. */
export interface TrailPoint {
    x: number;
    z: number;
}

export interface SceneryPlacement {
    id: number;
    dir: number;
    x: number;
    z: number;
    floor: number;
}

export interface SceneryAtlasEntry {
    id: number;
    dir: number;
    x: number;
    y: number;
    w: number;
    h: number;
    ax: number;
    ay: number;
    ppt: number;
    tw: number;
    th: number;
    name: string;
}

export interface SceneryAtlasIndex {
    version: number;
    entries: SceneryAtlasEntry[];
}

export interface WorldMeshCell {
    a: number;
    b: number;
    plane: number;
    botX0: number;
    botZ0: number;
    kinds: string[];
}

/** The oldest world3d bake format this viewer can read; a host should refuse an older tree. */
export const REQUIRED_BAKE_VERSION = 23;

export interface WorldMeshManifest {
    version: number;
    baked: number;
    world?: string;
    cellTiles: number;
    unitsPerTile: number;
    textures: number;
    botXTiles: number;
    botZTiles: number;
    cells: WorldMeshCell[];
}

/** The world of a bake that does not name one. */
const UNSTAMPED_WORLD = "uranium";

/** The interactions a host's `sendInteract` may be asked for. */
export type InteractAction =
    | "object"
    | "object2"
    | "wall_object"
    | "wall_object2"
    | "ground_item"
    | "npc_talk"
    | "npc_attack"
    | "npc_command1"
    | "npc_command2"
    | "player_attack"
    | "player_trade"
    | "player_follow"
    | "player_duel"
    | "answer"
    | "item_command"
    | "item_wield"
    | "item_unwield"
    | "item_drop"
    | "use_item_on_object"
    | "use_item_on_wall_object"
    | "use_item_on_npc"
    | "use_item_on_ground_item"
    | "use_item_on_item";

let lastBaked: number | null = null;
let manifestInFlight: Promise<WorldMeshManifest> | null = null;

const BAKE: string = import.meta.env.VITE_BAKE ?? "";

/**
 * The bake's manifest, the stamp every other asset URL is versioned by. A static build's manifest is
 * cached under its build-time stamp; any other is revalidated on every read, so a new bake shows at once.
 */
export function fetchWorldMeshManifest(): Promise<WorldMeshManifest> {
    manifestInFlight ??= (
        BAKE
            ? fetch(asset(`/api/world3d/manifest.json?v=${BAKE}`))
            : fetch(asset("/api/world3d/manifest.json"), {cache: "no-cache"})
    )
        .then((res) => {
            if (!res.ok) throw new Error(`world3d manifest: ${res.status}`);
            return res.json() as Promise<WorldMeshManifest>;
        })
        .then((m) => {
            lastBaked = m.baked;
            return m;
        })
        .finally(() => {
            manifestInFlight = null;
        });
    return manifestInFlight;
}

/** `path` under the deploy base with the bake stamp as `?v=`, reading the manifest first if needed. */
export async function versioned(path: string): Promise<string> {
    const baked = lastBaked ?? (await fetchWorldMeshManifest()).baked;
    return withStamp(path, baked);
}

/** {@link versioned} with the stamp known now; unstamped before the first manifest has been read. */
export function versionedNow(path: string): string {
    return lastBaked == null ? asset(path) : withStamp(path, lastBaked);
}

function withStamp(path: string, baked: number): string {
    return `${asset(path)}${path.includes("?") ? "&" : "?"}v=${baked}`;
}

export function worldMeshCellUrl(c: WorldMeshCell, kind: string, baked: number): string {
    return asset(`/api/world3d/cell/c_p${c.plane}_${c.a}_${c.b}_${kind}.bin?v=${baked}`);
}

export function worldMeshTextureUrl(id: number, baked: number): string {
    return asset(`/api/world3d/tex/${id}.png?v=${baked}`);
}

export async function fetchScenery(): Promise<SceneryPlacement[]> {
    const r = await fetch(await versioned("/api/world/scenery.json"));
    if (!r.ok) throw new Error(`/api/world/scenery.json → ${r.status}`);
    return r.json() as Promise<SceneryPlacement[]>;
}

export async function fetchSceneryAtlasIndex(): Promise<SceneryAtlasIndex> {
    const r = await fetch(await versioned("/api/map/scenery-atlas.json"));
    if (!r.ok) throw new Error(`/api/map/scenery-atlas.json → ${r.status}`);
    return r.json() as Promise<SceneryAtlasIndex>;
}

/** One floor's transports, floor-local. */
export type FloorTransports = NonNullable<World3DViewProps["transports"]>;

type FloorTransportsFile = {world: string; transports: FloorTransports} | FloorTransports;

/** `floor`'s transports; none, with a warning, when they were baked for another world than the mesh. */
export async function fetchFloorTransports(floor: number): Promise<FloorTransports> {
    const path = `/api/map/floor/${floor}.transports.json`;
    const [manifest, json] = await Promise.all([
        fetchWorldMeshManifest(),
        versioned(path)
            .then((url) => fetch(url))
            .then((r) => {
                if (!r.ok) throw new Error(`${path} → ${r.status}`);
                return r.json() as Promise<FloorTransportsFile>;
            }),
    ]);
    const file = Array.isArray(json) ? {world: UNSTAMPED_WORLD, transports: json} : json;
    const meshWorld = manifest.world ?? UNSTAMPED_WORLD;
    if (file.world !== meshWorld) {
        console.warn(`[transports] floor ${floor}: baked for ${file.world}, the map is ${meshWorld}`);
        return [];
    }
    return file.transports;
}

export async function fetchNpcSpawns(): Promise<NpcSpawnInfo[]> {
    const r = await fetch(await versioned("/api/world/npc-spawns.json"));
    if (!r.ok) throw new Error(`/api/world/npc-spawns.json → ${r.status}`);
    return r.json() as Promise<NpcSpawnInfo[]>;
}

export async function fetchNpcDefs(): Promise<NpcDefInfo[]> {
    const r = await fetch(await versioned("/api/world/npc-defs.json"));
    if (!r.ok) throw new Error(`/api/world/npc-defs.json → ${r.status}`);
    return r.json() as Promise<NpcDefInfo[]>;
}

export async function fetchWearables(): Promise<Record<number, string[]>> {
    const r = await fetch(await versioned("/api/world/wearables.json"));
    if (!r.ok) throw new Error(`/api/world/wearables.json → ${r.status}`);
    return r.json() as Promise<Record<number, string[]>>;
}

/** The control verbs a live host provides; the viewer alone controls nothing. */
interface ViewerHost {
    sendWalk?(username: string, tile: {x: number; z: number}): Promise<void>;
    sendInteract?(
        username: string,
        action: InteractAction,
        x: number,
        z: number,
        id?: number,
        dir?: number,
        item?: number,
    ): Promise<void>;
}

let host: ViewerHost = {};

/** Installs the host's control verbs; without one {@link sendWalk} and {@link sendInteract} do nothing. */
export function configureViewerHost(h: ViewerHost): void {
    host = h;
}

export async function sendWalk(username: string, tile: {x: number; z: number}): Promise<void> {
    if (host.sendWalk) return host.sendWalk(username, tile);
}

export async function sendInteract(
    username: string,
    action: InteractAction,
    x: number,
    z: number,
    id = 0,
    dir = 0,
    item = 0,
): Promise<void> {
    if (host.sendInteract) return host.sendInteract(username, action, x, z, id, dir, item);
}
