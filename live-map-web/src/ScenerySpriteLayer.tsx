import L from "leaflet";
import {useEffect, useState} from "react";
import {useMap} from "react-leaflet";
import type {SceneryAtlasEntry, SceneryPlacement} from "./api";
import {fetchScenery, fetchSceneryAtlasIndex, sceneryAtlasPngUrl} from "./api";
import {mirrorX} from "./coords";

interface SceneryData {
    byFloor: Map<number, SceneryPlacement[]>;
    atlas: Map<string, SceneryAtlasEntry>;
    img: HTMLImageElement;
}

interface Item {
    e: SceneryAtlasEntry;
    lat: number;
    lng: number;
    fit: number;
}

let sceneryDataPromise: Promise<SceneryData> | null = null;

async function retrying<T>(f: () => Promise<T>): Promise<T> {
    for (let attempt = 0; ; attempt++) {
        try {
            return await f();
        } catch (e) {
            if (attempt >= 60) throw e;
            await new Promise((res) => setTimeout(res, 5000));
        }
    }
}

function loadSceneryData(): Promise<SceneryData> {
    if (!sceneryDataPromise) {
        sceneryDataPromise = (async () => {
            const [placements, index] = await Promise.all([fetchScenery(), retrying(fetchSceneryAtlasIndex)]);
            const img = new Image();
            img.src = sceneryAtlasPngUrl(index.version);
            await img.decode();

            for (const e of index.entries) {
                if (e.x + e.w > img.naturalWidth || e.y + e.h > img.naturalHeight) {
                    throw new Error(
                        `scenery atlas index v${index.version} does not match the atlas PNG ` +
                            `(${img.naturalWidth}x${img.naturalHeight}) — rect ${e.id}/${e.dir} out of bounds`,
                    );
                }
            }

            const atlas = new Map<string, SceneryAtlasEntry>();
            for (const e of index.entries) atlas.set(`${e.id}/${e.dir}`, e);

            const byFloor = new Map<number, SceneryPlacement[]>();
            for (const p of placements) {
                let arr = byFloor.get(p.floor);
                if (!arr) byFloor.set(p.floor, (arr = []));
                arr.push(p);
            }
            for (const arr of byFloor.values()) arr.sort((a, b) => a.z - b.z || a.x - b.x);
            return {byFloor, atlas, img};
        })();
        sceneryDataPromise.catch(() => {
            sceneryDataPromise = null;
        });
    }
    return sceneryDataPromise;
}

function buildItems(data: SceneryData, floor: number, width: number): Item[] {
    const items: Item[] = [];
    for (const p of data.byFloor.get(floor) ?? []) {
        const e = data.atlas.get(`${p.id}/${p.dir}`);
        if (!e) continue;
        const fit = Math.min(1, e.tw / (e.w / e.ppt), e.th / (e.h / e.ppt));
        items.push({e, lat: p.z + e.th / 2, lng: mirrorX(p.x + e.tw / 2, width), fit});
    }
    return items;
}

interface RendererInternals {
    initialize(options: L.RendererOptions): void;
    _update(): void;
}
const rendererBase = L.Renderer.prototype as unknown as RendererInternals;

interface SceneryCanvas {
    _map: L.Map;
    _bounds: L.Bounds;
    _container: HTMLCanvasElement | undefined;
    _items: Item[];
    _img: HTMLImageElement;
    initialize(items: Item[], img: HTMLImageElement, options: L.RendererOptions): void;
    _initContainer(): void;
    _destroyContainer(): void;
    _update(): void;
    _draw(): void;
}

const sceneryCanvasMethods: Pick<
    SceneryCanvas,
    "initialize" | "_initContainer" | "_destroyContainer" | "_update" | "_draw"
> &
    ThisType<SceneryCanvas> = {
    initialize(items: Item[], img: HTMLImageElement, options: L.RendererOptions) {
        rendererBase.initialize.call(this, options);
        this._items = items;
        this._img = img;
    },

    _initContainer() {
        this._container = document.createElement("canvas");
        this._container.style.pointerEvents = "none";
    },

    _destroyContainer() {
        this._container?.remove();
        this._container = undefined;
    },

    _update() {
        rendererBase._update.call(this);
        const b = this._bounds;
        const canvas = this._container;
        if (!canvas || !b.min) return;
        const size = b.getSize();
        if (canvas.width !== size.x || canvas.height !== size.y) {
            canvas.width = size.x;
            canvas.height = size.y;
        }
        L.DomUtil.setPosition(canvas, b.min);
        this._draw();
    },

    _draw() {
        const map = this._map;
        const canvas = this._container;
        const ctx = canvas?.getContext("2d");
        const min = this._bounds.min;
        if (!canvas || !ctx || !min) return;
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        const zoom = map.getZoom();
        const pxPerTile = Math.pow(2, zoom);
        const origin = map.latLngToLayerPoint([0, 0]);
        const ox = origin.x - min.x;
        const oy = origin.y - min.y;
        const cw = canvas.width;
        const ch = canvas.height;

        for (const it of this._items) {
            const e = it.e;
            const s = (pxPerTile / e.ppt) * it.fit;
            const w = e.w * s;
            const h = e.h * s;
            if (w < 2 && h < 2) continue;
            const x = ox + it.lng * pxPerTile - e.ax * s;
            const y = oy + it.lat * pxPerTile - e.ay * s;
            if (x + w < 0 || y + h < 0 || x > cw || y > ch) continue;
            ctx.drawImage(this._img, e.x, e.y, e.w, e.h, x, y, w, h);
        }
    },
};

const SceneryCanvas = L.Renderer.extend(sceneryCanvasMethods) as new (
    items: Item[],
    img: HTMLImageElement,
    options: L.RendererOptions,
) => L.Renderer;

export interface ScenerySpriteLayerProps {
    floor: number;
    width: number;
    pane: string;
}

export function ScenerySpriteLayer({floor, width, pane}: ScenerySpriteLayerProps) {
    const map = useMap();
    const [data, setData] = useState<SceneryData | null>(null);

    useEffect(() => {
        let alive = true;
        loadSceneryData().then(
            (d) => alive && setData(d),
            (e: unknown) => console.error("scenery sprite layer disabled:", e),
        );
        return () => {
            alive = false;
        };
    }, []);

    useEffect(() => {
        if (!data) return;
        const layer = new SceneryCanvas(buildItems(data, floor, width), data.img, {pane, padding: 0.3});
        map.addLayer(layer);
        return () => {
            map.removeLayer(layer);
        };
    }, [map, data, floor, width, pane]);

    return null;
}
