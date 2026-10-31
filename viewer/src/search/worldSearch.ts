import type {FloorTransports, NpcSpawnInfo, SceneryPlacement} from "../api";
import {fetchFloorTransports, fetchScenery, fetchSceneryAtlasIndex} from "../api";
import {fetchDoorData} from "../World3DDoors";

/** The z offset between floors, in tiles: a tile on floor f has z from `f * FLOOR_TILES`. */
export const FLOOR_TILES = 944;
/** Floor names by floor index. */
export const FLOOR_NAME = ["ground floor", "1st floor", "2nd floor", "underground"];

type SearchKind = "npc" | "transport" | "object" | "boundary";

/** One place a search can land on. `z` is absolute, the floor folded in. */
export interface SearchEntry {
    /** Unique across the index. */
    key: string;
    kind: SearchKind;
    title: string;
    /** The NPC or object definition id; null for a transport. */
    defId: number | null;
    /** The entry's own line in the list: a spawn's server index, or what a transport does and requires. */
    detail: string;
    x: number;
    z: number;
    floor: number;
    /** A spawn's definition id, server index and wander box (absolute z); null for anything else. */
    npc: {id: number; serverIndex: number; box: TileBox} | null;
    name: Name;
    /** What the entry is grouped under: kind, normalised title and definition id. */
    groupKey: string;
}

/**
 * What an entry is matched against, shared by every entry with the same title and extra words, so a
 * query scores each distinct name once. `titleWhole` keeps camelCase words whole, for exact search.
 */
interface Name {
    titleWords: string[];
    extraWords: string[];
    titleNorm: string;
    titleWhole: string;
}

export interface TileBox {
    x0: number;
    z0: number;
    x1: number;
    z1: number;
}

// A word matched outside the title (a transport's verb, the object it is fired from) counts for less.
const EXTRA_WEIGHT = 0.75;
const EXACT_BONUS = 6;
const PREFIX_BONUS = 2;

/** Lower-case words, camelCase split ("tollWest" is two), anything but letters and digits a separator. */
export function words(s: string): string[] {
    return s
        .replace(/([a-z])([A-Z])/g, "$1 $2")
        .toLowerCase()
        .split(/[^a-z0-9]+/)
        .filter((w) => w.length > 0);
}

function normalise(s: string): string {
    return words(s).join(" ");
}

// Each camelCase word whole as well, so "DeVinci" is found by "de vinci" and by "devinci".
function indexWords(s: string): string[] {
    const split = words(s);
    const whole = s
        .toLowerCase()
        .split(/[^a-z0-9]+/)
        .filter((w) => w.length > 0 && !split.includes(w));
    return [...split, ...whole];
}

// Whole, prefix, or inside the word; a query word under 3 letters counts only as a prefix.
function wordQuality(q: string, w: string): number {
    if (w === q) return 3;
    if (w.startsWith(q)) return 2;
    if (q.length >= 3 && w.includes(q)) return 1;
    return 0;
}

function bestQuality(q: string, ws: string[]): number {
    let best = 0;
    for (const w of ws) {
        const v = wordQuality(q, w);
        if (v > best) best = v;
        if (best === 3) break;
    }
    return best;
}

/**
 * How well `query`, already split into {@link words}, matches a name; 0 when no word of it does. The
 * query being the whole title, or the start of it, adds a bonus worth more than any count of words.
 */
function score(entry: Name, query: string[], queryNorm: string): number {
    let total = 0;
    for (const q of query) {
        total += Math.max(bestQuality(q, entry.titleWords), EXTRA_WEIGHT * bestQuality(q, entry.extraWords));
    }
    if (total === 0) return 0;
    if (entry.titleNorm === queryNorm) total += EXACT_BONUS;
    else if (entry.titleNorm.startsWith(queryNorm)) total += PREFIX_BONUS;
    return total;
}

const NUMBER_SCORE = 100;

type Alternative =
    | {by: "id"; n: number}
    | {by: "index"; n: number}
    | {by: "words"; query: string[]; queryNorm: string; scores: Map<Name, number>};

