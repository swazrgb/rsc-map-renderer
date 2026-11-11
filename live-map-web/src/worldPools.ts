import type {MapEntity} from "./api";

export interface WorldPools {
    npcs: MapEntity[];
    players: MapEntity[];
    objects: MapEntity[];
    wallObjects: MapEntity[];
    groundItems: MapEntity[];
}

export const EMPTY_WORLD: WorldPools = {
    npcs: [],
    players: [],
    objects: [],
    wallObjects: [],
    groundItems: [],
};

interface ClassDelta {
    u?: unknown[][];
    p?: unknown[][];
    r?: number[];
}

export interface WorldDeltaWire {
    full?: boolean;
    npcs?: ClassDelta;
    players?: ClassDelta;
    objects?: ClassDelta;
    walls?: ClassDelta;
    ground?: ClassDelta;
}

interface PoolMap {
    entities: Map<number, MapEntity>;
    rows: Map<number, unknown[]>;
}

export type WorldPoolMaps = Record<keyof WorldPools, PoolMap>;

function emptyPool(): PoolMap {
    return {entities: new Map(), rows: new Map()};
}

export function emptyPoolMaps(): WorldPoolMaps {
    return {
        npcs: emptyPool(),
        players: emptyPool(),
        objects: emptyPool(),
        wallObjects: emptyPool(),
        groundItems: emptyPool(),
    };
}

const WIRE_KEYS: [keyof WorldPools, keyof Omit<WorldDeltaWire, "full">][] = [
    ["npcs", "npcs"],
    ["players", "players"],
    ["objects", "objects"],
    ["wallObjects", "walls"],
    ["groundItems", "ground"],
];

function decodeRow(r: unknown[]): MapEntity {
    return {
        serverIndex: r[0] as number,
        id: r[1] as number,
        x: r[2] as number,
        z: r[3] as number,
        name: (r[4] ?? null) as string | null,
        dir: (r[5] ?? null) as number | null,
        inCombat: r[6] === 1,
        hp: (r[7] ?? null) as number | null,
        maxHp: (r[8] ?? null) as number | null,
        combatLvl: (r[9] ?? null) as number | null,
        appearance: (r[10] ?? null) as string | null,
        skulled: r[11] === 1 ? true : undefined,
        dmg: (r[12] ?? null) as number | null,
        dmgTick: (r[13] ?? null) as number | null,
        msg: (r[14] ?? null) as string | null,
        msgTick: (r[15] ?? null) as number | null,
        bubble: (r[16] ?? null) as number | null,
        bubbleTick: (r[17] ?? null) as number | null,
    };
}

export function applyWorldDelta(maps: WorldPoolMaps, w: WorldDeltaWire): (keyof WorldPools)[] {
    const changed: (keyof WorldPools)[] = [];
    for (const [k, wk] of WIRE_KEYS) {
        const d = w[wk];
        const pool = maps[k];
        if (w.full) {
            pool.entities.clear();
            pool.rows.clear();
        }
        if (!d && !w.full) continue;
        for (const row of d?.u ?? []) {
            const e = decodeRow(row);
            pool.rows.set(e.serverIndex, row);
            pool.entities.set(e.serverIndex, e);
        }
        for (const patch of d?.p ?? []) {
            const index = patch[0] as number;
            const was = pool.rows.get(index);
            if (!was) continue;
            const row = was.slice();
            for (let c = 1; c + 1 < patch.length; c += 2) row[patch[c] as number] = patch[c + 1];
            pool.rows.set(index, row);
            pool.entities.set(index, decodeRow(row));
        }
        for (const key of d?.r ?? []) {
            pool.entities.delete(key);
            pool.rows.delete(key);
        }
        changed.push(k);
    }
    return changed;
}
