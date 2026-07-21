import {asset} from "./api";
/**
 * The game's bitmap font (h12b — font 1, bold 12px) + the stock overhead-chat
 * text renderer, both ported from the client:
 *
 * - Glyphs come from the baked white-on-transparent atlas
 *   (/api/world3d/font-h12b.{png,json}); tinting happens here per colour.
 * - Wrapping is GraphicsController.drawWrappedCenteredString: advance widths
 *   accumulate (skipping @col@ / ~1234~ codes), break at the last space once
 *   a line exceeds 300px, first line at the anchor baseline, later lines
 *   below (y += lineHeight).
 * - Drawing is drawstring: black drop shadow at (+1,0) and (0,+1) under each
 *   glyph (all game fonts are non-antialiased), colour switches on @col@
 *   codes and carries across wrapped lines (S_WANT_FIXED_OVERHEAD_CHAT).
 */

export interface Glyph {
    x: number;
    y: number;
    w: number;
    h: number;
    xo: number;
    yo: number;
    adv: number;
}

export class GameFont {
    readonly lineHeight: number;
    /** Glyph top extent above / below the baseline, over the whole face. */
    readonly ascent: number;
    readonly descent: number;
    private readonly glyphs = new Map<number, Glyph>();
    private readonly fallback: Glyph;
    private readonly atlas: HTMLImageElement;
    private readonly tinted = new Map<string, HTMLCanvasElement>();

    constructor(atlas: HTMLImageElement,
                idx: {lineHeight: number; chars: Glyph[] & {c?: number}[]}) {
        this.atlas = atlas;
        this.lineHeight = idx.lineHeight;
        let asc = 1;
        let desc = 1;
        for (const c of idx.chars as (Glyph & {c: number})[]) {
            this.glyphs.set(c.c, c);
            asc = Math.max(asc, c.yo);
            desc = Math.max(desc, c.h - c.yo);
        }
        this.ascent = asc;
        this.descent = desc;
        // The client maps unknown chars to charset index 74 ('=').
        this.fallback = this.glyphs.get(61)!;
    }

    glyph(code: number): Glyph {
        return this.glyphs.get(code) ?? this.fallback;
    }

    /** Whole-atlas copy filled with `color` (glyphs are a pure alpha mask). */
    private tintedAtlas(color: string): HTMLCanvasElement {
        let c = this.tinted.get(color);
        if (!c) {
            c = document.createElement("canvas");
            c.width = this.atlas.width;
            c.height = this.atlas.height;
            const g = c.getContext("2d")!;
            g.drawImage(this.atlas, 0, 0);
            g.globalCompositeOperation = "source-in";
            g.fillStyle = color;
            g.fillRect(0, 0, c.width, c.height);
            this.tinted.set(color, c);
        }
        return c;
    }

    /** Advance width of a string, skipping @col@ / ~1234~ codes (stringWidth). */
    width(str: string): number {
        let w = 0;
        for (let i = 0; i < str.length; i++) {
            if (str[i] === "@" && i + 4 < str.length && str[i + 4] === "@") {
                i += 4;
            } else if (str[i] === "~" && i + 4 < str.length && str[i + 4] === "~") {
                i += 4;
            } else {
                w += this.glyph(str.charCodeAt(i)).adv;
            }
        }
        return w;
    }

    /**
     * Stock wrap (drawWrappedCenteredString, wrapWidth 300): returns the
     * substrings drawn per line, colour codes left in place.
     */
    wrap(str: string, wrapWidth: number): string[] {
        const lines: string[] = [];
        let width = 0;
        let lastLineTerm = 0;
        let lastBreak = 0;
        for (let i = 0; i < str.length; i++) {
            if (str[i] === "@" && i + 4 < str.length && str[i + 4] === "@") {
                i += 4;
            } else if (str[i] === "~" && i + 4 < str.length && str[i + 4] === "~") {
                i += 4;
            } else {
                width += this.glyph(str.charCodeAt(i)).adv;
            }
            if (str[i] === " ") {
                lastBreak = i;
            }
            if (width > wrapWidth) {
                let lineEndsAt = lastBreak;
                if (lastBreak <= lastLineTerm) {
                    lineEndsAt = lastBreak = i;
                }
                lines.push(str.substring(lastLineTerm, lineEndsAt));
                lastLineTerm = i = 1 + lastBreak;
                width = 0;
            }
        }
        if (width > 0) {
            lines.push(str.substring(lastLineTerm));
        }
        return lines;
    }

