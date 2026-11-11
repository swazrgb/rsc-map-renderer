async function readJson<T>(r: Response): Promise<T> {
    return (await r.json()) as T;
}

export interface FloorsResponse {
    floors: number[];
    width: number;
    floorHeight: number;
}

export interface BotPosition {
    x: number;
    z: number;
    floor: number;
}

export interface MapEntity {
    serverIndex: number;
    id: number;
    name: string | null;
    x: number;
    z: number;
    inCombat: boolean;
    dir?: number | null;
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
    skulled?: boolean;
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
    projectiles?: {sprite: number; from: number; fromNpc: boolean; to: number; toPlayer: boolean}[];
    dialog?: string[];
    combatLvl?: number | null;
    groups?: string[];
    /** The bank, trade or duel screen on screen; sent only for a bot the view watches. */
    windows?: OpenWindows;
    /** The first line of {@link description}, for a bot a view lists without watching it. */
    doing?: string | null;
}

/** A stack on offer; `noted` for bank notes. An item that does not stack is offered one to a stack. */
export interface ExchangeStack {
    id: number;
    amount: number;
    noted: boolean;
}

/** A duel's rules, each forbidding what it names. */
export interface DuelRules {
    noRetreat: boolean;
    noMagic: boolean;
    noPrayer: boolean;
    noWeapons: boolean;
}

/** An open trade or duel's two sides and both accepts; on the confirm screen, what is about to change hands. */
export interface ExchangeSides {
    mine: ExchangeStack[];
    theirs: ExchangeStack[];
    accepted: boolean;
    theyAccepted: boolean;
    confirm: boolean;
}

export interface TradeWindow extends ExchangeSides {
    partner: string;
}

export interface DuelWindow extends ExchangeSides, DuelRules {
    opponent: string;
}

/**
 * A bank preset: what loading it leaves in the pack and on the body, both empty for one never saved; not `known`, and
 * both empty, until the server has sent it.
 */
export interface BankPreset {
    slot: number;
    known: boolean;
    pack: ExchangeStack[];
    worn: ExchangeStack[];
}

/**
 * The open bank, and the moves the world has in it: `notes` when a withdrawal may come as bank notes, `wornDeposits` when
 * a worn item may be deposited as a carried one; `depositAll` and `depositWorn` put the whole pack or everything worn in
 * at once, `unequip` a worn item straight in, `rearrange` moves the bank's stacks. `presets` holds one entry per preset
 * it keeps, in slot order; absent for a world without them.
 */
export interface BankScreen {
    notes: boolean;
    wornDeposits: boolean;
    depositAll: boolean;
    depositWorn: boolean;
    unequip: boolean;
    rearrange: boolean;
    presets?: BankPreset[];
}

/** The bank, trade or duel screen a bot has open; the closed ones are left out. */
export interface OpenWindows {
    bank?: BankScreen;
    trade?: TradeWindow;
    duel?: DuelWindow;
}

/**
 * A move at the open bank, as both pages' `bank` endpoints take it: `withdraw` or `deposit` `amount` of `item`;
 * `deposit_inventory`, `deposit_worn`, or `unequip` the worn `item`; `swap` or `insert` the stack in bank slot `slot`
 * at `to`; `load_preset` or `save_preset` preset `slot`; or `close`.
 */
export interface BankMove {
    action:
        | "withdraw"
        | "deposit"
        | "deposit_inventory"
        | "deposit_worn"
        | "unequip"
        | "swap"
        | "insert"
        | "load_preset"
        | "save_preset"
        | "close";
    item?: number;
    amount?: number;
    noted?: boolean;
    slot?: number;
    to?: number;
}

/** A move in the open trade: the player's whole side, or one of the screens' buttons. */
export type TradeMove = {action: "offer"; items: ExchangeStack[]} | {action: "accept" | "confirm" | "decline"};

/** A move in the open duel: the stake, the rules, or one of the screens' buttons. */
export type DuelMove = TradeMove | {action: "rules"; rules: DuelRules};

export interface ObjectRespawn {
    x: number;
    z: number;
    id: number;
    t: number;
}

export interface ObjectDef {
    id: number;
    model: number;
    w: number;
    h: number;
    lift: number;
    name: string;
    cmd1?: string;
    cmd2?: string;
}

