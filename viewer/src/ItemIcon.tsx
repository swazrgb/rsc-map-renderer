import {asset, versioned} from "./api";

export interface ItemAtlasIndex {
    baked: number;
    width: number;
    height: number;
    frames: Map<number, {x: number; y: number; w: number; h: number}>;
}

interface BakedItemAtlas {
    baked: number;
    width: number;
    height: number;
    frames: {id: number; x: number; y: number; w: number; h: number}[];
}

let itemAtlasPromise: Promise<ItemAtlasIndex | null> | null = null;

/** The baked item sprite atlas, fetched once; null when it cannot be loaded. */
export function getItemAtlas(): Promise<ItemAtlasIndex | null> {
    itemAtlasPromise ??= versioned("/api/world3d/item-atlas.json")
        .then((url) => fetch(url))
        .then((r) => (r.ok ? (r.json() as Promise<BakedItemAtlas>) : null))
        .then((idx) => {
            if (!idx) return null;
            const frames = new Map<number, {x: number; y: number; w: number; h: number}>();
            for (const f of idx.frames) frames.set(f.id, f);
            return {baked: idx.baked, width: idx.width, height: idx.height, frames};
        })
        .catch(() => null);
    return itemAtlasPromise;
}

/** An item's sprite scaled to fit a `box`-pixel square, at most 2x; its id as text while there is no sprite. */
export function ItemIcon({atlas, id, box}: {atlas: ItemAtlasIndex | null; id: number; box: number}) {
    const f = atlas?.frames.get(id);
    if (!atlas || !f) {
        return <span style={{fontSize: 10, opacity: 0.5}}>#{id}</span>;
    }
    const s = Math.min((box - 2) / f.w, (box - 2) / f.h, 2);
    return (
        <div
            style={{
                width: f.w * s,
                height: f.h * s,
                backgroundImage: `url(${asset("/api/world3d/item-atlas.png")}?v=${atlas.baked})`,
                backgroundSize: `${atlas.width * s}px ${atlas.height * s}px`,
                backgroundPosition: `${-f.x * s}px ${-f.y * s}px`,
                imageRendering: "pixelated",
            }}
        />
    );
}
