import {memo, useMemo} from "react";
import type L from "leaflet";
import {Polyline} from "react-leaflet";
import type {RoutePoint} from "@world3d";
import {tileCenter} from "./coords";

export interface RouteLineProps {
    route: RoutePoint[] | null;
    style: L.PolylineOptions;
    width: number;
    floorHeight: number;
    floor: number;
    /** The map pane to draw in; Leaflet's overlay pane when left out. */
    pane?: string;
}

/**
 * A route on the 2D map's floor: one line for each run of its tiles on that floor, broken at each hop's landing
 * (`hop`) and wherever the route leaves the floor.
 */
export const RouteLine = memo(function RouteLine({route, style, width, floorHeight, floor, pane}: RouteLineProps) {
    const segments = useMemo(
        () =>
            (route ? routeSegments(route, floor, floorHeight) : []).map((seg) =>
                seg.map((p) => tileCenter(p.x, p.z - floor * floorHeight, width)),
            ),
        [route, floor, floorHeight, width],
    );
    return (
        <>
            {segments.map((positions, i) => (
                <Polyline key={i} positions={positions} pathOptions={style} pane={pane} interactive={false} />
            ))}
        </>
    );
});

function routeSegments(route: RoutePoint[], floor: number, floorHeight: number): RoutePoint[][] {
    const segs: RoutePoint[][] = [];
    let cur: RoutePoint[] = [];
    for (const p of route) {
        if (Math.floor(p.z / floorHeight) !== floor) {
            if (cur.length > 1) segs.push(cur);
            cur = [];
            continue;
        }
        if (p.hop && cur.length > 0) {
            if (cur.length > 1) segs.push(cur);
            cur = [p];
        } else {
            cur.push(p);
        }
    }
    if (cur.length > 1) segs.push(cur);
    return segs;
}