export interface GlobalPlayer {
    x: number;
    z: number;
    name: string | null;
}

export interface SkillState {
    level: number;
    maxLevel: number;
    xp: number;
}

export interface InventoryItem {
    slot: number;
    id: number;
    amount: number;
    equipped: boolean;
    /** A bank note for the item. */
    noted?: boolean;
}

export interface BankSlot {
    slot: number;
    id: number;
    amount: number;
}

export interface QuestState {
    id: number;
    name: string;
    stage: number;
    miniquest: boolean;
    stageLabel: string | null;
}

/** A bot's panel (the `bot` topic): its skills, inventory, bank and quests. */
export interface BotPanelState {
    status: string;
    skills: Record<string, SkillState>;
    inventory: InventoryItem[];
    /** The inventory's slots in the pack; the rest of it is worn outside the pack. */
    packSlotsUsed?: number;
    /** As the bank screen last showed it. */
    bank: BankSlot[];
    bankMaxSize: number | null;
    /** False until the bank has been opened, when an empty `bank` says nothing; unset where it is not known. */
    bankSeen?: boolean;
    quests: QuestState[];
    questPoints: number;
}

export interface ItemHolder {
    username: string;
    inventory: number;
    bank: number;
    equipped: boolean;
    perHour: number;
}

export interface ItemAggregate {
    id: number;
    name: string;
    total: number;
    inInventory: number;
    inBank: number;
    perHour: number;
    holders: ItemHolder[];
}

export type ItemDefs = Record<string, string>;

/** One row of the items list (`items/owned`): an item's totals, its holders counted. */
export interface ItemRow {
    id: number;
    name: string;
    total: number;
    inInventory: number;
    inBank: number;
    perHour: number;
    bots: number;
}

export async function fetchItemDefs(): Promise<ItemDefs> {
    const r = await fetch("/api/items/defs");
    if (!r.ok) throw new Error(`/api/items/defs → ${r.status}`);
    return readJson(r);
}

export interface Shop {
    id: string;
    name: string;
    kind: string;
    general: boolean;
    gated: boolean;
    x: number;
    z: number;
    npcIds: number[];
    sells: number[];
    buys: number[];
    buyModifier: number;
    sellModifier: number;
}

export interface TransportProperties {
    kind: "transport";
    name: string;
    cost: number;
    dx: number;
    dz: number;
    dfloor: number;
    req?: string;
}

interface DoorProperties {
    kind: "door";
    id: number;
    dir: number;
    openable: boolean;
    source: "boundary" | "scenery";
}

export interface NpcSpawnProperties {
    kind: "npcSpawn";
    serverIndex: number;
    id: number;
    name: string;
    aggressive?: boolean;
    combatLevel?: number;
    hits?: number;
    attack?: number;
    strength?: number;
    defense?: number;
    attackable?: boolean;
    ranged?: boolean;
    members?: boolean;
    respawnTime?: number;
    description?: string;
    box?: [number, number, number, number];
}

export type FeatureProperties = TransportProperties | DoorProperties | NpcSpawnProperties;

export async function fetchFloors(): Promise<FloorsResponse> {
    const r = await fetch("/api/map/floors");
    if (!r.ok) throw new Error(`/api/map/floors → ${r.status}`);
    return readJson(r);
}

// Each floor's GeoJSON is fetched once per page, so a revisit has it on the render that switches to it. A
// failed fetch is forgotten, to be retried by the next ask.
const floorGeoJsonAsked = new Map<number, Promise<GeoJSON.FeatureCollection>>();
const floorGeoJsonLoaded = new Map<number, GeoJSON.FeatureCollection>();

export function fetchFloorGeoJson(floor: number): Promise<GeoJSON.FeatureCollection> {
    let p = floorGeoJsonAsked.get(floor);
    if (!p) {
        p = (async () => {
            const r = await fetch(`/api/map/floor/${floor}.geojson`);
            if (!r.ok) throw new Error(`/api/map/floor/${floor}.geojson → ${r.status}`);
            const fc = await readJson<GeoJSON.FeatureCollection>(r);
            floorGeoJsonLoaded.set(floor, fc);
            return fc;
        })();
        p.catch(() => floorGeoJsonAsked.delete(floor));
        floorGeoJsonAsked.set(floor, p);
    }
    return p;
}

