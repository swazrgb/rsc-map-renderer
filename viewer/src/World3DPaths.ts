import {RIBBON_HALF_WIDTH, type Ribbon} from "./World3DSight";
import {drapeGate} from "./World3DOverlays";
import type {OverlayContext, ViewerOverlay} from "./World3DOverlays";

/** One point of a path; z is absolute. `hop` marks a jump (a transport or teleport), which breaks the line. */
export interface PathPoint {
    x: number;
    z: number;
    hop?: boolean;
}

/**
 * How a {@link pathOverlay} draws. `breakOverTiles` breaks the line where points are further apart
 * than that, as a teleport. `dropRepeats` skips repeated tiles. `dash` draws `on` tiles of line then
 * `off` tiles of gap, cut in tile space so every dash follows the terrain. `minPx` is the narrowest
 * the line may draw on screen, default {@link MIN_PATH_PX}.
 */
interface PathStyle {
    color: number;
    opacity: number;
    ghostOpacity: number;
    breakOverTiles?: number;
    dropRepeats?: boolean;
    dash?: {on: number; off: number};
    minPx?: number;
}

const MIN_PATH_PX = 2;
// A dash and its gap stay at least this long on screen.
const MIN_DASH_PX = 6;

// The factor that keeps `px` screen pixels at least `tiles` long at this zoom, in half-octave steps so
// zooming redrapes the line only now and then.
function screenFloor(ctx: OverlayContext, px: number, tiles: number): number {
    const want = (px * ctx.zoomTiles) / Math.max(1, ctx.view.h) / tiles;
    if (!(want > 1)) return 1;
    return Math.SQRT2 ** Math.ceil(Math.log2(want) * 2);
}

const DASH_EPS = 1e-6;

/**
 * `chain` cut into dashes of `on` tiles every `on + off` tiles along its length. Each dash edge is
 * computed from its index against the distance along the whole chain, and the loop is capped at the
 * edges the chain can hold, so rounding cannot make it spin.
 */
function dashed(
    chain: {x: number; z: number}[],
    on: number,
    off: number,
): {pts: {x: number; z: number}[]; closed: boolean}[] {
    const out: {pts: {x: number; z: number}[]; closed: boolean}[] = [];
    const period = on + off;
    if (!(on > 0) || !(period > 0) || chain.length < 2) return out;
    let total = 0;
    for (let i = 0; i + 1 < chain.length; i++) {
        total += Math.hypot(chain[i + 1].x - chain[i].x, chain[i + 1].z - chain[i].z);
    }
    let budget = 2 * Math.ceil(total / Math.min(on, off > 0 ? off : on)) + 2 * chain.length + 16;
    let base = 0;
    let cur: {x: number; z: number}[] = [];
    const flush = () => {
        if (cur.length > 1) out.push({pts: cur, closed: false});
        cur = [];
    };
    for (let i = 0; i + 1 < chain.length; i++) {
        const a = chain[i];
        const b = chain[i + 1];
        const len = Math.hypot(b.x - a.x, b.z - a.z);
        if (len === 0) continue;
        const at = (t: number) => ({x: a.x + (b.x - a.x) * (t / len), z: a.z + (b.z - a.z) * (t / len)});
        let t = 0;
        while (len - t > DASH_EPS && budget-- > 0) {
            const g = base + t;
            let k = Math.floor(g / period);
            // A point a rounding error short of the next period's start is at it.
            if ((k + 1) * period - g <= DASH_EPS) k++;
            const onEnd = k * period + on;
            const drawing = onEnd - g > DASH_EPS;
            const edge = drawing ? onEnd : (k + 1) * period;
            const next = Math.min(len, edge - base);
            if (drawing) {
                if (cur.length === 0) cur.push(at(t));
                cur.push(at(next));
            } else {
                flush();
            }
            t = next > t ? next : len;
        }
        base += len;
    }
    flush();
    return out;
}

/** A draped polyline on the floor in view, broken at floor changes and jumps, redrawn only when it changes. */
export function pathOverlay(style: PathStyle): ViewerOverlay<PathPoint[] | null | undefined> {
    let ribbon: Ribbon | null = null;
    const stale = drapeGate();
    let lastWidth = 0;
    let lastDash = 0;
    return {
        mount(ctx: OverlayContext) {
            ribbon = ctx.ribbon({
                color: style.color,
                opacity: style.opacity,
                ghostOpacity: style.ghostOpacity,
                band: "ground",
            });
        },
        frame(ctx: OverlayContext, data) {
            const width = screenFloor(ctx, style.minPx ?? MIN_PATH_PX, 2 * RIBBON_HALF_WIDTH);
            const dashScale = style.dash ? screenFloor(ctx, MIN_DASH_PX, style.dash.on) : 1;
            const moved = stale(ctx, data);
            if (!moved && width === lastWidth && dashScale === lastDash) return;
            lastWidth = width;
            lastDash = dashScale;
            const chains: {pts: {x: number; z: number}[]; closed: boolean}[] = [];
            let cur: {x: number; z: number}[] = [];
            let prev: PathPoint | null = null;
            for (const p of data ?? []) {
                if (style.dropRepeats && p.x === prev?.x && p.z === prev.z) continue;
                const onFloor = Math.floor(p.z / 944) === ctx.floor;
                const jump =
                    p.hop === true ||
                    (style.breakOverTiles != null &&
                        prev != null &&
                        Math.max(Math.abs(p.x - prev.x), Math.abs(p.z - prev.z)) > style.breakOverTiles);
                if (jump || !onFloor) {
                    if (cur.length > 1) chains.push({pts: cur, closed: false});
                    cur = [];
                }
                if (onFloor) cur.push({x: p.x, z: p.z});
                prev = p;
            }
            if (cur.length > 1) chains.push({pts: cur, closed: false});
            const dash = style.dash;
            ribbon?.extrude(
                dash ? chains.flatMap((c) => dashed(c.pts, dash.on * dashScale, dash.off * dashScale)) : chains,
                ctx.toWorld,
                RIBBON_HALF_WIDTH * width,
            );
        },
    };
}

/** The selected observer's route, blue. */
export const routeOverlay = pathOverlay({
    color: 0x4da3ff,
    opacity: 0.6,
    ghostOpacity: 0.16,
});

/** The planner's own route, magenta and dotted, to read apart from the walk drawn over it. */
export const planOverlay = pathOverlay({
    color: 0xff6ad5,
    opacity: 0.85,
    ghostOpacity: 0.2,
    dash: {on: 1.5, off: 1.5},
});

/** The walk a plan predicted, green and faint, kept beside the walk the bot actually takes. */
export const predictedOverlay = pathOverlay({
    color: 0x5fe39a,
    opacity: 0.45,
    ghostOpacity: 0.12,
});

/** A recorded walked trail, amber, with teleports inferred from gaps over 5 tiles. */
export const trailOverlay = pathOverlay({
    color: 0xe8a33d,
    opacity: 0.7,
    ghostOpacity: 0.14,
    breakOverTiles: 5,
    dropRepeats: true,
});
