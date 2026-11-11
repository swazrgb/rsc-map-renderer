import L from "leaflet";

export function mirrorX(x: number, width: number): number {
    return width - x;
}

const TILE_CENTERS = new Map<string, L.LatLng>();
const TILE_CENTERS_CAP = 200_000;

export function tileCenter(x: number, zLocal: number, width: number): L.LatLng {
    const key = `${x},${zLocal},${width}`;
    let at = TILE_CENTERS.get(key);
    if (!at) {
        if (TILE_CENTERS.size >= TILE_CENTERS_CAP) TILE_CENTERS.clear();
        at = L.latLng(zLocal + 0.5, mirrorX(x, width) - 0.5);
        TILE_CENTERS.set(key, at);
    }
    return at;
}

export const NORTH_UP_SIMPLE_CRS: L.CRS = L.Util.extend({}, L.CRS.Simple, {
    transformation: new L.Transformation(1, 0, 1, 0),
});
