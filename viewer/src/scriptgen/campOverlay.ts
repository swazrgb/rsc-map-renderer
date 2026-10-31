import * as THREE from "three";
import {boxPerimeter, drapeGate} from "../World3DOverlays";
import type {OverlayContext, ViewerOverlay} from "../World3DOverlays";
import type {Ribbon} from "../World3DSight";
import type {TagPool} from "../World3DTags";
import type {NpcSpawnInfo} from "../api";

/**
 * What the generator shows in the world: the picked spawns, the camp and the bank. Must keep its
 * identity while unchanged: a new object is what triggers a redraw.
 */
export interface CampMarks {
    targets: readonly NpcSpawnInfo[];
    camp: {x: number; z: number; box: {minX: number; minZ: number; maxX: number; maxZ: number}} | null;
    bank: {x: number; z: number; name: string} | null;
}

export const NO_CAMP: CampMarks = {targets: [], camp: null, bank: null};

const FLOOR_TILES = 944;
const TARGET_COLOUR = 0xff9e3d;
const CAMP_COLOUR = 0x58ff8a;
const BANK_COLOUR = 0x58a6ff;
const tag = (fill: number, ink: string) =>
    `font:600 10px monospace;color:${ink};background:#${fill.toString(16).padStart(6, "0")};` +
    "padding:0 4px;border-radius:4px;white-space:nowrap;";

/** Each picked spawn's wander box and name, the camp's box and the bank's tile, on the shown floor. */
export function campOverlay(): ViewerOverlay<CampMarks> {
    let targets: Ribbon | null = null;
    let camp: Ribbon | null = null;
    let bank: Ribbon | null = null;
    let names: TagPool | null = null;
    let places: TagPool | null = null;
    const at = new THREE.Vector3();
    const stale = drapeGate();
    const floorOf = (z: number) => Math.floor(z / FLOOR_TILES);

    return {
        mount(ctx: OverlayContext) {
            targets = ctx.ribbon({color: TARGET_COLOUR, opacity: 0.9, ghostOpacity: 0.35, band: "aboveGround"});
            camp = ctx.ribbon({color: CAMP_COLOUR, opacity: 0.7, ghostOpacity: 0.25});
            bank = ctx.ribbon({color: BANK_COLOUR, opacity: 1, ghostOpacity: 0.4, band: "aboveGround"});
            names = ctx.tags({css: tag(TARGET_COLOUR, "#1a0d00"), band: "chrome", offsetY: 10});
            places = ctx.tags({css: tag(CAMP_COLOUR, "#04210f"), band: "chrome", offsetY: 10});
        },
        frame(ctx: OverlayContext, marks: CampMarks) {
            if (stale(ctx, marks)) {
                targets?.extrude(
                    marks.targets
                        .filter((s) => floorOf(s.z) === ctx.floor)
                        .flatMap((s) => [
                            {pts: boxPerimeter({x0: s.minX, z0: s.minZ, x1: s.maxX, z1: s.maxZ}), closed: true},
                            {pts: boxPerimeter({x0: s.x, z0: s.z, x1: s.x, z1: s.z}), closed: true},
                        ]),
                    ctx.toWorld,
                );
                const c = marks.camp;
                camp?.extrude(
                    c && floorOf(c.z) === ctx.floor
                        ? [
                              {
                                  pts: boxPerimeter({
                                      x0: c.box.minX,
                                      z0: c.box.minZ,
                                      x1: c.box.maxX,
                                      z1: c.box.maxZ,
                                  }),
                                  closed: true,
                              },
                          ]
                        : [],
                    ctx.toWorld,
                );
                const b = marks.bank;
                bank?.extrude(
                    b && floorOf(b.z) === ctx.floor
                        ? [{pts: boxPerimeter({x0: b.x, z0: b.z, x1: b.x, z1: b.z}), closed: true}]
                        : [],
                    ctx.toWorld,
                );
            }
            names?.begin(ctx.view, ctx.camera);
            for (const s of marks.targets) {
                const live = ctx.npcAt(s.serverIndex);
                if (live?.id === s.id) continue;
                if (floorOf(s.z) !== ctx.floor) continue;
                names?.put(`t${s.serverIndex}`, ctx.toWorld(s.x, s.z, at), `${s.name ?? s.id} #${s.serverIndex}`);
            }
            names?.end();
            places?.begin(ctx.view, ctx.camera);
            if (marks.camp && floorOf(marks.camp.z) === ctx.floor) {
                places?.put("camp", ctx.toWorld(marks.camp.x, marks.camp.box.minZ, at), "camp");
            }
            if (marks.bank && floorOf(marks.bank.z) === ctx.floor) {
                places?.put("bank", ctx.toWorld(marks.bank.x, marks.bank.z, at), marks.bank.name);
            }
            places?.end();
        },
    };
}
