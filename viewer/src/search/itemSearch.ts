import {words} from "./worldSearch";

/** An item a search found, with how well it matched: higher is better. */
export interface ItemHit {
    id: number;
    name: string;
    score: number;
}

// Players' names for words of item names.
const NICKNAMES: Record<string, string> = {
    addy: "adamantite",
    ammy: "amulet",
    ammie: "amulet",
};

// Whole, prefix, or inside the word; a query word under 3 letters counts only as a prefix.
function wordQuality(q: string, w: string): number {
    if (w === q) return 3;
    if (w.startsWith(q)) return 2;
    if (q.length >= 3 && w.includes(q)) return 1;
    return 0;
}

// Whether `q` is a prefix of each of two or more consecutive words from `at`: "dsword" is d(ragon) sword.
function spans(q: string, ws: string[], at: number, used = 0): boolean {
    if (q === "") return used >= 2;
    if (at >= ws.length) return false;
    const w = ws[at];
    for (let k = Math.min(q.length, w.length); k >= 1; k--) {
        if (w.startsWith(q.slice(0, k)) && spans(q.slice(k), ws, at + 1, used + 1)) return true;
    }
    return false;
}

function queryWordQuality(q: string, ws: string[]): number {
    const alias = NICKNAMES[q];
    let best = 0;
    for (const w of ws) {
        best = Math.max(best, wordQuality(q, w), alias ? wordQuality(alias, w) : 0);
        if (best === 3) return best;
    }
    if (best > 0) return best;
    for (let i = 0; i < ws.length; i++) {
        if (spans(q, ws, i)) return 1.5;
    }
    return 0;
}

/**
 * How well `query` matches an item called `name`; 0 unless every word of the query matches a word of
 * the name, wholly, as its start, inside it, as a nickname, or as the starts of several words in a row.
 * The id typed in full matches best of all.
 */
function itemScore(id: number, name: string, query: string): number {
    const q = query.trim().toLowerCase();
    if (q === "") return 0;
    if (q === String(id)) return 100;
    const qs = words(q);
    if (qs.length === 0) return 0;
    const ws = words(name);
    let total = 0;
    for (const w of qs) {
        const v = queryWordQuality(w, ws);
        if (v === 0) return 0;
        total += v;
    }
    const whole = ws.join(" ");
    const typed = qs.join(" ");
    if (whole === typed) total += 6;
    else if (whole.startsWith(typed)) total += 2;
    // Of equal matches, the one with fewer words left over.
    return total - 0.01 * ws.length;
}

/** The best `limit` items of `items` for `query`, best first; a tie goes to the lower id. */
export function searchItems(
    items: Iterable<readonly [number, string]>,
    query: string,
    limit: number,
    keep: (id: number) => boolean = () => true,
): ItemHit[] {
    const hits: ItemHit[] = [];
    for (const [id, name] of items) {
        if (!keep(id)) continue;
        const score = itemScore(id, name, query);
        if (score > 0) hits.push({id, name, score});
    }
    hits.sort((a, b) => b.score - a.score || a.id - b.id);
    return hits.slice(0, limit);
}

/** An id-to-name map as {@link searchItems} takes it. */
export function itemEntries(names: Record<string, string>): [number, string][] {
    return Object.entries(names).map(([id, name]) => [Number(id), name]);
}
