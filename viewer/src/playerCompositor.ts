import {asset} from "./api";

/**
 * Client-side player-sprite compositor (Option B). Instead of the server / a
 * pre-bake producing one flat strip per fixed appearance token, the bake ships
 * a single per-layer atlas (see {@code PlayerLayerAtlasBaker}) of every wearable
 * sprite reduced to recolour-neutral pixels, and this module composes an
 * arbitrary {@code layers|colours} token in the browser — so all appearance
 * combinations render with no server and nothing pre-baked per token.
 *
 * <p>The output is byte-for-byte the same strip format the viewer already
 * consumes ({@code {scale,width,height,frames[{o,f,x,y,w,h,ax,ay}]}} + PNG), so
 * {@link import("./World3DPlayerSprites")} needs no changes — {@link compositePlayerStrip}
 * just returns blob URLs in place of the static files.
 *
 * <p>Recolour replays the engine kernel {@code plot_trans_scale_with_2_masks}:
 * each atlas pixel's alpha byte tags which mask it uses — {@code 0xFF} already
 * final, {@code 0xFE/FD/FC} hair/top/bottom, {@code 0xFB} skin — and RGB holds
 * either the final colour or the gray shade to multiply.
 */

interface AtlasIndex {
    scale: number;
    originX: number;
    originY: number;
    width1: number;
    height: number;
    palettes: {hair: number[]; clothing: number[]; skin: number[]};
    layerOrder: number[][]; // [layerRow][12]
    atlas: [number, number, number, number][]; // uid -> [ax, ay, w, h]
    crops: Record<string, Record<string, Record<string, [number, number, number]>>>;
    // animId -> slotClass -> "order,walk" -> [uid, dx, dy]
}

const SURF = 512;
const T_FIXED = 0xff, T_HAIR = 0xfe, T_TOP = 0xfd, T_BOTTOM = 0xfc, T_SKIN = 0xfb;

let atlasPromise: Promise<{idx: AtlasIndex; data: Uint8ClampedArray; aw: number}> | null = null;
const stripCache = new Map<string, {json: string; png: string}>();

/** Load the atlas index + pixels once (straight alpha preserved for the tags). */
function loadAtlas(): Promise<{idx: AtlasIndex; data: Uint8ClampedArray; aw: number}> {
    if (atlasPromise) return atlasPromise;
    atlasPromise = (async () => {
        const idx: AtlasIndex = await fetch(asset("/api/world3d/player-layers/index.json"))
            .then(r => {
                if (!r.ok) throw new Error(`atlas index ${r.status}`);
                return r.json();
            });
        const blob = await fetch(asset("/api/world3d/player-layers/atlas.png"))
            .then(r => r.blob());
        // premultiplyAlpha:"none" keeps the alpha tag + shade exact.
        const bmp = await createImageBitmap(blob, {premultiplyAlpha: "none"});
        const cv = document.createElement("canvas");
        cv.width = bmp.width;
        cv.height = bmp.height;
        const ctx = cv.getContext("2d", {willReadFrequently: true})!;
        ctx.clearRect(0, 0, cv.width, cv.height);
        ctx.drawImage(bmp, 0, 0);
        const data = ctx.getImageData(0, 0, cv.width, cv.height).data;
        return {idx, data, aw: cv.width};
    })();
    return atlasPromise;
}

function pal(table: number[], i: number): number {
    return table[Math.max(0, Math.min(table.length - 1, i | 0))];
}

/** Per-channel multiply: dest = colour * shade / 255 (matches the kernel). */
function mul(colour: number, shade: number): number {
    const r = (((colour >> 16) & 255) * shade / 255) | 0;
    const g = (((colour >> 8) & 255) * shade / 255) | 0;
    const b = ((colour & 255) * shade / 255) | 0;
    return (r << 16) | (g << 8) | b;
}

/** Skin: red fixed at full skin, green/blue ride the shade ramp. */
function skinApply(skin: number, shade: number): number {
    const r = (((skin >> 16) & 255) * 255) >> 8;
    const g = (((skin >> 8) & 255) * shade / 255) | 0;
    const b = ((skin & 255) * shade / 255) | 0;
    return (r << 16) | (g << 8) | b;
}

