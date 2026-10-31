/**
 * The client's `@xxx@` colour codes, matched case-insensitively. A code missing here (such as
 * `@que@`) is still consumed when drawn, and leaves the colour as it was.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L1516 GraphicsController.drawColoredString
 */
const RSC_COLOR_CODES: Readonly<Record<string, string>> = {
    red: "#ff0000",
    lre: "#ff9040",
    yel: "#ffff00",
    gre: "#00ff00",
    blu: "#0000ff",
    cya: "#00ffff",
    mag: "#ff00ff",
    whi: "#ffffff",
    bla: "#000000",
    dre: "#c00000",
    ora: "#ff9040",
    or1: "#ffb000",
    or2: "#ff7000",
    or3: "#ff3000",
    gr1: "#c0ff00",
    gr2: "#80ff00",
    gr3: "#40ff00",
    bl1: "#4040ff",
    bl2: "#0040ff",
    bl3: "#4000ff",
    dgr: "#00c000",
    dbl: "#0000c0",
    dcy: "#00c0c0",
    dor: "#c06020",
    sub: "#eedddd",
    eve: "#4d33bd",
    sil: "#c0c0c0",
    pre: "#44eadf",
    cla: "#7cadda",
    pin: "#ff8ed9",
};

/** The code that sets a fresh random colour each time the string is drawn ({@link randomRscColor}). */
const RSC_RANDOM_CODE = "ran";

/**
 * The default colour of overhead chat.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L2775 mudclient.drawCharacterOverlay
 */
export const RSC_OVERHEAD_COLOR = "#ffff00";

/**
 * The client draws no drop shadow under black text.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L1671 GraphicsController.drawColoredString
 */
export const RSC_BLACK = "#000000";

/** One run of a game string: literal `text`, or a colour change, a random colour, or a pen move to `x`. */
export type RscToken =
    {kind: "text"; text: string} | {kind: "color"; color: string} | {kind: "random"} | {kind: "cursor"; x: number};

function allDigits(s: string): boolean {
    for (let i = 0; i < s.length; i++) {
        const c = s.charCodeAt(i);
        if (c < 48 || c > 57) return false;
    }
    return true;
}

/**
 * Splits a game string as the client's scanner does: a code needs at least one character after it,
 * `~ddd~` is tried before `~dddd~`, a `~` code is consumed even when it holds non-digits, and a
 * no-break space reads as a space. Adjacent literal characters form one `text` token.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/graphics/two/GraphicsController.java#L1486 GraphicsController.drawColoredString
 */
export function tokenizeRscMarkup(str: string): RscToken[] {
    const out: RscToken[] = [];
    let run = "";
    const flush = () => {
        if (run.length > 0) {
            out.push({kind: "text", text: run});
            run = "";
        }
    };
    for (let i = 0; i < str.length; i++) {
        const c = str[i];
        if (c === "@" && i + 4 < str.length && str[i + 4] === "@") {
            const key = str.substring(i + 1, i + 4).toLowerCase();
            if (key === RSC_RANDOM_CODE) {
                flush();
                out.push({kind: "random"});
            } else {
                const color = RSC_COLOR_CODES[key];
                if (color) {
                    flush();
                    out.push({kind: "color", color});
                }
            }
            i += 4;
            continue;
        }
        if (c === "~" && i + 4 < str.length && str[i + 4] === "~") {
            const digits = str.substring(i + 1, i + 4);
            if (allDigits(digits)) {
                flush();
                out.push({kind: "cursor", x: parseInt(digits, 10)});
            }
            i += 4;
            continue;
        }
        if (c === "~" && i + 5 < str.length && str[i + 5] === "~") {
            const digits = str.substring(i + 1, i + 5);
            if (allDigits(digits)) {
                flush();
                out.push({kind: "cursor", x: parseInt(digits, 10)});
            }
            i += 5;
            continue;
        }
        run += str.charCodeAt(i) === 160 ? " " : c;
    }
    flush();
    return out;
}

/** A uniformly random 24-bit colour, as the client rolls for `@ran@`. */
export function randomRscColor(): string {
    return (
        "#" +
        Math.floor(0xffffff * Math.random())
            .toString(16)
            .padStart(6, "0")
    );
}

/** The string's text with every code removed. */
export function stripRscMarkup(str: string): string {
    if (!str.includes("@") && !str.includes("~")) return str;
    let out = "";
    for (const t of tokenizeRscMarkup(str)) {
        if (t.kind === "text") out += t.text;
    }
    return out;
}