// The `|`-separated parts of a query: digits alone are an NPC definition id, `#` and digits a server index.
function alternatives(text: string): Alternative[] {
    const out: Alternative[] = [];
    for (const part of text.split("|")) {
        const t = part.trim();
        const index = /^#(\d+)$/.exec(t);
        if (/^\d+$/.test(t)) out.push({by: "id", n: Number(t)});
        else if (index) out.push({by: "index", n: Number(index[1])});
        else {
            const query = words(t);
            if (query.length > 0) out.push({by: "words", query, queryNorm: query.join(" "), scores: new Map()});
        }
    }
    return out;
}

/** A key equal for two queries exactly when {@link search} answers them alike. */
export function queryKey(text: string, exact: boolean): string {
    const parts = alternatives(text).map((a) =>
        a.by === "words" ? a.queryNorm : `${a.by === "index" ? "#" : ""}${a.n}`,
    );
    return (exact ? "=" : "") + parts.join("|");
}

function matchScore(e: SearchEntry, a: Alternative, exact: boolean): number {
    if (a.by === "id") return e.npc?.id === a.n ? NUMBER_SCORE : 0;
    if (a.by === "index") return e.npc?.serverIndex === a.n ? NUMBER_SCORE : 0;
    let s = a.scores.get(e.name);
    if (s === undefined) {
        const n = e.name;
        s = exact && n.titleNorm !== a.queryNorm && n.titleWhole !== a.queryNorm ? 0 : score(n, a.query, a.queryNorm);
        a.scores.set(n, s);
    }
    return s;
}

/** Entries sharing a kind, normalised title and definition id; `score` is the best of them. */
export interface SearchGroup {
    key: string;
    kind: SearchKind;
    title: string;
    defId: number | null;
    score: number;
    entries: SearchEntry[];
}

/**
 * The groups `text` matches, best first, then shorter titles, then by name; each group's entries in
 * map order, so neighbours on the map are neighbours in the list. `text` is `|`-separated
 * alternatives: words, an NPC definition id ("34"), or a server index ("#1094"); an entry stands at its
 * best alternative, and an id or index match outranks any name. `exact` keeps only names equal to the
 * query, word for word.
 */
export function search(index: readonly SearchEntry[], text: string, exact = false): SearchGroup[] {
    const alts = alternatives(text);
    if (alts.length === 0) return [];
    const groups = new Map<string, SearchGroup>();
    for (const e of index) {
        let s = 0;
        for (const a of alts) s = Math.max(s, matchScore(e, a, exact));
        if (s === 0) continue;
        const key = e.groupKey;
        let g = groups.get(key);
        if (!g) {
            g = {key, kind: e.kind, title: e.title, defId: e.defId, score: s, entries: []};
            groups.set(key, g);
        }
        if (s > g.score) g.score = s;
        g.entries.push(e);
    }
    const out = [...groups.values()].sort(
        (a, b) => b.score - a.score || a.title.length - b.title.length || a.title.localeCompare(b.title),
    );
    for (const g of out) g.entries = mapOrderOf(index, g);
    return out;
}

// Chebyshev distance in tiles, z absolute, so other floors are far apart.
function apart(a: SearchEntry, b: SearchEntry): number {
    return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
}

const AREA_GAP = 24;

/** Whether `b`, listed right after `a`, is more than 24 tiles from it. */
export function newArea(a: SearchEntry, b: SearchEntry): boolean {
    return apart(a, b) > AREA_GAP;
}

const orders = new WeakMap<readonly SearchEntry[], Map<string, SearchEntry[]>>();

// Cached per index and group: a group is usually the same set from one keystroke to the next.
function mapOrderOf(index: readonly SearchEntry[], g: SearchGroup): SearchEntry[] {
    let byGroup = orders.get(index);
    if (!byGroup) {
        byGroup = new Map();
        orders.set(index, byGroup);
    }
    const cached = byGroup.get(g.key);
    if (cached?.length === g.entries.length) {
        const members = new Set(g.entries);
        if (cached.every((e) => members.has(e))) return cached;
    }
    const ordered = mapOrder(g.entries);
    byGroup.set(g.key, ordered);
    return ordered;
}

