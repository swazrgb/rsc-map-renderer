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
 *     has nothing to control, so they no-op. A closed-source build wires these
 *     to a live server via `configureViewerHost`.
 *
 * Player sprites need no server at all: any appearance token is composited in
 * the browser from the per-layer atlas (see `playerCompositor`).
 */

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

/**
 * One thing in a bot's local view — an npc, player, scenery object, wall/gate,
 * or ground item. `serverIndex` is the server's stable handle for it (and the
 * dedup key when several observers see it at once); most fields are optional —
 * send only what you know.
 */
export interface MapEntity {
    /** Server's stable id for this entity — the dedup key across observers. */
    serverIndex: number;
    /** Definition id: npc id, item id, or scenery id; `0` for players. */
    id: number;
    /** Display name, or `null` to fall back to the definition's own name. */
    name: string | null;
    /** Tile X. */
    x: number;
    /**
     * Tile Z, **absolute** — the floor is folded in: `z = tileZ + floor*944`
     * (floor 0 = ground … 3 = underground).
     */
    z: number;
    /** Whether it's currently fighting (tints the nameplate). */
    inCombat: boolean;
    /** Tile-edge direction for wall/boundary objects; facing for scenery. */
    dir?: number | null;
    /**
     * Players only — the RSC appearance packet as a token ({@link Appearance}),
     * composited into a sprite in the browser. Build/read it with
     * {@link formatAppearance} / {@link parseAppearance}.
     */
    appearance?: string | null;
    /** Players only — combat level shown on the nameplate. */
    combatLvl?: number | null;
    /** Players only — draws the PK-skull icon. */
    skulled?: boolean;
    /** Current hitpoints (with {@link MapEntity.maxHp}, draws a health bar). */
    hp?: number | null;
    /** Maximum hitpoints. */
    maxHp?: number | null;
    /** Damage just taken (`0` = a blocked hit); flashes a hit splat at {@link MapEntity.dmgTick}. */
    dmg?: number | null;
    /** The server tick {@link MapEntity.dmg} landed on (so each hit splats exactly once). */
    dmgTick?: number | null;
    /** Latest overhead chat line, shown as a bubble at {@link MapEntity.msgTick}. */
    msg?: string | null;
    /** The server tick {@link MapEntity.msg} was said. */
    msgTick?: number | null;
    /** Action-bubble item id (small nameplate icon), popped at {@link MapEntity.bubbleTick}. */
    bubble?: number | null;
    /** The server tick {@link MapEntity.bubble} popped. */
    bubbleTick?: number | null;
}

/**
 * A player's on-screen appearance — the RSC appearance record, one-to-one with
 * what the server's player-appearance packet carries:
 *
 *  - `sprites`: the worn-sprite array, one animation id per appearance slot, in
 *    draw order. `0` = empty slot; a non-zero value is `animationId + 1` (the
 *    +1 keeps 0 free for "nothing"). These already encode the whole outfit —
 *    equipping an item is the server swapping the sprite id in that item's slot,
 *    so there are no separate "item ids" to resolve. Slots 3/4 are weapon/shield;
 *    the rest are the head/body/legs base and armour overrides. Up to 12 slots.
 *  - `hair`/`top`/`bottom`/`skin`: palette indices (not RGB) into the hair,
 *    clothing, clothing and skin ramps — the four colour choices the packet sends.
 *
 * On the wire and on {@link MapEntity.appearance} this is serialized to the
 * compact string `"s0,s1,…|hair,top,bottom,skin"` (e.g. `"4,5,3|1,12,2,3"`): it
 * is one small field per entity per tick and doubles as the compositor's cache
 * key. {@link formatAppearance}/{@link parseAppearance} convert to/from it.
 */
export interface Appearance {
    /** Worn-sprite animation ids per slot (0 = empty), the RSC appearance array. */
    sprites: number[];
    /** Palette index into the hair-colour ramp. */
    hair: number;
    /** Palette index into the clothing ramp (shirt / torso). */
    top: number;
    /** Palette index into the clothing ramp (trousers / legs). */
    bottom: number;
    /** Palette index into the skin ramp. */
    skin: number;
}

/** Serialize an {@link Appearance} to the `"sprites|hair,top,bottom,skin"` token. */
export function formatAppearance(a: Appearance): string {
    return a.sprites.join(",") + "|" + [a.hair, a.top, a.bottom, a.skin].join(",");
}

/** Parse a `"sprites|hair,top,bottom,skin"` token into an {@link Appearance}. */
export function parseAppearance(token: string): Appearance {
    const [l = "", c = ""] = token.split("|");
    const sprites = l ? l.split(",").map(n => parseInt(n, 10) || 0) : [];
    const [hair = 0, top = 0, bottom = 0, skin = 0] =
        c.split(",").map(n => parseInt(n, 10) || 0);
    return {sprites, hair, top, bottom, skin};
}

