import {boxPerimeter, drapeGate} from "../World3DOverlays";
import type {OverlayContext, ViewerOverlay} from "../World3DOverlays";
import type {Ribbon} from "../World3DSight";
import type {TagPool} from "../World3DTags";

/** The planner's endpoints as absolute tiles, null while unset. */
export interface RouteEndsData {
    start: {x: number; z: number} | null;
    end: {x: number; z: number} | null;
}

const START_COLOUR = 0x58ff8a;
const END_COLOUR = 0xf85149;

/**
 * Outlines the start tile in green and the end tile in red, each labelled, on the floor in view.
 *
 * @param floorHeight - tiles of z per floor, so `floor(z / floorHeight)` is a tile's floor
 */
export function routeEndsOverlay(floorHeight: number): ViewerOverlay<RouteEndsData> {
    const ribbons = new Map<string, Ribbon>();
    let labels: TagPool | null = null;
    const stale = drapeGate();
    // Inclusive bounds: one tile is the same tile twice.
    const tileBox = (t: {x: number; z: number}) => ({x0: t.x, z0: t.z, x1: t.x, z1: t.z});
    return {
        mount(ctx: OverlayContext) {
            labels = ctx.tags({
                css: "font:600 10px monospace;color:#c9d1d9;" + "text-shadow:0 1px 2px rgba(0,0,0,.9);",
                anchor: "centre",
                interactive: false,
            });
        },
        frame(ctx: OverlayContext, ends: RouteEndsData) {
            const drawn: {key: string; tile: {x: number; z: number}; colour: number}[] = [];
            if (ends.start) drawn.push({key: "start", tile: ends.start, colour: START_COLOUR});
            if (ends.end) drawn.push({key: "end", tile: ends.end, colour: END_COLOUR});
            if (stale(ctx, ends)) {
                const seen = new Set<string>();
                for (const {key, tile, colour} of drawn) {
                    if (Math.floor(tile.z / floorHeight) !== ctx.floor) continue;
                    seen.add(key);
                    let ribbon = ribbons.get(key);
                    if (!ribbon) {
                        ribbon = ctx.ribbon({color: colour, opacity: 0.95, ghostOpacity: 0.35});
                        ribbons.set(key, ribbon);
                    }
                    ribbon.extrude([{pts: boxPerimeter(tileBox(tile)), closed: true}], ctx.toWorld);
                }
                for (const [key, ribbon] of ribbons) {
                    if (!seen.has(key)) {
                        ribbon.dispose();
                        ribbons.delete(key);
                    }
                }
            }
            labels?.begin(ctx.view, ctx.camera);
            for (const {key, tile} of drawn) {
                if (Math.floor(tile.z / floorHeight) !== ctx.floor) continue;
                labels?.put(key, ctx.toWorld(tile.x, tile.z), key);
            }
            labels?.end();
        },
    };
}
