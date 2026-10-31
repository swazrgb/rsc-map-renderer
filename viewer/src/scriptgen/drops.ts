import {versioned} from "../api";

/** One line of a drop table: an item, or another table rolled in its place. */
interface DropEntry {
    item?: number;
    table?: string;
    amount?: number;
    weight?: number;
    of?: number;
    noted?: boolean;
    /** Dropped every kill, beside the roll: bones and ashes. */
    always?: boolean;
}

/** The baked world's drop tables: per NPC id, the shared tables by name, and items' base prices. */
export interface NpcDrops {
    world: string;
    npcs: Record<string, DropEntry[]>;
    tables: Record<string, DropEntry[]>;
    values: Record<string, number>;
}

let dropsPromise: Promise<NpcDrops | null> | null = null;

/** The baked drop tables, fetched once; null for a world baked without them. */
export function fetchNpcDrops(): Promise<NpcDrops | null> {
    dropsPromise ??= versioned("/api/world/npc-drops.json")
        .then((url) => fetch(url))
        .then((r) => (r.ok ? (r.json() as Promise<NpcDrops>) : null))
        .catch(() => null);
    return dropsPromise;
}

/** Every item any of `npcIds` can drop, through the shared tables their rolls reach. */
export function possibleDrops(drops: NpcDrops, npcIds: Iterable<number>): Set<number> {
    const items = new Set<number>();
    const seen = new Set<string>();
    const walk = (entries: DropEntry[] | undefined) => {
        for (const e of entries ?? []) {
            if (e.item != null) items.add(e.item);
            else if (e.table != null && !seen.has(e.table)) {
                seen.add(e.table);
                walk(drops.tables[e.table]);
            }
        }
    };
    for (const id of npcIds) walk(drops.npcs[String(id)]);
    return items;
}