// From the northernmost entry, each next the nearest one not yet listed (ties to the north, then the
// east). Depends on the entries alone, so the list never reshuffles as the camera moves.
function mapOrder(entries: SearchEntry[]): SearchEntry[] {
    if (entries.length < 2) return entries;
    const cellOf = (v: number) => Math.floor(v / ORDER_CELL);
    const cellKey = (cx: number, cz: number) => cx * CELL_ROW + cz;
    const cells = new Map<number, SearchEntry[]>();
    const left = new Set(entries);
    let first = entries[0];
    for (const e of entries) {
        const k = cellKey(cellOf(e.x), cellOf(e.z));
        let c = cells.get(k);
        if (!c) cells.set(k, (c = []));
        c.push(e);
        if (northOf(e, first)) first = e;
    }
    const take = (e: SearchEntry) => {
        left.delete(e);
        const c = cells.get(cellKey(cellOf(e.x), cellOf(e.z)));
        if (!c) return;
        const i = c.indexOf(e);
        c[i] = c[c.length - 1];
        c.pop();
    };
    const out: SearchEntry[] = [first];
    take(first);
    let cur = first;
    while (left.size > 0) {
        let best = null as SearchEntry | null;
        let bestD = Infinity;
        const consider = (e: SearchEntry) => {
            const d = apart(cur, e);
            if (d < bestD || (d === bestD && best !== null && northOf(e, best))) {
                best = e;
                bestD = d;
            }
        };
        const cx = cellOf(cur.x);
        const cz = cellOf(cur.z);
        // Ring r of grid cells is at least (r - 1) * ORDER_CELL + 1 tiles away: once that passes the best,
        // no further ring can beat it. A ring wider than the entries left scans those instead.
        for (let r = 0; best === null || (r - 1) * ORDER_CELL + 1 <= bestD; r++) {
            if (8 * r > left.size) {
                for (const e of left) consider(e);
                break;
            }
            for (let dz = -r; dz <= r; dz++) {
                const edge = dz === -r || dz === r;
                for (let dx = -r; dx <= r; dx += edge ? 1 : 2 * r) {
                    const c = cells.get(cellKey(cx + dx, cz + dz));
                    if (c) for (const e of c) consider(e);
                }
            }
        }
        if (best === null) break;
        take(best);
        out.push(best);
        cur = best;
    }
    return out;
}

const ORDER_CELL = 32;
// More cells than a grid row can ever hold, so the numeric cell key is unique.
const CELL_ROW = 1 << 16;

function northOf(a: SearchEntry, b: SearchEntry): boolean {
    return a.z < b.z || (a.z === b.z && a.x < b.x);
}

const names = new Map<string, Name>();

function nameOf(title: string, extra: string[]): Name {
    const k = extra.length === 0 ? title : `${title}\u0000${extra.join("\u0001")}`;
    let n = names.get(k);
    if (!n) {
        n = {
            titleWords: indexWords(title),
            extraWords: extra.flatMap(indexWords),
            titleNorm: normalise(title),
            titleWhole: title
                .toLowerCase()
                .split(/[^a-z0-9]+/)
                .filter((w) => w.length > 0)
                .join(" "),
        };
        names.set(k, n);
    }
    return n;
}

function entry(
    key: string,
    kind: SearchKind,
    title: string,
    detail: string,
    at: {x: number; z: number},
    extra: string[],
    defId: number | null = null,
    npc: SearchEntry["npc"] = null,
): SearchEntry {
    return {
        key,
        kind,
        title: capitalise(title),
        defId,
        detail,
        x: at.x,
        z: at.z,
        floor: Math.floor(at.z / FLOOR_TILES),
        npc,
        name: nameOf(title, extra),
        groupKey: `${kind}:${normalise(title)}:${defId ?? ""}`,
    };
}

/** A spawn's wander box, its z clamped to the spawn's own floor. */
export function wanderBox(s: NpcSpawnInfo): TileBox {
    const lo = Math.floor(s.z / FLOOR_TILES) * FLOOR_TILES;
    const hi = lo + FLOOR_TILES - 1;
    const clamp = (z: number) => Math.max(lo, Math.min(hi, z));
    return {x0: s.minX, z0: clamp(s.minZ), x1: s.maxX, z1: clamp(s.maxZ)};
}

