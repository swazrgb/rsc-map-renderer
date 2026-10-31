import * as THREE from "three";

/**
 * Stacking bands for tags, as z-index: `world` for labels on world features, `entity` for nameplates
 * and splats, `chrome` for interactive chips over both. The DOM counterpart of `OVERLAY_BAND`.
 */
export const TAG_BAND = {
    world: 880000,
    entity: 890000,
    chrome: 905000,
} as const;

type TagBand = keyof typeof TAG_BAND;

/**
 * How a pool's tags look and sit. `css` is applied once per div and must not set position,
 * transform or z-index. `offsetY` lifts a tag that many pixels above its point; `anchor` (default
 * `above`) puts its bottom edge on the point, or its centre. `interactive` tags take clicks, though a
 * press on one still starts a camera drag. `dataset` is stamped on each div.
 */
export interface TagStyle {
    css: string;
    band?: TagBand;
    offsetY?: number;
    anchor?: "above" | "centre";
    interactive?: boolean;
    dataset?: Record<string, string>;
}

/** The view's size in CSS pixels, measured once per frame before anything writes a style. */
export interface ViewSize {
    w: number;
    h: number;
}

/** A tag built into its div by `build`, rebuilt only when `stamp` changes. */
export interface TagContent {
    stamp: string;
    build(div: HTMLDivElement): void;
}

const interactivePools = new Map<string, TagPool>();
let nextPoolId = 0;

/**
 * The interactive pool and tag key under an event target, or null. Resolve it at pointer down: the
 * view captures the pointer for a drag, which retargets the click away from the tag.
 */
export function resolveTagClick(target: EventTarget | null): {pool: TagPool; key: string} | null {
    const el = target instanceof Element ? target.closest<HTMLElement>("[data-tag-pool]") : null;
    const pool = el && interactivePools.get(el.dataset.tagPool ?? "");
    const key = el?.dataset.tagKey;
    return pool?.onClick && key != null ? {pool, key} : null;
}

/**
 * DOM labels anchored to world points, reused across frames by key. Each frame is `begin`, a `put`
 * per visible tag, then `end`, which removes every tag not put that frame.
 */
export class TagPool {
    private readonly divs = new Map<string, HTMLDivElement>();
    private readonly used = new Set<string>();
    private view: ViewSize = {w: 0, h: 0};
    private camera: THREE.Camera | null = null;
    private readonly ndc = new THREE.Vector3();
    /** Receives clicks on an interactive pool's tags: a press and release within the view's click threshold. */
    onClick: ((key: string, ev: MouseEvent) => void) | null = null;
    private readonly id = String(nextPoolId++);

    constructor(
        private readonly host: HTMLElement,
        private readonly style: TagStyle,
    ) {
        if (style.interactive) interactivePools.set(this.id, this);
    }

    /** Opens a frame; the camera's world matrix is refreshed here, once. */
    begin(view: ViewSize, camera: THREE.Camera): void {
        this.view = view;
        this.camera = camera;
        camera.updateMatrixWorld();
        this.used.clear();
    }

    /**
     * Places one tag at `at`. Returns its div, or null when the point is off screen or behind the
     * camera. `align` overrides the pool's anchor: the point of the tag that sits on `at`, as fractions
     * of its size from its top left.
     */
    put(
        key: string,
        at: THREE.Vector3,
        text: string | TagContent,
        align?: {x: number; y: number},
    ): HTMLDivElement | null {
        if (!this.camera) return null;
        const v = this.ndc.copy(at).project(this.camera);
        if (v.z > 1 || v.z < -1 || v.x < -1.05 || v.x > 1.05 || v.y < -1.05 || v.y > 1.05) {
            return null;
        }
        this.used.add(key);
        let div = this.divs.get(key);
        if (!div) {
            div = document.createElement("div");
            div.style.cssText =
                "position:absolute;left:0;top:0;white-space:nowrap;" +
                `z-index:${TAG_BAND[this.style.band ?? "world"]};` +
                (this.style.interactive ? "pointer-events:auto;cursor:pointer;" : "pointer-events:none;") +
                this.style.css;
            for (const [k, val] of Object.entries(this.style.dataset ?? {})) {
                div.dataset[k] = val;
            }
            if (this.style.interactive) {
                div.dataset.tagPool = this.id;
                div.dataset.tagKey = key;
            }
            this.host.appendChild(div);
            this.divs.set(key, div);
        }
        if (typeof text === "string") {
            if (div.dataset.stamp !== undefined) delete div.dataset.stamp;
            if (div.textContent !== text) div.textContent = text;
        } else if (div.dataset.stamp !== text.stamp) {
            div.replaceChildren();
            text.build(div);
            div.dataset.stamp = text.stamp;
        }
        const px = ((v.x + 1) / 2) * this.view.w;
        const py = ((1 - v.y) / 2) * this.view.h - (this.style.offsetY ?? 0);
        const self = align
            ? `translate(${-align.x * 100}%,${-align.y * 100}%)`
            : this.style.anchor === "centre"
              ? "translate(-50%,-50%)"
              : "translate(-50%,-100%)";
        // One transform, never left or top: those are layout inputs, and hundreds move per frame.
        const t = `translate(${px}px,${py}px) ${self}`;
        if (div.style.transform !== t) div.style.transform = t;
        return div;
    }

    get(key: string): HTMLDivElement | null {
        return this.divs.get(key) ?? null;
    }

    end(): void {
        for (const [key, div] of this.divs) {
            if (!this.used.has(key)) {
                div.remove();
                this.divs.delete(key);
            }
        }
        this.camera = null;
    }

    /** Removes every tag; the pool stays usable. */
    clear(): void {
        for (const div of this.divs.values()) div.remove();
        this.divs.clear();
        this.used.clear();
    }

    /** Removes every tag and stops answering {@link resolveTagClick}. */
    dispose(): void {
        this.clear();
        interactivePools.delete(this.id);
    }
}