/**
 * One observer's slim per-tick view: its own vitals plus its local view of the
 * npcs / players / objects / walls / ground items around it. An observer is a
 * live vantage point onto the world — one of your bots, or (to mirror a whole
 * server) every online player. Fed to the viewer as the `observers` prop; the
 * open-source demo synthesizes these, a live app streams them (one snapshot per
 * server tick — the viewer interpolates between ticks). Multiple observers are
 * merged into one world, deduped by `serverIndex` (npcs/players) and tile
 * (ground items / scenery).
 */
export interface Observer {
    /** The observer's account name — its stable key in the `observers` array. */
    username: string;
    /** Free-text run status (e.g. `"online"`). Host UI only; ignored by the viewer. */
    status: string;
    /** The script class driving this observer (bot runs only), or `null`. Host UI only. */
    scriptClass: string | null;
    /** Human-readable current task, if any. Host UI only. */
    task?: string | null;
    /** Longer status / description line. Host UI only. */
    description: string | null;
    /** This observer's current server tick — stamps the timed events below. */
    serverTick: number | null;
    /** The observer's own tile + floor; `null` = it draws no sprite of its own. */
    position: BotPosition | null;
    /** Fatigue, 0–100. Host UI only. */
    fatiguePercent: number | null;
    /** Whether the observer is asleep (Zzz on the nameplate). */
    sleeping: boolean | null;
    /** Whether the observer is in combat (tints the nameplate). */
    inCombat: boolean | null;
    /** The observer's own server index, so it isn't re-drawn as a "player" it sees. */
    serverIndex?: number;
    /** This observer's own appearance token ({@link Appearance}) — drives its sprite. */
    appearance?: string | null;
    /** The observer's facing, 0–7. */
    dir?: number | null;
    /** The observer's current hitpoints (with {@link Observer.maxHits}, draws its health bar). */
    hits?: number | null;
    /** The observer's maximum hitpoints. */
    maxHits?: number | null;
    /** Damage just dealt to the observer (`0` = blocked); flashes a splat at {@link Observer.serverTick}. */
    dmg?: number | null;
    /** Ground items the observer can see. */
    groundItems?: MapEntity[];
    /** NPCs in the observer's view. */
    npcs?: MapEntity[];
    /** Other players in the observer's view. */
    players?: MapEntity[];
    /** Live scenery the observer sees — a mined rock, an opened chest — overriding the static bake. */
    objects?: MapEntity[];
    /** Doors / gates the observer sees, addressed by tile-edge (their `dir`). */
    wallObjects?: MapEntity[];
    /** Server session id. Host UI only. */
    sessionId?: number | null;
    /** The observer's latest overhead chat line (bubble at {@link Observer.msgTick}). */
    msg?: string | null;
    /** The server tick {@link Observer.msg} was said. */
    msgTick?: number | null;
    /** The observer's action-bubble item id (nameplate icon), popped at {@link Observer.bubbleTick}. */
    bubble?: number | null;
    /** The server tick {@link Observer.bubble} popped. */
    bubbleTick?: number | null;
    /** Projectiles (arrows / spells) launched this tick — each animates once, then is gone. */
    projectiles?: {
        /** Which projectile sprite: 0 orb, 1 magic, 2 ranged, 3 gnomeball, 4 skull, 5 spikeball. */
        sprite: number;
        /** The shooter's `serverIndex`. */
        from: number;
        /** `true` if {@link from} is an NPC (else a player/bot). */
        fromNpc: boolean;
        /** The target's `serverIndex`. */
        to: number;
        /** `true` if {@link to} is a player/bot (else an NPC). */
        toPlayer: boolean;
    }[];
    /** The observer's current dialogue lines, if in a menu. Host UI only. */
    dialog?: string[];
    /** The observer's combat level. */
    combatLvl?: number | null;
    /** Swarm / group tags this observer belongs to. Host UI only. */
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

/** A depleted scenery object with a predicted respawn — the scenery mirror of
 *  {@link NpcRespawn}. Each draws a countdown ghost of the object it will become;
 *  the viewer resolves that object's name from the object library. */
export interface ObjectRespawn {
    /** Tile X of the depleted object. */
    x: number;
    /** Absolute tile Z (floor folded in: `z = tileZ + floor*944`). */
    z: number;
    /** The scenery def id the object will respawn as. */
    id: number;
    /** Ticks until it respawns (`0` = due). */
    t: number;
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
// live host or a baked static host; the only things that differ per deployment
// are the two outbound control verbs. A host wires them in via
// `configureViewerHost`; the open-source demo leaves them no-op (it's view-only).
// (Player sprites need no host hook — they're composited in the browser from the
// per-layer atlas for every deployment; see `playerCompositor`.)

export interface ViewerHost {
    sendWalk?(username: string, tile: {x: number; z: number}): Promise<void>;
    sendInteract?(username: string, action: InteractAction,
        x: number, z: number, id?: number, dir?: number, item?: number): Promise<void>;
}

let host: ViewerHost = {};

/** Wire a live control source into the viewer. Call once at startup. */
export function configureViewerHost(h: ViewerHost): void {
    host = h;
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
