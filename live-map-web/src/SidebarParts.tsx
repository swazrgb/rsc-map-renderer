import {useAssetStatus} from "./assetStatus";
import type {BotLive} from "./api";
import type {FloorSetter} from "./floor";
import {at} from "./lookup";
import type {Connection} from "./useLiveStream";
import type {MapLayers} from "./WorldMap";

/** The 3D view's own toggles. */
export interface World3DOptionValues {
    roofs: boolean;
    sight: boolean;
    tags: boolean;
    clusters: boolean;
    components: boolean;
    below: boolean;
}

/** A row of the layer list: `on3d` when the 3D view draws the layer too. */
export interface LayerRow<K extends string> {
    key: K;
    label: string;
    on3d: boolean;
}

/** The map's layers, as the layer list shows them. */
export const MAP_LAYER_ROWS: readonly LayerRow<keyof MapLayers>[] = [
    {key: "terrain", label: "terrain", on3d: false},
    {key: "walls", label: "walls", on3d: false},
    {key: "blocked", label: "blocked tiles", on3d: false},
    {key: "doors", label: "doors", on3d: false},
    {key: "transports", label: "transports", on3d: true},
    {key: "bots", label: "bots", on3d: true},
    {key: "npcs", label: "npcs", on3d: true},
    {key: "npcSpawns", label: "npc spawns", on3d: true},
    {key: "npcWanderZones", label: "npc wander zones", on3d: false},
    {key: "dangerZones", label: "danger zones", on3d: true},
    {key: "players", label: "other players", on3d: true},
    {key: "shops", label: "shops", on3d: true},
    {key: "scenerySprites", label: "scenery sprites", on3d: false},
    {key: "objects", label: "game objects", on3d: false},
    {key: "wallObjects", label: "wall objects", on3d: false},
];

const CONNECTION_LABEL: Record<Connection, string> = {
    idle: "idle",
    connecting: "connecting…",
    live: "live",
    error: "disconnected (retrying)",
};

const CONNECTION_CLASS: Record<Connection, string> = {
    idle: "idle",
    connecting: "idle",
    live: "live",
    error: "err",
};

/** The socket's state, as the sidebar shows it. */
export function ConnectionStatus({connection}: {connection: Connection}) {
    return (
        <span id="status" className={CONNECTION_CLASS[connection]}>
            {CONNECTION_LABEL[connection]}
        </span>
    );
}

/** Shown while the server's 3D assets are older than this page needs; the page reloads once they are not. */
export function BakeWarning() {
    const {waiting, detail} = useAssetStatus();
    if (!waiting) return null;
    return (
        <div className="bake-warn" title={detail}>
            old bake — please wait
        </div>
    );
}

/** The 3D view's toggles and its flyby button. */
export function World3DOptions({
    values,
    onChange,
    flying,
    onFly,
}: {
    values: World3DOptionValues;
    onChange: (next: World3DOptionValues) => void;
    flying: boolean;
    onFly: () => void;
}) {
    return (
        <>
            <label className="row">
                <input
                    type="checkbox"
                    checked={values.roofs}
                    onChange={(e) =>
                        onChange({
                            ...values,
                            roofs: e.target.checked,
                        })
                    }
                />
                roofs
            </label>
            <label className="row">
                <input
                    type="checkbox"
                    checked={values.sight}
                    onChange={(e) =>
                        onChange({
                            ...values,
                            sight: e.target.checked,
                        })
                    }
                />
                sight outline
            </label>
            <label className="row">
                <input
                    type="checkbox"
                    checked={values.tags}
                    onChange={(e) =>
                        onChange({
                            ...values,
                            tags: e.target.checked,
                        })
                    }
                />
                nametags
            </label>
            <label className="row" title="the pathfinder's clusters (64×64 tiles) and their ids">
                <input
                    type="checkbox"
                    checked={values.clusters}
                    onChange={(e) =>
                        onChange({
                            ...values,
                            clusters: e.target.checked,
                        })
                    }
                />
                clusters
            </label>
            <label
                className="row"
                title="the collision map's walking components: tiles one can walk between share an id; outlines are where they meet"
            >
                <input
                    type="checkbox"
                    checked={values.components}
                    onChange={(e) =>
                        onChange({
                            ...values,
                            components: e.target.checked,
                        })
                    }
                />
                components
            </label>
            <label className="row" title="on floor 1 or 2, draw the floors beneath as well, a storey lower each">
                <input
                    type="checkbox"
                    checked={values.below}
                    onChange={(e) =>
                        onChange({
                            ...values,
                            below: e.target.checked,
                        })
                    }
                />
                floors below
            </label>
            <button type="button" onClick={onFly}>
                {flying ? "stop flyby" : "flyby"}
            </button>
        </>
    );
}

/** A checkbox a layer; on the 3D view, the layers it does not draw are left out. */
export function LayerList<K extends string>({
    rows,
    layers,
    onChange,
    on3dView,
}: {
    rows: readonly LayerRow<K>[];
    layers: Record<K, boolean>;
    onChange: (key: K, on: boolean) => void;
    on3dView: boolean;
}) {
    return (
        <>
            {rows
                .filter(({on3d}) => !on3dView || on3d)
                .map(({key, label}) => (
                    <label key={key} className="row">
                        <input
                            type="checkbox"
                            checked={layers[key]}
                            onChange={(e) => onChange(key, e.target.checked)}
                        />
                        {label}
                    </label>
                ))}
        </>
    );
}

interface FloorPickerProps {
    floors: number[];
    floor: number;
    onFloorChange: FloorSetter;
}

const VERTICAL_ORDER = [3, 0, 1, 2];

const FLOOR_LABEL: Record<number, string> = {
    3: "basement",
    0: "ground",
    1: "floor 1",
    2: "floor 2",
};

/** The floor shown, with a step up and down in the order the floors stack. */
export function FloorPicker({floors, floor, onFloorChange}: FloorPickerProps) {
    const order = VERTICAL_ORDER.filter((f) => floors.includes(f));
    const idx = order.indexOf(floor);
    const canDown = idx > 0;
    const canUp = idx >= 0 && idx < order.length - 1;

    const step = (delta: number) => {
        const next = at(order, idx + delta);
        if (next !== undefined) onFloorChange(next, "picker-step");
    };

    return (
        <div className="floor-picker">
            <select id="floor" value={floor} onChange={(e) => onFloorChange(parseInt(e.target.value, 10), "picker")}>
                {order.map((f) => (
                    <option key={f} value={f}>
                        {FLOOR_LABEL[f] ?? `floor ${f}`}
                    </option>
                ))}
            </select>
            <button
                type="button"
                className="floor-step"
                onClick={() => step(-1)}
                disabled={!canDown}
                title="floor down"
            >
                −
            </button>
            <button type="button" className="floor-step" onClick={() => step(1)} disabled={!canUp} title="floor up">
                +
            </button>
        </div>
    );
}

/** One line on where a bot is: its tile, floor and server tick. */
export function describeBot(s: BotLive | null): string {
    if (!s) return "no data";
    if (!s.position) return "position unknown";
    const {x, z, floor} = s.position;
    return `${x}, ${z} · ${FLOOR_LABEL[floor] ?? `floor ${floor}`} · tick ${s.serverTick ?? "?"}`;
}
