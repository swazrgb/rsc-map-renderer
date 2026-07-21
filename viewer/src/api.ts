/**
 * Open-source viewer data layer.
 *
 * The World3DView component is data-source agnostic: it renders whatever entity
 * data it's handed via props and fetches its static world assets from
 * `/api/world3d/*` + `/api/map/*`. This module provides:
 *
 *   - the shared entity/asset TYPES (mirrors of the server DTOs),
 *   - the static asset FETCHERS (plain GETs a dumb HTTP host answers), and
 *   - STUBBED control verbs (`sendWalk`/`sendInteract`): the open-source build
 *     has nothing to control, so they no-op. A closed-source build supplies its
 *     own api module wiring these to a live server.
 *
 * Player sprites are composited in the browser from a per-layer atlas (see
 * `playerSpriteUrls` -> `playerCompositor`), so any appearance token renders
 * with no server and nothing pre-baked per token.
 */

import {compositePlayerStrip} from "./playerCompositor";

/**
 * Prefix an absolute asset("/api/...") path with the deploy base (Vite's BASE_URL),
 * so the viewer works both at a domain root ("/") and under a GitHub *project*
 * Pages subpath ("/<repo>/"). Set the base via VITE_BASE at build time.
 */
export function asset(path: string): string {
    return import.meta.env.BASE_URL.replace(/\/$/, "") + path;
}

// ---------------------------------------------------------------------------
// Entity + asset types (mirror the server DTOs the viewer consumes)

export interface BotPosition {
    x: number;
    z: number;
    floor: number;
}

/** One map entity in a bot's local view (npc / player / scenery / wall / ground
 *  item). `serverIndex` dedupes across bots; `id` is the def id (0 for players);
 *  `z` is absolute (floor folded in). */
export interface MapEntity {
    serverIndex: number;
    id: number;
    name: string | null;
    x: number;
    z: number;
    inCombat: boolean;
    respawnId?: number;
    respawnName?: string | null;
    respawnTicks?: number;
    /** Tile-edge direction for wall/boundary objects (and facing for scenery). */
    dir?: number | null;
    /** Appearance token (players only): worn sprite ids + colour indices. */
    appearance?: string | null;
    combatLvl?: number | null;
    skulled?: boolean;
    hp?: number | null;
    maxHp?: number | null;
    dmg?: number | null;
    dmgTick?: number | null;
    msg?: string | null;
    msgTick?: number | null;
    bubble?: number | null;
    bubbleTick?: number | null;
}

/** Slim per-tick view for one bot: its own vitals plus its local view of
 *  npcs / players / objects / walls / ground items. Fed to the viewer as props;
 *  the open-source demo synthesizes these, a closed-source app streams them. */
export interface BotLive {
    username: string;
    status: string;
    scriptClass: string | null;
    task?: string | null;
    description: string | null;
    serverTick: number | null;
    position: BotPosition | null;
    fatiguePercent: number | null;
    sleeping: boolean | null;
    inCombat: boolean | null;
    serverIndex?: number;
    appearance?: string | null;
    dir?: number | null;
    hits?: number | null;
    maxHits?: number | null;
    dmg?: number | null;
    groundItems?: MapEntity[];
    npcs?: MapEntity[];
    players?: MapEntity[];
    objects?: MapEntity[];
    wallObjects?: MapEntity[];
    sessionId?: number | null;
    msg?: string | null;
    msgTick?: number | null;
    bubble?: number | null;
    bubbleTick?: number | null;
    projectiles?: {sprite: number; from: number; fromNpc: boolean;
        to: number; toPlayer: boolean}[];
    dialog?: string[];
    combatLvl?: number | null;
    groups?: string[];
}

/** A dead NPC resolved to its static spawn, with a predicted pop window in ticks
 *  (`tMin ≤ pop ≤ t`). Drives the ghost-npc markers. */
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

/** One static NPC spawn (load order = stable serverIndex). */
export interface NpcSpawnInfo {
    serverIndex: number;
    id: number;
    name: string | null;
    x: number;
    z: number;
}

export interface RoutePoint {
    x: number;
    z: number;
    hop: boolean;
}

export interface SceneryPlacement {
    id: number;
    dir: number;
    x: number;
    z: number;
    floor: number;
}

/** One sprite in the top-down scenery atlas. */
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

export interface WorldMeshManifest {
    version: number;
    baked: number;
    cellTiles: number;
    unitsPerTile: number;
    textures: number;
    botXTiles: number;
    botZTiles: number;
    cells: WorldMeshCell[];
}