/** An entry for every named spawn, in spawn-table order. */
export function npcEntries(spawns: readonly NpcSpawnInfo[]): SearchEntry[] {
    const out: SearchEntry[] = [];
    for (const s of spawns) {
        if (!s.name) continue;
        out.push(
            entry(`npc:${s.serverIndex}`, "npc", s.name, `#${s.serverIndex}`, s, [], s.id, {
                id: s.id,
                serverIndex: s.serverIndex,
                box: wanderBox(s),
            }),
        );
    }
    return out;
}

// A transport's engine name as a title: the part before a `#` variant or `@` placement, underscores as
// spaces. A name that ends in its tile or carries a placement is named by its anchor object instead.
function transportTitle(name: string, objectName: string | null): string {
    const parts = name.split(/[#@]/)[0].split("_");
    let end = parts.length;
    while (end > 1 && /^\d+$/.test(parts[end - 1])) end--;
    const placed = end < parts.length || name.includes("@");
    if (placed && objectName) return objectName;
    return parts.slice(0, end).join(" ").toLowerCase();
}

function capitalise(s: string): string {
    return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1);
}

/**
 * An entry for every transport on every floor, placed at its anchor object, the wall it crosses or
 * else its first entry tile; and for every scenery and boundary object not already a transport's
 * anchor. A source that fails to load is skipped with a warning.
 */
export async function placeEntries(): Promise<SearchEntry[]> {
    const [floors, placements, atlas, doors] = await Promise.all([
        Promise.all(
            [0, 1, 2, 3].map((floor) =>
                fetchFloorTransports(floor).catch(warnEmpty<FloorTransports[number]>(`floor ${floor} transports`)),
            ),
        ),
        fetchScenery().catch(warnEmpty<SceneryPlacement>("scenery")),
        fetchSceneryAtlasIndex().catch(() => ({version: 0, entries: []})),
        fetchDoorData().catch((e: unknown) => {
            console.warn("[search] door library:", e);
            return null;
        }),
    ]);
    const objectNames = new Map<number, string>();
    for (const a of atlas.entries) if (a.name) objectNames.set(a.id, a.name);
    const objectAt = new Map<string, string>();
    for (const p of placements) {
        const name = objectNames.get(p.id);
        if (name) objectAt.set(`${p.floor}:${p.x}:${p.z}`, name);
    }
    const out: SearchEntry[] = [];
    const anchorAt = new Set<string>();
    const wallAt = new Set<string>();
    floors.forEach((list, floor) => {
        list.forEach((t, i) => {
            if (t.anchor) anchorAt.add(`${floor}:${t.anchor.x}:${t.anchor.z}`);
            if (t.boundary) wallAt.add(`${floor}:${t.boundary.x}:${t.boundary.z}:${t.boundary.dir}`);
            const at = t.anchor ?? t.boundary ?? (t.entries.length > 0 ? t.entries[0] : null);
            if (!at) return;
            const objectName = t.anchor ? (objectAt.get(`${floor}:${t.anchor.x}:${t.anchor.z}`) ?? null) : null;
            const label = t.label ?? t.name;
            const detail = t.req ? `${label} · ${t.req}` : label;
            const title = transportTitle(t.name, objectName);
            const extra = [label, t.name, ...(objectName ? [objectName] : [])];
            out.push(
                entry(
                    `transport:${floor}:${i}`,
                    "transport",
                    title,
                    detail,
                    {x: at.x, z: at.z + floor * FLOOR_TILES},
                    extra,
                ),
            );
        });
    });
    const place = (
        kind: "object" | "boundary",
        key: string,
        p: {id: number; x: number; z: number; floor: number},
        name?: string,
    ) => {
        if (name) out.push(entry(key, kind, name, "", {x: p.x, z: p.z + p.floor * FLOOR_TILES}, [], p.id));
    };
    for (const p of placements) {
        const at = `${p.floor}:${p.x}:${p.z}`;
        if (!anchorAt.has(at)) place("object", `object:${at}:${p.id}`, p, objectNames.get(p.id));
    }
    for (const b of doors?.boundaries ?? []) {
        const at = `${b.floor}:${b.x}:${b.z}:${b.dir}`;
        if (!wallAt.has(at)) place("boundary", `boundary:${at}:${b.id}`, b, doors?.defs.get(b.id)?.name);
    }
    return out;
}

function warnEmpty<T>(what: string): (e: unknown) => T[] {
    return (e) => {
        console.warn(`[search] ${what}:`, e);
        return [];
    };
}
