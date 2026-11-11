import {useEffect} from "react";
import {useMap, useMapEvents} from "react-leaflet";
import type {TilePoint} from "./WorldMap";

export interface TileClickProbeProps {
    /** Whether a click picks a tile; the cursor turns to a crosshair while it does. */
    active: boolean;
    width: number;
    floorHeight: number;
    floor: number;
    /** The tile clicked, its z counting the floors below it as the map's coordinates do. */
    onTile: (tile: TilePoint) => void;
}

/** Turns a click on the 2D map into the tile under it. Mount it among the map's overlays. */
export function TileClickProbe({active, width, floorHeight, floor, onTile}: TileClickProbeProps) {
    const map = useMap();
    useEffect(() => {
        const el = map.getContainer();
        el.classList.toggle("pathfinder-cursor", active);
        return () => el.classList.remove("pathfinder-cursor");
    }, [active, map]);

    useMapEvents({
        click: (e) => {
            if (!active) return;
            const x = Math.floor(width - e.latlng.lng);
            const localZ = Math.floor(e.latlng.lat);
            onTile({x, z: floor * floorHeight + localZ});
        },
    });
    return null;
}