/** Compose the 8 facings + combat A/B × 3 walk frames for a token. */
export async function compositePlayerStrip(token: string): Promise<{json: string; png: string}> {
    const cached = stripCache.get(token);
    if (cached) return cached;
    const {idx, data, aw} = await loadAtlas();

    const halves = token.split("|");
    const layerStr = (halves[0] ?? "").split(",");
    const layers: number[] = [];
    for (let i = 0; i < 12; i++) layers[i] = i < layerStr.length ? (parseInt(layerStr[i], 10) || 0) : 0;
    const cs = (halves[1] ?? "").split(",").map(n => parseInt(n, 10) || 0);
    const hair = pal(idx.palettes.hair, cs[0] ?? 0);
    const top = pal(idx.palettes.clothing, cs[1] ?? 0);
    const bottom = pal(idx.palettes.clothing, cs[2] ?? 0);
    const skin = pal(idx.palettes.skin, cs[3] ?? 0);

    // 512² accumulation buffer (opaque over-draw in layer order), reused.
    const buf = new Uint32Array(SURF * SURF); // 0 = empty, else 0xFF_RRGGBB

    interface F {o: number; f: number; px: Uint32Array; w: number; h: number; ax: number; ay: number}
    const frames: F[] = [];

    for (let order = 0; order < 10; order++) {
        const layerRow = order <= 7 ? order : 2;
        const drawOrder = idx.layerOrder[layerRow];
        for (let walk = 0; walk < 3; walk++) {
            buf.fill(0);
            let any = false;
            for (const slot of drawOrder) {
                const animId = layers[slot] - 1;
                if (animId < 0) continue;
                const slotClass = slot === 4 ? "4" : slot === 3 ? "3" : "0";
                const byAnim = idx.crops[animId];
                if (!byAnim) continue;
                const fk = order + "," + walk;
                const crop = byAnim[slotClass]?.[fk] ?? byAnim["0"]?.[fk];
                if (!crop) continue;
                const [uid, dx, dy] = crop;
                const [ax, ay, w, h] = idx.atlas[uid];
                for (let py = 0; py < h; py++) {
                    const dyRow = (dy + py) * SURF;
                    const syRow = (ay + py) * aw;
                    for (let px = 0; px < w; px++) {
                        const si = (syRow + ax + px) * 4;
                        const tag = data[si + 3];
                        if (tag === 0) continue;
                        const r = data[si], g = data[si + 1], b = data[si + 2];
                        let rgb: number;
                        if (tag === T_FIXED) rgb = (r << 16) | (g << 8) | b;
                        else if (tag === T_HAIR) rgb = mul(hair, r);
                        else if (tag === T_TOP) rgb = mul(top, r);
                        else if (tag === T_BOTTOM) rgb = mul(bottom, r);
                        else if (tag === T_SKIN) rgb = skinApply(skin, r);
                        else rgb = (r << 16) | (g << 8) | b;
                        buf[dyRow + dx + px] = 0xff000000 | rgb;
                        any = true;
                    }
                }
            }
            if (!any) continue;
            let minX = SURF, minY = SURF, maxX = -1, maxY = -1;
            for (let y = 0; y < SURF; y++) {
                const row = y * SURF;
                for (let x = 0; x < SURF; x++) {
                    if (buf[row + x] !== 0) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0) continue;
            const w = maxX - minX + 1, h = maxY - minY + 1;
            const px = new Uint32Array(w * h);
            for (let y = 0; y < h; y++) {
                for (let x = 0; x < w; x++) {
                    px[y * w + x] = buf[(minY + y) * SURF + (minX + x)];
                }
            }
            frames.push({
                o: order, f: walk, px, w, h,
                ax: idx.originX + (idx.width1 >> 1) - minX,
                ay: idx.originY + idx.height - minY,
            });
        }
    }

    // Grid pack: 10 columns (facings + combat A/B) × 3 walk rows, matching
    // PlayerSpriteService so the strip layout the viewer expects is identical.
    let cw = 1, ch = 1;
    for (const f of frames) {
        cw = Math.max(cw, f.w);
        ch = Math.max(ch, f.h);
    }
    const stripW = cw * 10, stripH = ch * 3;
    const out = new Uint8ClampedArray(stripW * stripH * 4);
    const jf: {o: number; f: number; x: number; y: number; w: number; h: number; ax: number; ay: number}[] = [];
    for (const f of frames) {
        const bx = f.o * cw, by = f.f * ch;
        for (let y = 0; y < f.h; y++) {
            for (let x = 0; x < f.w; x++) {
                const p = f.px[y * f.w + x];
                if (p === 0) continue;
                const di = ((by + y) * stripW + (bx + x)) * 4;
                out[di] = (p >> 16) & 255;
                out[di + 1] = (p >> 8) & 255;
                out[di + 2] = p & 255;
                out[di + 3] = 255;
            }
        }
        jf.push({o: f.o, f: f.f, x: bx, y: by, w: f.w, h: f.h, ax: f.ax, ay: f.ay});
    }

    const cv = document.createElement("canvas");
    cv.width = stripW;
    cv.height = stripH;
    cv.getContext("2d")!.putImageData(new ImageData(out, stripW, stripH), 0, 0);
    const pngBlob: Blob = await new Promise(res => cv.toBlob(b => res(b!), "image/png"));
    const pngUrl = URL.createObjectURL(pngBlob);
    const jsonUrl = URL.createObjectURL(new Blob(
        [JSON.stringify({scale: idx.scale, width: stripW, height: stripH, frames: jf})],
        {type: "application/json"}));

    const result = {json: jsonUrl, png: pngUrl};
    stripCache.set(token, result);
    return result;
}
