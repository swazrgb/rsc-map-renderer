import {asset, versioned} from "./api";
import {context2d, need} from "./need";
import {RSC_BLACK, RSC_OVERHEAD_COLOR, randomRscColor, tokenizeRscMarkup} from "./rscMarkup";

/** A glyph's rectangle in the font atlas, its offset from the pen, and its advance, in pixels. */
export interface Glyph {
    x: number;
    y: number;
    w: number;
    h: number;
    xo: number;
    yo: number;
    adv: number;
}

interface FontIndex {
    baked: number;
    lineHeight: number;
    chars: (Glyph & {c: number})[];
}

/**
 * The client's bold 12px font (font 1), baked to a white atlas and tinted per colour, with the
 * client's measuring, wrapping and drawing of game strings.
 */
export class GameFont {
    readonly lineHeight: number;
    /** The face's extent above and below the baseline, in pixels. */
    readonly ascent: number;
    readonly descent: number;
    private readonly glyphs = new Map<number, Glyph>();
    private readonly fallback: Glyph;
    private readonly atlas: HTMLImageElement;
    private readonly tinted = new Map<string, HTMLCanvasElement>();

    constructor(atlas: HTMLImageElement, idx: FontIndex) {
        this.atlas = atlas;
        this.lineHeight = idx.lineHeight;
        let asc = 1;
        let desc = 1;
        for (const c of idx.chars) {
            this.glyphs.set(c.c, c);
            asc = Math.max(asc, c.yo);
            desc = Math.max(desc, c.h - c.yo);
        }
        this.ascent = asc;
        this.descent = desc;
        // The client draws a character it has no glyph for as '='.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/Fonts.java#L18
        this.fallback = need(this.glyphs.get(61), "font glyph 61 ('=')");
    }

    glyph(code: number): Glyph {
        return this.glyphs.get(code) ?? this.fallback;
    }

    private tintedAtlas(color: string): HTMLCanvasElement {
        let c = this.tinted.get(color);
        if (!c) {
            // The palette is small, so this only grows under @ran@, which rolls a colour every frame.
            if (this.tinted.size > 64) this.tinted.clear();
            c = document.createElement("canvas");
            c.width = this.atlas.width;
            c.height = this.atlas.height;
            const g = context2d(c);
            g.drawImage(this.atlas, 0, 0);
            g.globalCompositeOperation = "source-in";
            g.fillStyle = color;
            g.fillRect(0, 0, c.width, c.height);
            this.tinted.set(color, c);
        }
        return c;
    }

    /**
     * The string's advance width in pixels, codes skipped.
     *
     * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L2611 GraphicsController.stringWidth
     */
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
     * The lines the client breaks `str` into: at the last space once a line passes `wrapWidth` pixels,
     * mid-word when there is none. Codes stay in place.
     *
     * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L805 GraphicsController.drawWrappedCenteredString
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
     * Draws overhead chat as the client does: each line centred, a black shadow one pixel right and one
     * down, colour codes carried across lines. `baseline` and `midX` place the first line's baseline and
     * the centre axis in the canvas, in CSS pixels. `scale` supersamples the backing store (pass the
     * rounded-up device pixel ratio) so glyph pixels land on the device grid. `into` redraws a previous
     * canvas in place. `animated` means the string holds `@ran@`, so the drawing is good for one frame.
     * `totalWidth` and `extraLinesHeight` are the client's bubble-collision metrics, from the unwrapped width.
     *
     * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6555 mudclient.drawNPC
     */
    renderOverhead(
        str: string,
        wrapWidth = 300,
        defaultColor = RSC_OVERHEAD_COLOR,
        scale = 1,
        into?: HTMLCanvasElement | null,
    ): {
        canvas: HTMLCanvasElement;
        baseline: number;
        midX: number;
        width: number;
        height: number;
        animated: boolean;
        totalWidth: number;
        extraLinesHeight: number;
    } {
        const r = this.draw(str, {wrapWidth, defaultColor, scale, into});
        const totalWidth = this.width(str);
        return {
            canvas: r.canvas,
            baseline: this.ascent,
            midX: r.width / 2,
            width: r.width,
            height: r.height,
            animated: r.animated,
            totalWidth,
            extraLinesHeight: Math.floor(totalWidth / wrapWidth) * this.lineHeight,
        };
    }

    private draw(
        str: string,
        opts: {
            wrapWidth: number;
            defaultColor: string;
            scale: number;
            into?: HTMLCanvasElement | null;
        },
    ): {canvas: HTMLCanvasElement; width: number; height: number; lines: number; animated: boolean} {
        const lines = this.wrap(str, opts.wrapWidth);
        const widths = lines.map((l) => this.width(l));
        const maxW = Math.max(1, ...widths);
        const logicalW = maxW + 1;
        const logicalH = (lines.length - 1) * this.lineHeight + this.ascent + this.descent + 1;
        const canvas = opts.into ?? document.createElement("canvas");
        canvas.width = logicalW * opts.scale;
        canvas.height = logicalH * opts.scale;
        canvas.style.width = `${logicalW}px`;
        canvas.style.height = `${logicalH}px`;
        const g = context2d(canvas);
        g.imageSmoothingEnabled = false;
        g.setTransform(opts.scale, 0, 0, opts.scale, 0, 0);
        let color = opts.defaultColor;
        let animated = false;
        for (let li = 0; li < lines.length; li++) {
            const baseY = this.ascent + li * this.lineHeight;
            let x = Math.floor((maxW - widths[li]) / 2);
            for (const tok of tokenizeRscMarkup(lines[li])) {
                if (tok.kind === "color") {
                    color = tok.color;
                    continue;
                }
                if (tok.kind === "random") {
                    color = randomRscColor();
                    animated = true;
                    continue;
                }
                if (tok.kind === "cursor") {
                    continue;
                }
                const shadow = color === RSC_BLACK ? null : this.tintedAtlas(RSC_BLACK);
                const face = this.tintedAtlas(color);
                for (let i = 0; i < tok.text.length; i++) {
                    const gl = this.glyph(tok.text.charCodeAt(i));
                    if (gl.w > 0 && gl.h > 0) {
                        const dx = x + gl.xo;
                        const dy = baseY - gl.yo;
                        if (shadow) {
                            g.drawImage(shadow, gl.x, gl.y, gl.w, gl.h, dx + 1, dy, gl.w, gl.h);
                            g.drawImage(shadow, gl.x, gl.y, gl.w, gl.h, dx, dy + 1, gl.w, gl.h);
                        }
                        g.drawImage(face, gl.x, gl.y, gl.w, gl.h, dx, dy, gl.w, gl.h);
                    }
                    x += gl.adv;
                }
            }
        }
        return {canvas, width: logicalW, height: logicalH, lines: lines.length, animated};
    }
}

/** The baked game font. */
export function loadGameFont(): Promise<GameFont> {
    return versioned("/api/world3d/font-h12b.json")
        .then((url) => fetch(url))
        .then((r) => {
            if (!r.ok) throw new Error(`font index ${r.status}`);
            return r.json() as Promise<FontIndex>;
        })
        .then(
            (idx) =>
                new Promise<GameFont>((res, rej) => {
                    const img = new Image();
                    img.onload = () => res(new GameFont(img, idx));
                    img.onerror = rej;
                    img.src = asset(`/api/world3d/font-h12b.png?v=${idx.baked}`);
                }),
        );
}