/** Remote-control verbs (mirrors the server's RemoteWalkBus.Action). Kept for
 *  the `sendInteract` signature; no-op in the open-source build. */
export type InteractAction =
    | "object" | "object2" | "wall_object" | "wall_object2" | "ground_item"
    | "npc_talk" | "npc_attack" | "npc_command1" | "npc_command2"
    | "player_attack" | "player_trade" | "player_follow" | "player_duel"
    | "answer"
    | "item_command" | "item_wield" | "item_unwield" | "item_drop"
    | "use_item_on_object" | "use_item_on_wall_object" | "use_item_on_npc"
    | "use_item_on_ground_item" | "use_item_on_item";

// ---------------------------------------------------------------------------
// Static asset fetchers (plain GETs answered by a dumb HTTP host)

export async function fetchWorldMeshManifest(): Promise<WorldMeshManifest> {
    const res = await fetch(asset("/api/world3d/manifest.json"));
    if (!res.ok) throw new Error(`world3d manifest: ${res.status}`);
    return res.json();
}

export function worldMeshCellUrl(c: WorldMeshCell, kind: string, baked: number): string {
    return asset(`/api/world3d/cell/c_p${c.plane}_${c.a}_${c.b}_${kind}.bin?v=${baked}`);
}

export function worldMeshTextureUrl(id: number, baked: number): string {
    return asset(`/api/world3d/tex/${id}.png?v=${baked}`);
}

export async function fetchScenery(): Promise<SceneryPlacement[]> {
    const r = await fetch(asset("/api/map/scenery.json"));
    if (!r.ok) throw new Error(`/api/map/scenery.json → ${r.status}`);
    return r.json();
}

export async function fetchSceneryAtlasIndex(): Promise<SceneryAtlasIndex> {
    const r = await fetch(asset("/api/map/scenery-atlas.json"));
    if (!r.ok) throw new Error(`/api/map/scenery-atlas.json → ${r.status}`);
    return r.json();
}

export async function fetchNpcSpawns(): Promise<NpcSpawnInfo[]> {
    const r = await fetch(asset("/api/npc-spawns"));
    if (!r.ok) throw new Error(`/api/npc-spawns → ${r.status}`);
    return r.json();
}

export async function fetchWearables(): Promise<Record<number, string[]>> {
    const r = await fetch(asset("/api/items/wearables"));
    if (!r.ok) throw new Error(`/api/items/wearables → ${r.status}`);
    return r.json();
}

// ---------------------------------------------------------------------------
// Host injection. Everything the viewer FETCHES is static and identical on a
// live host or a baked static host — but three verbs differ per deployment: the
// two control verbs, and how player-sprite strips are addressed. A host wires
// those in via `configureViewerHost`; the open-source demo leaves them at their
// defaults (no-op control + static hashed player-sprite files).

export interface ViewerHost {
    sendWalk?(username: string, tile: {x: number; z: number}): Promise<void>;
    sendInteract?(username: string, action: InteractAction,
        x: number, z: number, id?: number, dir?: number, item?: number): Promise<void>;
    playerSpriteUrls?(token: string): Promise<{json: string; png: string}>;
}

let host: ViewerHost = {};

/** Wire a live data source / control into the viewer. Call once at startup. */
export function configureViewerHost(h: ViewerHost): void {
    host = h;
}

/**
 * Player-sprite strip {json,png} URLs. Default (open-source): the appearance is
 * composited in the browser from the per-layer atlas ({@link compositePlayerStrip}),
 * returning blob URLs in the same strip format the viewer consumes — so any
 * token renders with no server and nothing pre-baked per token. A live host
 * injects its own on-demand (`?a=token`) resolver instead.
 */
export async function playerSpriteUrls(token: string): Promise<{json: string; png: string}> {
    if (host.playerSpriteUrls) return host.playerSpriteUrls(token);
    // Open-source default: composite in the browser from the per-layer atlas
    // (Option B) — any token renders with no server and no per-token pre-bake.
    return compositePlayerStrip(token);
}

/** Command the selected bot to walk. No-op unless a host injects a live impl. */
export async function sendWalk(username: string, tile: {x: number; z: number}): Promise<void> {
    if (host.sendWalk) return host.sendWalk(username, tile);
}

/** Queue a one-shot interaction. No-op unless a host injects a live impl. */
export async function sendInteract(
    username: string, action: InteractAction,
    x: number, z: number, id = 0, dir = 0, item = 0,
): Promise<void> {
    if (host.sendInteract) return host.sendInteract(username, action, x, z, id, dir, item);
}
