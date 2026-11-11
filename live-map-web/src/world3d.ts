/** A transport as the 3D view draws it: every tile it is taken from, and where it lands. */
export interface World3DTransport {
    name: string;
    label?: string | null;
    req?: string | null;
    perEntry?: boolean;
    anchor: {x: number; z: number} | null;
    boundary: {x: number; z: number; dir: number} | null;
    entries: {x: number; z: number}[];
    dx: number;
    dz: number;
    dfloor: number;
}

/** The floor's transports, from its GeoJSON's transport points; null while the GeoJSON loads. */
export function world3dTransports(fc: GeoJSON.FeatureCollection | null): World3DTransport[] | null {
    const byTid = new Map<number, World3DTransport>();
    let anon = -1;
    for (const f of fc?.features ?? []) {
        const p = f.properties as {
            kind?: string;
            tid?: number;
            name?: string;
            label?: string;
            perEntry?: boolean;
            dx?: number;
            dz?: number;
            dfloor?: number;
            req?: string;
            ax?: number;
            az?: number;
            bx?: number;
            bz?: number;
            bdir?: number;
        } | null;
        if (p?.kind !== "transport" || f.geometry.type !== "Point") continue;
        const [cx, cz] = f.geometry.coordinates as [number, number];
        const tid = p.tid ?? anon--;
        let g = byTid.get(tid);
        if (!g) {
            g = {
                name: p.name ?? "transport",
                label: p.label ?? null,
                req: p.req ?? null,
                perEntry: p.perEntry === true,
                anchor: p.ax != null && p.az != null ? {x: p.ax, z: p.az} : null,
                boundary: p.bx != null && p.bz != null ? {x: p.bx, z: p.bz, dir: p.bdir ?? -1} : null,
                entries: [],
                dx: p.dx ?? 0,
                dz: p.dz ?? 0,
                dfloor: p.dfloor ?? 0,
            };
            byTid.set(tid, g);
        }
        g.entries.push({x: Math.floor(cx), z: Math.floor(cz)});
    }
    return fc ? [...byTid.values()] : null;
}
