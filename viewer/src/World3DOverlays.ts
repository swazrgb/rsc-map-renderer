import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import {Ribbon} from "./World3DSight";
import {TagPool, type TagStyle, type ViewSize} from "./World3DTags";

/**
 * Draw bands, as `renderOrder` in the transparent pass: `ground` draped under the characters,
 * `aboveGround` over ground decoration but still under them, `chrome` over everything. The sprite
 * layers sit between, at 11 to 13. renderOrder sorts only within a pass, so overlays are transparent.
 */
export const OVERLAY_BAND = {
    ground: 9,
    aboveGround: 10,
    chrome: 15,
} as const;

type OverlayBand = keyof typeof OVERLAY_BAND;

/** A ground point under the cursor, z absolute. */
export interface OverlayPoint {
    x: number;
    z: number;
    floor: number;
    world: THREE.Vector3;
}

/**
 * What an overlay is handed each frame. Its fields are refreshed in place: read them fresh each
 * frame rather than keeping values out of it.
 */
export interface OverlayContext {
    /** This overlay's own group, removed with it and left out of picking, so it never blocks a click. */
    group: THREE.Group;
    camera: THREE.Camera;
    /** The floor shown: 0 ground, 1 and 2 upper, 3 underground. Filtering to it is the overlay's job. */
    floor: number;
    /** Tile to world point, terrain height included; takes absolute z, and fills `out` when given. */
    toWorld: (x: number, z: number, out?: THREE.Vector3) => THREE.Vector3;
    /**
     * Where the view draws the live NPC with this server index this frame, mid-glide, z absolute.
     * `id` is its definition, to check it is the NPC meant, since the server reuses freed indexes. Null
     * when no live NPC by that index is drawn on the shown floor.
     */
    npcAt: (serverIndex: number) => {x: number; z: number; id: number | null} | null;
    /** Changes when the terrain under the view has settled at a new shape; draped geometry must be rebuilt. */
    heightsRev: number;
    /** The view's height in tiles. */
    zoomTiles: number;
    view: ViewSize;
    /** A label pool owned by this overlay and disposed with it; make one per style, at mount. */
    tags(style: TagStyle): TagPool;
    /** A draped outline owned by this overlay and disposed with it. */
    ribbon(opts: {color: number; opacity: number; ghostOpacity: number; band?: OverlayBand}): Ribbon;
}

/**
 * One overlay. `frame` runs every frame, so it checks for itself what needs redoing. Hover goes to
 * every overlay whose `hitTest` claims the cursor's point; clicks go only to interactive labels, so
 * an overlay's area never swallows clicks on the world.
 */
export interface ViewerOverlay<T = unknown> {
    /** Called once, before the first frame. */
    mount?(ctx: OverlayContext): void;
    frame(ctx: OverlayContext, data: T): void;
    /** A key for what the point landed on, or null; `hover` fires when the key changes. */
    hitTest?(at: OverlayPoint, data: T): string | null;
    /** Told the new hover key, or null when the cursor leaves. */
    hover?(hit: string | null, ctx: OverlayContext, data: T): void;
    /** Called on removal, for anything beyond the group, tag pools and ribbons, which are torn down already. */
    dispose?(): void;
}

/**
 * An overlay as handed to the view: a stable implementation and this render's data. Keyed by `id`,
 * so only adding and removing an entry mounts or disposes. Built only by {@link overlay}.
 */
export interface OverlayEntry {
    readonly id: string;
    readonly impl: ViewerOverlay;
    readonly data: unknown;
}

/** Pairs an implementation with its data, type-checked. */
export function overlay<T>(id: string, impl: ViewerOverlay<T>, data: T): OverlayEntry {
    return {id, impl: impl, data};
}

/**
 * A gate that returns true when anything draped geometry depends on changed since its last call:
 * the data, by identity, the floor, the settled terrain, or a remount, which brings a new context.
 */
export function drapeGate(): (ctx: OverlayContext, data: unknown) => boolean {
    let lastCtx: OverlayContext | null = null;
    // Not null or undefined: both are legitimate first values.
    let lastData: unknown = UNSET;
    let lastFloor = Number.NaN;
    let lastHeights = Number.NaN;
    return (ctx, data) => {
        if (ctx === lastCtx && data === lastData && ctx.floor === lastFloor && ctx.heightsRev === lastHeights) {
            return false;
        }
        lastCtx = ctx;
        lastData = data;
        lastFloor = ctx.floor;
        lastHeights = ctx.heightsRev;
        return true;
    };
}

const UNSET = Symbol("unset");

/** An inclusive tile box's outer edges: corners ordered, grown by the half tile from centre to edge. */
export function boxEdges(box: {x0: number; z0: number; x1: number; z1: number}): {
    x0: number;
    z0: number;
    x1: number;
    z1: number;
} {
    return {
        x0: Math.min(box.x0, box.x1) - 0.5,
        x1: Math.max(box.x0, box.x1) + 0.5,
        z0: Math.min(box.z0, box.z1) - 0.5,
        z1: Math.max(box.z0, box.z1) + 0.5,
    };
}

/**
 * The perimeter of an inclusive tile box as points to drape, tile by tile so the outline follows
 * the terrain, strided to at most about `maxPoints` points.
 */
