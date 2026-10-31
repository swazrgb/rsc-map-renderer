import {boxPerimeter, drapeGate} from "./World3DOverlays";
import type {OverlayContext, OverlayPoint, ViewerOverlay} from "./World3DOverlays";
import type {Ribbon} from "./World3DSight";
import type {TagPool} from "./World3DTags";
import type {WorldMeshManifest} from "./api";

// nav's ClusterGeometry grid: 64-tile squares, 16 by 15 per floor, numbered floor * 240 + row * 16 + column.
const CLUSTER_TILES = 64;
const CLUSTERS_X = 16;
const CLUSTERS_Y = 15;
const PER_FLOOR = CLUSTERS_X * CLUSTERS_Y;
const FLOOR_HEIGHT = 944;
const BASE = "rgb(255, 209, 102)";
const LIT = "rgb(255, 233, 168)";

/** Cluster `id`'s tiles, inclusive, z absolute; the last row stops at its floor's edge. */
function clusterBox(id: number): {x0: number; z0: number; x1: number; z1: number} {
    const floor = Math.floor(id / PER_FLOOR);
    const local = id % PER_FLOOR;
    const cx = local % CLUSTERS_X;
    const cy = Math.floor(local / CLUSTERS_X);
    const x0 = cx * CLUSTER_TILES;
    const z0 = floor * FLOOR_HEIGHT + cy * CLUSTER_TILES;
    const z1 = Math.min(z0 + CLUSTER_TILES, (floor + 1) * FLOOR_HEIGHT) - 1;
    return {x0, z0, x1: x0 + CLUSTER_TILES - 1, z1};
}

/** Every cluster the bake's cells overlap, ascending. */
export function manifestClusters(m: WorldMeshManifest): number[] {
    const ids = new Set<number>();
    for (const c of m.cells) {
        const cx0 = Math.max(0, Math.floor(c.botX0 / CLUSTER_TILES));
        const cx1 = Math.min(CLUSTERS_X - 1, Math.floor((c.botX0 + m.cellTiles - 1) / CLUSTER_TILES));
        const cy0 = Math.max(0, Math.floor(c.botZ0 / CLUSTER_TILES));
        const cy1 = Math.min(CLUSTERS_Y - 1, Math.floor((c.botZ0 + m.cellTiles - 1) / CLUSTER_TILES));
        for (let cx = cx0; cx <= cx1; cx++) {
            for (let cy = cy0; cy <= cy1; cy++) {
                ids.add(c.plane * PER_FLOOR + cy * CLUSTERS_X + cx);
            }
        }
    }
    return [...ids].sort((a, b) => a - b);
}

/** Outlines and numbers the clusters of the floor in view, the hovered one brighter. */
export function clusterOverlay(): ViewerOverlay<readonly number[]> {
    let ribbon: Ribbon | null = null;
    let lit: Ribbon | null = null;
    let labels: TagPool | null = null;
    const stale = drapeGate();
    let hovered: number | null = null;
    let litFor: number | null = null;
    return {
        mount(ctx: OverlayContext) {
            ribbon = ctx.ribbon({color: 0xffd166, opacity: 0.55, ghostOpacity: 0.15});
            lit = ctx.ribbon({color: 0xffe9a8, opacity: 1, ghostOpacity: 0.4, band: "aboveGround"});
            litFor = null;
            labels = ctx.tags({
                css: "font:600 12px monospace;color:#ffd166;" + "text-shadow:0 1px 2px rgba(0,0,0,.9);",
                anchor: "centre",
                interactive: false,
            });
        },
        frame(ctx: OverlayContext, ids: readonly number[]) {
            const shown = ids.filter((id) => Math.floor(id / PER_FLOOR) === ctx.floor);
            const redrape = stale(ctx, ids);
            if (redrape) {
                ribbon?.extrude(
                    shown.map((id) => ({pts: boxPerimeter(clusterBox(id)), closed: true})),
                    ctx.toWorld,
                );
            }
            if (redrape || hovered !== litFor) {
                litFor = hovered;
                lit?.extrude(
                    hovered == null ? [] : [{pts: boxPerimeter(clusterBox(hovered)), closed: true}],
                    ctx.toWorld,
                );
            }
            labels?.begin(ctx.view, ctx.camera);
            for (const id of shown) {
                const b = clusterBox(id);
                const tag = labels?.put(String(id), ctx.toWorld((b.x0 + b.x1) / 2, (b.z0 + b.z1) / 2), `#${id}`);
                const colour = id === hovered ? LIT : BASE;
                if (tag && tag.style.color !== colour) tag.style.color = colour;
            }
            labels?.end();
        },
        hitTest(at: OverlayPoint): string | null {
            const cx = Math.floor(at.x / CLUSTER_TILES);
            const local = at.z - at.floor * FLOOR_HEIGHT;
            const cy = Math.floor(local / CLUSTER_TILES);
            if (cx < 0 || cx >= CLUSTERS_X || cy < 0 || cy >= CLUSTERS_Y || local < 0) return null;
            return String(at.floor * PER_FLOOR + cy * CLUSTERS_X + cx);
        },
        hover(hit: string | null) {
            hovered = hit == null ? null : Number(hit);
        },
    };
}