    /**
     * Render wrapped overhead text into a canvas: each line centred, the
     * stock shadow + colour handling, default colour = overhead yellow.
     * Returns the canvas plus where the FIRST line's baseline and the centre
     * axis sit inside it (in LOGICAL px), so the caller can pin it to the
     * stock anchor (sprite mid-x, sprite top).
     *
     * `scale` supersamples the backing store (pass ceil(devicePixelRatio));
     * the canvas is CSS-sized to logical px, so glyph pixels land on (or
     * uniformly resample to) the device grid instead of warping — a 1:1
     * canvas at fractional positions/DPR gets nearest-neighbour column
     * drops that read as slanted "italic" text.
     */
    renderOverhead(str: string, wrapWidth = 300, defaultColor = "#ff0",
                   scale = 1):
        {canvas: HTMLCanvasElement; baseline: number; midX: number;
         width: number; height: number;
         totalWidth: number; extraLinesHeight: number} {
        const lines = this.wrap(str, wrapWidth);
        const widths = lines.map(l => this.width(l));
        const maxW = Math.max(1, ...widths);
        const logicalW = maxW + 1;               // +1: shadow spills right
        const logicalH = (lines.length - 1) * this.lineHeight
            + this.ascent + this.descent + 1;    // +1: shadow spills down
        const canvas = document.createElement("canvas");
        canvas.width = logicalW * scale;
        canvas.height = logicalH * scale;
        canvas.style.width = `${logicalW}px`;
        canvas.style.height = `${logicalH}px`;
        const g = canvas.getContext("2d")!;
        g.imageSmoothingEnabled = false;
        g.setTransform(scale, 0, 0, scale, 0, 0);
        let color = defaultColor;
        for (let li = 0; li < lines.length; li++) {
            const line = lines[li];
            const baseY = this.ascent + li * this.lineHeight;
            let x = Math.floor((maxW - widths[li]) / 2);
            for (let i = 0; i < line.length; i++) {
                if (line[i] === "@" && i + 4 < line.length && line[i + 4] === "@") {
                    color = COLOR_CODES[line.substring(i + 1, i + 4).toLowerCase()]
                        ?? color;
                    i += 4;
                    continue;
                }
                if (line[i] === "~" && i + 4 < line.length && line[i + 4] === "~") {
                    i += 4;
                    continue;
                }
                const gl = this.glyph(line.charCodeAt(i));
                if (gl.w > 0 && gl.h > 0) {
                    const dx = x + gl.xo;
                    const dy = baseY - gl.yo;
                    const shadow = this.tintedAtlas("#000");
                    // drawstring: black at (+1,0) and (0,+1), colour on top.
                    g.drawImage(shadow, gl.x, gl.y, gl.w, gl.h,
                        dx + 1, dy, gl.w, gl.h);
                    g.drawImage(shadow, gl.x, gl.y, gl.w, gl.h,
                        dx, dy + 1, gl.w, gl.h);
                    g.drawImage(this.tintedAtlas(color), gl.x, gl.y, gl.w, gl.h,
                        dx, dy, gl.w, gl.h);
                }
                x += gl.adv;
            }
        }
        // Stock bubble-collision metrics: halfWidth = stringWidth/2 (cap 150),
        // height = (stringWidth/300|0) * lineHeight — from the UNWRAPPED width.
        const totalWidth = this.width(str);
        return {canvas, baseline: this.ascent, midX: logicalW / 2,
            width: logicalW, height: logicalH,
            totalWidth,
            extraLinesHeight: Math.floor(totalWidth / wrapWidth) * this.lineHeight};
    }
}

/** GraphicsController.drawstring @col@ table (sans @ran@'s per-char random). */
const COLOR_CODES: Record<string, string> = {
    red: "#ff0000", lre: "#ff9040", yel: "#ffff00", gre: "#00ff00",
    blu: "#0000ff", cya: "#00ffff", mag: "#ff00ff", whi: "#ffffff",
    bla: "#000000", dre: "#c00000", ora: "#ff9040", or1: "#ffb000",
    or2: "#ff7000", or3: "#ff3000", gr1: "#c0ff00", gr2: "#80ff00",
    gr3: "#40ff00", bl1: "#4040ff", bl2: "#0040ff", bl3: "#4000ff",
    dgr: "#00c000", dbl: "#0000c0", dcy: "#00c0c0", dor: "#c06000",
    dye: "#c0c000", dma: "#c000c0", gra: "#c0c0c0", dgy: "#808080",
    bgy: "#404040", ran: "#ffff00",
};

/** Fetch the baked h12b atlas + metrics; rejects when the bake isn't ready. */
export function loadGameFont(): Promise<GameFont> {
    return fetch(asset("/api/world3d/font-h12b.json"))
        .then(r => {
            if (!r.ok) throw new Error(`font index ${r.status}`);
            return r.json();
        })
        .then(idx => new Promise<GameFont>((res, rej) => {
            const img = new Image();
            img.onload = () => res(new GameFont(img, idx));
            img.onerror = rej;
            img.src = asset("/api/world3d/font-h12b.png");
        }));
}