export function boxPerimeter(
    box: {x0: number; z0: number; x1: number; z1: number},
    maxPoints = 512,
): {x: number; z: number}[] {
    const {x0, x1, z0, z1} = boxEdges(box);
    const stride = Math.max(1, Math.ceil((2 * (x1 - x0 + z1 - z0) + 4) / maxPoints));
    const pts: {x: number; z: number}[] = [];
    for (let x = x0; x < x1; x += stride) pts.push({x, z: z0});
    for (let z = z0; z < z1; z += stride) pts.push({x: x1, z});
    for (let x = x1; x > x0; x -= stride) pts.push({x, z: z1});
    for (let z = z1; z > z0; z -= stride) pts.push({x: x0, z});
    if (pts.length < 2) pts.push({x: x0, z: z0}, {x: x1, z: z1});
    return pts;
}

interface Slot {
    impl: ViewerOverlay;
    ctx: OverlayContext;
    group: THREE.Group;
    tags: TagPool[];
    ribbons: Ribbon[];
    hover: string | null;
}

/** The per-frame half of the context, shared by every overlay. */
export interface OverlayFrame {
    floor: number;
    heightsRev: number;
    zoomTiles: number;
    view: ViewSize;
    toWorld: (x: number, z: number, out?: THREE.Vector3) => THREE.Vector3;
    npcAt: (serverIndex: number) => {x: number; z: number; id: number | null} | null;
}

/** Mounts, runs and disposes the overlays the view is given. */
export class OverlayHost {
    private readonly slots = new Map<string, Slot>();
    private readonly failed = new Set<string>();

    constructor(
        private readonly scene: THREE.Scene,
        private readonly camera: THREE.Camera,
        private readonly tagHost: HTMLElement,
    ) {}

    /**
     * Mounts and drops overlays to match `entries`, then frames each in order. An overlay that throws
     * is logged once and does not stop the rest.
     */
    sync(entries: readonly OverlayEntry[], frame: OverlayFrame): void {
        for (const [id, slot] of this.slots) {
            if (!entries.some((e) => e.id === id)) {
                this.drop(id, slot);
            }
        }
        for (const entry of entries) {
            let slot = this.slots.get(entry.id);
            if (slot && slot.impl !== entry.impl) {
                this.drop(entry.id, slot);
                slot = undefined;
            }
            if (!slot) {
                slot = this.mount(entry);
                // Before mount, so mount sees the first frame's context.
                this.refresh(slot, frame);
                slot.impl.mount?.(slot.ctx);
            }
            this.refresh(slot, frame);
            try {
                slot.impl.frame(slot.ctx, entry.data);
            } catch (e) {
                if (!this.failed.has(entry.id)) {
                    this.failed.add(entry.id);
                    console.error(`[overlay] ${entry.id} failed to draw:`, e);
                }
            }
        }
    }

    /** Hands the cursor's ground point to every overlay with a hover handler. */
    hover(at: OverlayPoint | null, entries: readonly OverlayEntry[]): void {
        for (const entry of entries) {
            const slot = this.slots.get(entry.id);
            if (!slot?.impl.hover) continue;
            const hit = at && slot.impl.hitTest ? slot.impl.hitTest(at, entry.data) : null;
            if (hit === slot.hover) continue;
            slot.hover = hit;
            slot.impl.hover(hit, slot.ctx, entry.data);
        }
    }

    dispose(): void {
        for (const [id, slot] of this.slots) this.drop(id, slot);
    }

    private mount(entry: OverlayEntry): Slot {
        const group = new THREE.Group();
        group.userData.noPick = true;
        this.scene.add(freezeTransform(group));
        const slot: Slot = {
            impl: entry.impl,
            group,
            tags: [],
            ribbons: [],
            hover: null,
            ctx: {
                group,
                camera: this.camera,
                floor: 0,
                heightsRev: -1,
                zoomTiles: 0,
                view: {w: 0, h: 0},
                toWorld: (_x, _z, out) => out ?? new THREE.Vector3(),
                npcAt: () => null,
                tags: (style: TagStyle) => {
                    const pool = new TagPool(this.tagHost, style);
                    slot.tags.push(pool);
                    return pool;
                },
                ribbon: (opts) => {
                    const r = new Ribbon(group, opts.color, opts.opacity, opts.ghostOpacity);
                    r.band = OVERLAY_BAND[opts.band ?? "ground"];
                    slot.ribbons.push(r);
                    return r;
                },
            },
        };
        this.slots.set(entry.id, slot);
        return slot;
    }

    private refresh(slot: Slot, frame: OverlayFrame): void {
        slot.ctx.floor = frame.floor;
        slot.ctx.heightsRev = frame.heightsRev;
        slot.ctx.zoomTiles = frame.zoomTiles;
        slot.ctx.view = frame.view;
        slot.ctx.toWorld = frame.toWorld;
        slot.ctx.npcAt = frame.npcAt;
    }

    private drop(id: string, slot: Slot): void {
        slot.impl.dispose?.();
        for (const pool of slot.tags) pool.dispose();
        for (const r of slot.ribbons) r.dispose();
        this.scene.remove(slot.group);
        this.slots.delete(id);
    }
}
