import * as THREE from "three";
import {boxPerimeter, drapeGate} from "../World3DOverlays";
import type {OverlayContext, ViewerOverlay} from "../World3DOverlays";
import type {Ribbon} from "../World3DSight";
import type {TagPool} from "../World3DTags";
import {FLOOR_NAME, FLOOR_TILES, type SearchEntry} from "./worldSearch";

/**
 * What the search shows in the world: every match faintly, and the `lit` ones (a hovered row, or every
 * row of a hovered group) strongly. Each array must keep its identity while unchanged: a new array
 * is what triggers a redraw.
 */
export interface SearchMarks {
    matches: readonly SearchEntry[];
    lit: readonly SearchEntry[];
}

export const NO_MARKS: SearchMarks = {matches: [], lit: []};

/** The fill of every label naming a search hit, so an NPC nameplate forced on for one can match. */
export const LABEL_FILL = 0xffd76a;
const LABEL_CSS =
    "font:10px monospace;color:#1a1405;" +
    `background:#${LABEL_FILL.toString(16).padStart(6, "0")};padding:0 4px;border-radius:4px;white-space:nowrap;`;

const MATCH_COLOUR = 0xffc857;
const LIT_COLOUR = 0xfff1a8;
const MAX_LABELS = 40;
// Off-screen entries get edge pointers only while this few are lit: a hovered row, not a whole group.
const MAX_POINTERS = 3;
// Pixels in from the view's edges, and from the inset.
const POINTER_MARGIN = 6;
// Clockwise from east in screen space, where y grows down.
const ARROWS = ["→", "↘", "↓", "↙", "←", "↖", "↑", "↗"];

const tileBox = (t: {x: number; z: number}) => ({x0: t.x, z0: t.z, x1: t.x, z1: t.z});

/**
 * Outlines every match's tile, and for each lit entry its tile, an NPC spawn's wander box and its
 * name. A live NPC is named only by the view's own nameplate. A lit entry out of view gets a pointer
 * at the edge of the view in its direction; one on another floor is placed where it would stand on
 * the shown floor, its name saying which.
 *
 * @param inset - pixels at the view's left and top that the host covers, kept clear of pointers
 */
export function searchOverlay(inset: {left: number; top: number}): ViewerOverlay<SearchMarks> {
    let matchRibbon: Ribbon | null = null;
    let litRibbon: Ribbon | null = null;
    let labels: TagPool | null = null;
    let pointers: TagPool | null = null;
    const ndc = new THREE.Vector3();
    const at = new THREE.Vector3();
    const staleMatches = drapeGate();
    const staleLit = drapeGate();

    const visible = (ctx: OverlayContext) => {
        const px = ((ndc.x + 1) / 2) * ctx.view.w;
        const py = ((1 - ndc.y) / 2) * ctx.view.h;
        return px >= inset.left && px <= ctx.view.w && py >= inset.top && py <= ctx.view.h;
    };
    const point = (ctx: OverlayContext, key: string, text: string) => {
        const {w, h} = ctx.view;
        const left = Math.min(inset.left + POINTER_MARGIN, w / 2);
        const right = Math.max(w - POINTER_MARGIN, left);
        const top = Math.min(inset.top + POINTER_MARGIN, h / 2);
        const bottom = Math.max(h - POINTER_MARGIN, top);
        const cx = (left + right) / 2;
        const cy = (top + bottom) / 2;
        // A point behind the camera projects mirrored through the centre.
        const behind = ndc.z > 1 ? -1 : 1;
        const dx = behind * (((ndc.x + 1) / 2) * w - cx);
        const dy = behind * (((1 - ndc.y) / 2) * h - cy);
        const k = Math.min(
            dx === 0 ? Infinity : (right - cx) / Math.abs(dx),
            dy === 0 ? Infinity : (bottom - cy) / Math.abs(dy),
            1,
        );
        const px = cx + dx * k;
        const py = cy + dy * k;
        // Back into the world at the same depth, so the tag pool projects it onto this very pixel.
        at.set((px / w) * 2 - 1, 1 - (py / h) * 2, Math.max(-0.99, Math.min(0.99, ndc.z))).unproject(ctx.camera);
        const arrow = ARROWS[(Math.round(Math.atan2(dy, dx) / (Math.PI / 4)) + 8) % 8];
        const along = (d: number, half: number) => (half > 0 ? Math.max(-1, Math.min(1, d / half)) : 0);
        // Hung inward from that pixel, so the whole pointer shows whatever its length.
        const align = {x: 0.5 + 0.5 * along(px - cx, right - cx), y: 0.5 + 0.5 * along(py - cy, bottom - cy)};
        pointers?.put(key, at, `${arrow} ${text}`, align);
    };

    return {
        mount(ctx: OverlayContext) {
            matchRibbon = ctx.ribbon({color: MATCH_COLOUR, opacity: 0.55, ghostOpacity: 0.2});
            litRibbon = ctx.ribbon({color: LIT_COLOUR, opacity: 1, ghostOpacity: 0.5, band: "aboveGround"});
            labels = ctx.tags({css: LABEL_CSS, band: "chrome", offsetY: 10});
            pointers = ctx.tags({css: LABEL_CSS, band: "chrome", anchor: "centre"});
        },
        frame(ctx: OverlayContext, {matches, lit}: SearchMarks) {
            if (staleMatches(ctx, matches)) {
                matchRibbon?.extrude(
                    matches
                        .filter((e) => e.floor === ctx.floor)
                        .map((e) => ({pts: boxPerimeter(tileBox(e)), closed: true})),
                    ctx.toWorld,
                );
            }
            if (staleLit(ctx, lit)) {
                litRibbon?.extrude(
                    lit
                        .filter((e) => e.floor === ctx.floor)
                        .flatMap((e) => [
                            {pts: boxPerimeter(tileBox(e)), closed: true},
                            ...(e.npc ? [{pts: boxPerimeter(e.npc.box), closed: true}] : []),
                        ]),
                    ctx.toWorld,
                );
            }
            labels?.begin(ctx.view, ctx.camera);
            pointers?.begin(ctx.view, ctx.camera);
            let labelled = 0;
            const pointing = lit.length <= MAX_POINTERS;
            for (const e of lit) {
                if (labelled >= MAX_LABELS && !pointing) break;
                const live = e.npc ? ctx.npcAt(e.npc.serverIndex) : null;
                const npc = live && live.id === e.npc?.id ? live : null;
                const here = npc != null || e.floor === ctx.floor;
                if (!here && !pointing) continue;
                if (npc) ctx.toWorld(npc.x, npc.z, at);
                else ctx.toWorld(e.x, e.z + (ctx.floor - e.floor) * FLOOR_TILES, at);
                ndc.copy(at).project(ctx.camera);
                const name = e.npc ? `${e.title} (${e.npc.id}) #${e.npc.serverIndex}` : e.title;
                const text = here ? name : `${name} · ${FLOOR_NAME[e.floor]}`;
                if (visible(ctx)) {
                    if (!npc && labelled++ < MAX_LABELS) labels?.put(e.key, at, text);
                } else if (pointing) {
                    point(ctx, e.key, text);
                }
            }
            labels?.end();
            pointers?.end();
        },
    };
}
