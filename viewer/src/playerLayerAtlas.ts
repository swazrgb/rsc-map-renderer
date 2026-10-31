import * as THREE from "three";
import {asset, versioned} from "./api";

// A crop is ax, ay, w, h, dx, dy.
const CROP_STRIDE = 6;
// 8 walk facings and the two combat stances.
const ORDERS = 10;
const WALKS = 3;
// Slot classes: any slot, slot 3 and slot 4 (see classOfSlot).
const CLASSES = 3;

/**
 * Every wearable sprite frame in one texture, with the alpha byte tagging which recolour a pixel
 * takes (0xFF none, 0xFE hair, 0xFD top, 0xFC bottom, 0xFB skin, 0 empty), so a shader can apply the
 * palettes as the client's blit does. `scale` is atlas pixels per world unit; `layerOrder` holds, per
 * facing, the back-to-front order of the 12 slots.
 */
export interface PlayerLayerAtlas {
    tex: THREE.Texture;
    atlasW: number;
    atlasH: number;
    scale: number;
    originX: number;
    originY: number;
    width1: number;
    height: number;
    layerOrder: number[][];
    palettes: {hair: number[]; clothing: number[]; skin: number[]};
    /** {@link CROP_STRIDE} entries per (animation, slot class, order, walk frame); ax below 0 draws nothing. */
    crops: Int16Array;
    maxAnim: number;
}

/** Where the crop of one frame starts in {@link PlayerLayerAtlas.crops}. */
export function cropIndex(animId: number, slotClass: number, order: number, walk: number): number {
    return (((animId * CLASSES + slotClass) * ORDERS + order) * WALKS + walk) * CROP_STRIDE;
}

/** The crop table's slot class for appearance slot `slot`. */
export function classOfSlot(slot: number): number {
    return slot === 3 ? 1 : slot === 4 ? 2 : 0;
}

interface AtlasIndexJson {
    baked?: number;
    scale: number;
    originX: number;
    originY: number;
    width1: number;
    height: number;
    atlasW: number;
    atlasH: number;
    palettes: {hair: number[]; clothing: number[]; skin: number[]};
    layerOrder: number[][];
    atlas: ([number, number, number, number] | undefined)[];
    crops: Record<string, Partial<Record<string, Partial<Record<string, [number, number, number]>>>>>;
}

let pending: Promise<PlayerLayerAtlas> | null = null;

/** The player layer atlas, fetched once. */
export function loadPlayerLayerAtlas(): Promise<PlayerLayerAtlas> {
    if (pending) return pending;
    pending = (async () => {
        const idx: AtlasIndexJson = await fetch(await versioned("/api/world3d/player-layers/index.json")).then((r) => {
            if (!r.ok) throw new Error(`atlas index ${r.status}`);
            return r.json() as Promise<AtlasIndexJson>;
        });
        const blob = await fetch(
            asset("/api/world3d/player-layers/atlas.png" + (idx.baked != null ? `?v=${idx.baked}` : "")),
        ).then((r) => r.blob());
        // The alpha byte is a tag and the RGB a shade ramp, so neither may be premultiplied or converted.
        const bmp = await createImageBitmap(blob, {
            premultiplyAlpha: "none",
            colorSpaceConversion: "none",
        });
        const tex = new THREE.Texture(bmp);
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.generateMipmaps = false;
        tex.flipY = false;
        tex.premultiplyAlpha = false;
        tex.colorSpace = THREE.NoColorSpace;
        tex.needsUpdate = true;

        let maxAnim = 0;
        for (const k of Object.keys(idx.crops)) {
            const n = parseInt(k, 10);
            if (n > maxAnim) maxAnim = n;
        }
        const crops = new Int16Array((maxAnim + 1) * CLASSES * ORDERS * WALKS * CROP_STRIDE).fill(-1);
        for (const [animKey, byClass] of Object.entries(idx.crops)) {
            const animId = parseInt(animKey, 10);
            for (let cls = 0; cls < CLASSES; cls++) {
                // A slot class with no crop for a frame falls back to class 0.
                const table = byClass[cls === 1 ? "3" : cls === 2 ? "4" : "0"] ?? byClass["0"];
                const fallback = byClass["0"];
                for (let order = 0; order < ORDERS; order++) {
                    for (let walk = 0; walk < WALKS; walk++) {
                        const fk = `${order},${walk}`;
                        const c = table?.[fk] ?? fallback?.[fk];
                        if (!c) continue;
                        const [uid, dx, dy] = c;
                        const tile = idx.atlas[uid];
                        if (!tile) continue;
                        const at = cropIndex(animId, cls, order, walk);
                        crops[at] = tile[0];
                        crops[at + 1] = tile[1];
                        crops[at + 2] = tile[2];
                        crops[at + 3] = tile[3];
                        crops[at + 4] = dx;
                        crops[at + 5] = dy;
                    }
                }
            }
        }
        return {
            tex,
            atlasW: idx.atlasW,
            atlasH: idx.atlasH,
            scale: idx.scale,
            originX: idx.originX,
            originY: idx.originY,
            width1: idx.width1,
            height: idx.height,
            layerOrder: idx.layerOrder,
            palettes: idx.palettes,
            crops,
            maxAnim,
        };
    })();
    return pending;
}

/** Index `i` clamped into `table`. */
export function palClamp(table: number[], i: number): number {
    return Math.max(0, Math.min(table.length - 1, i | 0));
}