/** The floor's GeoJSON if it has been fetched, without asking for it. */
export function loadedFloorGeoJson(floor: number): GeoJSON.FeatureCollection | null {
    return floorGeoJsonLoaded.get(floor) ?? null;
}

export function terrainPngUrl(floor: number): string {
    return `/api/map/floor/${floor}.png`;
}

export function wallsFillPngUrl(floor: number): string {
    return `/api/map/floor/${floor}.walls-fill.png`;
}

export function wallsLinesPngUrl(floor: number): string {
    return `/api/map/floor/${floor}.walls-lines.png`;
}

export function dangerPngUrl(floor: number): string {
    return `/api/map/floor/${floor}.danger.png`;
}

export interface FloorDanger {
    floor: number;
    combatLevel: number;
    ceiling: number;
    x: number[];
    z: number[];
    v: number[];
}

export async function fetchFloorDanger(floor: number): Promise<FloorDanger> {
    const r = await fetch(`/api/map/floor/${floor}.danger.json`);
    if (!r.ok) throw new Error(`/api/map/floor/${floor}.danger.json → ${r.status}`);
    return readJson(r);
}

export interface ItemDefFull {
    name: string;
    stackable?: boolean;
    /** Never offered in a trade or a duel, as the game's client refuses to. */
    untradeable?: boolean;
    command?: string;
    wearable?: boolean;
    wield?: boolean;
    basePrice?: number;
}

export async function fetchItemDefsFull(): Promise<Record<string, ItemDefFull>> {
    const r = await fetch("/api/items/defs-full");
    if (!r.ok) throw new Error(`/api/items/defs-full → ${r.status}`);
    return readJson(r);
}

let defsFullPromise: Promise<Record<string, ItemDefFull>> | null = null;

export function getItemDefsFull(): Promise<Record<string, ItemDefFull>> {
    defsFullPromise ??= fetchItemDefsFull().catch(() => {
        defsFullPromise = null;
        return {};
    });
    return defsFullPromise;
}

export interface AuthStatus {
    authed: boolean;
    authEnabled: boolean;
}

export async function fetchAuthStatus(): Promise<AuthStatus> {
    const r = await fetch("/api/auth/status");
    if (!r.ok) throw new Error(`/api/auth/status → ${r.status}`);
    return readJson(r);
}

export async function login(password: string): Promise<AuthStatus> {
    const r = await fetch("/api/auth/login", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({password}),
    });
    if (r.status === 401) return {authed: false, authEnabled: true};
    if (!r.ok) throw new Error(`/api/auth/login → ${r.status}`);
    return readJson(r);
}

export async function logout(): Promise<AuthStatus> {
    const r = await fetch("/api/auth/logout", {method: "POST"});
    if (!r.ok) throw new Error(`/api/auth/logout → ${r.status}`);
    return readJson(r);
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

export async function fetchScenery(): Promise<SceneryPlacement[]> {
    const r = await fetch("/api/world/scenery.json");
    if (!r.ok) throw new Error(`/api/world/scenery.json → ${r.status}`);
    return readJson(r);
}

export async function fetchSceneryAtlasIndex(): Promise<SceneryAtlasIndex> {
    const r = await fetch("/api/map/scenery-atlas.json");
    if (!r.ok) throw new Error(`/api/map/scenery-atlas.json → ${r.status}`);
    return readJson(r);
}

export function sceneryAtlasPngUrl(version: number): string {
    return `/api/map/scenery-atlas.png?v=${version}`;
}

/** A line of players' chat, as the bots heard it; `heardBy` names who heard it, `kill` is a kill announcement's verb. */
export interface ChatMessage {
    timestamp: number;
    sender: string;
    senderIndex: number;
    text: string;
    heardBy: string[];
    outgoing: boolean;
    global: boolean;
    kill: string | null;
}

/** A line the server sent a bot, in its sequence. */
export interface ServerMessage {
    seq: number;
    tick: number;
    timestamp: number;
    text: string;
    type: number;
    colour: string | null;
}

/** A private message a bot sent (`sent`) or got, from or to `peer`. */
export interface PrivateMessage {
    seq: number;
    tick: number;
    timestamp: number;
    bot: string;
    peer: string;
    text: string;
    sent: boolean;
}
