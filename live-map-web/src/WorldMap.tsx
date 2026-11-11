import {
    memo,
    type CSSProperties,
    type ReactNode,
    type SubmitEvent,
    useCallback,
    useEffect,
    useMemo,
    useRef,
    useState,
} from "react";
import {createPortal} from "react-dom";
import L from "leaflet";
import {CircleMarker, GeoJSON, ImageOverlay, MapContainer, Marker, Pane, useMap, useMapEvents} from "react-leaflet";
import {FloatingWindow, type PickedEntity, type WindowAnchor} from "@world3d";
import type {
    BotLive,
    FeatureProperties,
    FloorsResponse,
    GlobalPlayer,
    MapEntity,
    NpcSpawnProperties,
    ObjectDef,
    ObjectRespawn,
    Shop,
    TransportProperties,
} from "./api";
import {
    dangerPngUrl,
    fetchFloorDanger,
    fetchFloorGeoJson,
    loadedFloorGeoJson,
    terrainPngUrl,
    wallsFillPngUrl,
    wallsLinesPngUrl,
} from "./api";
import {useBotInterest} from "./botInterest";
import {mirrorX, NORTH_UP_SIMPLE_CRS, tileCenter} from "./coords";
import {noop} from "./errors";
import type {FloorSetter} from "./floor";
import {clipboard} from "./lookup";
import {ScenerySpriteLayer} from "./ScenerySpriteLayer";
import "./smoothWheelZoom";
import type {WorldPools} from "./worldPools";

/** Which of the map's layers are drawn. */
export interface MapLayers {
    terrain: boolean;
    walls: boolean;
    blocked: boolean;
    doors: boolean;
    transports: boolean;
    bots: boolean;
    npcs: boolean;
    npcSpawns: boolean;
    npcWanderZones: boolean;
    dangerZones: boolean;
    players: boolean;
    shops: boolean;
    objects: boolean;
    wallObjects: boolean;
    scenerySprites: boolean;
}

export interface TilePoint {
    x: number;
    z: number;
}

/** A game object, with what replaces it and in how many ticks, when it is spent and respawning. */
export type InspectorObject = MapEntity & {respawnName?: string; respawnTicks?: number};

/** What a page's own layers of the map are drawn from: the map's geometry and the entities it shows. */
export interface MapOverlayContext {
    width: number;
    floorHeight: number;
    floor: number;
    npcs: MapEntity[];
    /** The players the bots see, the bots themselves left out. */
    players: MapEntity[];
    objects: InspectorObject[];
    wallObjects: MapEntity[];
    fc: GeoJSON.FeatureCollection | null;
}

export interface WorldMapProps {
    floors: FloorsResponse;
    floor: number;
    layers: MapLayers;
    world: WorldPools;
    /** False while the map is hidden behind another view: it neither writes the URL nor pans to a selection. */
    active?: boolean;
    /** The view shared with the 3D world, kept in step as either moves. */
    sharedView?: {current: {x: number; z: number; zoom: number} | null};
    bots: BotLive[];
    /** The spent objects that respawn, named from `objectDefs`. */
    objectRespawns?: ObjectRespawn[];
    objectDefs?: Map<number, ObjectDef>;
    selectedBot: string | null;
    /** Bots drawn with the group's outline. */
    groupBots?: ReadonlySet<string> | null;
    shops?: Shop[];
    selectedShop?: string | null;
    onSelectBot: (username: string) => void;
    onSelectShop?: (id: string) => void;
    followBot: boolean;
    /** A player the map keeps centred on, by name, over `followBot`. */
    followPlayer?: string | null;
    /** A change pans to the selected bot, keeping the zoom. */
    focusBot: number;
    panTarget: {x: number; z: number; floor: number; label: string; nonce: number} | null;
    onFloorChange: FloorSetter;
    onHover: (latlng: L.LatLng | null) => void;
    /** False while a page's own tool takes the clicks: a click picks nothing. */
    interactive?: boolean;
    /** Players beyond the bots' sight, which the search finds by name. */
    globalPlayers?: GlobalPlayer[];
    onPickEntity?: (pick: PickedEntity) => void;
    /** A page's own layers, drawn inside the map. */
    overlays?: (ctx: MapOverlayContext) => ReactNode;
}

const DOORS_RENDERER = L.svg({pane: "doorsPane", padding: 0.5});
const TRANSPORT_RENDERER = L.canvas({pane: "transportsPane", padding: 0.5});
const NPC_SPAWN_RENDERER = L.svg({pane: "npcSpawnsPane", padding: 0.5});
const NPC_WANDER_RENDERER = L.svg({pane: "npcWanderPane", padding: 0.5});

const BOT_RENDERER = L.svg({pane: "botsPane", padding: 0.5});

const DOOR_STYLE: L.PathOptions = {
    color: "#f0883e",
    weight: 3,
    opacity: 0.95,
    lineCap: "butt",
    renderer: DOORS_RENDERER,
};

const TRANSPORT_STYLE: L.CircleMarkerOptions = {
    radius: 4,
    color: "#222",
    weight: 1,
    fillColor: "#ffd166",
    fillOpacity: 0.95,
    renderer: TRANSPORT_RENDERER,
};

const NPC_SPAWN_STYLE: L.CircleMarkerOptions = {
    radius: 4,
    color: "#222",
    weight: 1,
    fillColor: "#7ee787",
    fillOpacity: 0.9,
    renderer: NPC_SPAWN_RENDERER,
};
const NPC_SPAWN_STYLE_AGGRO: L.CircleMarkerOptions = {
    ...NPC_SPAWN_STYLE,
    fillColor: "#ff7b72",
};
const NPC_SPAWN_BOX_STYLE: L.PathOptions = {
    color: "#7ee787",
    weight: 1,
    opacity: 0.4,
    fill: false,
    interactive: false,
    renderer: NPC_WANDER_RENDERER,
};
const NPC_SPAWN_BOX_STYLE_AGGRO: L.PathOptions = {
    ...NPC_SPAWN_BOX_STYLE,
    color: "#ff3b3b",
};
const NPC_WANDER_HIGHLIGHT: L.PolylineOptions = {
    color: "#7ee787",
    weight: 3,
    opacity: 1,
    fill: true,
    fillColor: "#7ee787",
    fillOpacity: 0.12,
    interactive: false,
    renderer: NPC_WANDER_RENDERER,
};
const NPC_WANDER_HIGHLIGHT_AGGRO: L.PolylineOptions = {
    ...NPC_WANDER_HIGHLIGHT,
    color: "#ff3b3b",
    fillColor: "#ff3b3b",
};

const BOT_TILE_RADIUS = 0.65;

const BOT_PIXEL_RADIUS = 7;
const BOT_PIXEL_RADIUS_SELECTED = 9;
const BOT_TILE_RADIUS_SELECTED = BOT_TILE_RADIUS * (BOT_PIXEL_RADIUS_SELECTED / BOT_PIXEL_RADIUS);
const BOT_TILE_MODE_MIN_ZOOM = Math.log2(BOT_PIXEL_RADIUS / BOT_TILE_RADIUS);
const tileRadiusPx = (z: number, selected: boolean) =>
    (selected ? BOT_TILE_RADIUS_SELECTED : BOT_TILE_RADIUS) * Math.pow(2, z);

const BOT_STYLE: L.PathOptions = {
    color: "#0d1117",
    weight: 1,
    fillColor: "#4ea1ff",
    fillOpacity: 0.9,
    renderer: BOT_RENDERER,
};

const BOT_STYLE_OFFLINE: L.PathOptions = {
    color: "#0d1117",
    weight: 1,
    fillColor: "#6e7681",
    fillOpacity: 0.55,
    renderer: BOT_RENDERER,
};

const BOT_STYLE_SELECTED: L.PathOptions = {
    color: "#3a0a2e",
    weight: 2,
    fillColor: "#ff4ec9",
    fillOpacity: 0.95,
    renderer: BOT_RENDERER,
};

/** The outline of a bot in the group. */
export const GROUP_STROKE = "#f0a030";
const withGroupStroke = (s: L.PathOptions): L.PathOptions => ({...s, color: GROUP_STROKE, weight: 3});
const BOT_STYLE_GROUPED = withGroupStroke(BOT_STYLE);
const BOT_STYLE_OFFLINE_GROUPED = withGroupStroke(BOT_STYLE_OFFLINE);
const BOT_STYLE_SELECTED_GROUPED = withGroupStroke(BOT_STYLE_SELECTED);

function botStyle(selected: boolean, online: boolean, grouped = false): L.PathOptions {
    if (grouped) {
        if (selected) return BOT_STYLE_SELECTED_GROUPED;
        return online ? BOT_STYLE_GROUPED : BOT_STYLE_OFFLINE_GROUPED;
    }
    if (selected) return BOT_STYLE_SELECTED;
    return online ? BOT_STYLE : BOT_STYLE_OFFLINE;
}

const NPC_RENDERER = L.svg({pane: "npcsPane", padding: 0.5});
const PLAYER_RENDERER = L.svg({pane: "playersPane", padding: 0.5});

const NPC_PIXEL_RADIUS = 4;
const PLAYER_PIXEL_RADIUS = 5;
const PLAYER_TILE_RADIUS = BOT_TILE_RADIUS * (PLAYER_PIXEL_RADIUS / BOT_PIXEL_RADIUS);
const playerRadiusPx = (z: number) =>
    z >= BOT_TILE_MODE_MIN_ZOOM ? PLAYER_TILE_RADIUS * Math.pow(2, z) : PLAYER_PIXEL_RADIUS;

const NPC_STYLE: L.PathOptions = {
    color: "#0d1117",
    weight: 1,
    fillColor: "#56d364",
    fillOpacity: 0.85,
    renderer: NPC_RENDERER,
};

const NPC_STYLE_COMBAT: L.PathOptions = {
    ...NPC_STYLE,
    fillColor: "#f85149",
};

const PLAYER_STYLE: L.PathOptions = {
    color: "#0d1117",
    weight: 1,
    fillColor: "#ff9e64",
    fillOpacity: 0.9,
    renderer: PLAYER_RENDERER,
};

const OBJECT_RENDERER = L.canvas({pane: "objectsPane", padding: 0.5});
const WALL_OBJECT_RENDERER = L.canvas({pane: "wallObjectsPane", padding: 0.5});
const OBJECT_PIXEL_RADIUS = 3;
const WALL_OBJECT_PIXEL_RADIUS = 3;

const OBJECT_STYLE: L.PathOptions = {
    stroke: false,
    fillColor: "#d2a24c",
    fillOpacity: 0.8,
    renderer: OBJECT_RENDERER,
};

const WALL_OBJECT_STYLE: L.PathOptions = {
    stroke: false,
    fillColor: "#8b98a8",
    fillOpacity: 0.7,
    renderer: WALL_OBJECT_RENDERER,
};

const HIGHLIGHT_RENDERER = L.svg({pane: "botBadgePane", padding: 0.5});
const HIGHLIGHT_STYLE: L.PathOptions = {
    color: "#ffffff",
    weight: 3,
    opacity: 1,
    fill: false,
    renderer: HIGHLIGHT_RENDERER,
};

function tileKey(x: number, zLocal: number): string {
    return `${x},${zLocal}`;
}

function clusterCellTiles(zoom: number): number {
    if (zoom >= BOT_TILE_MODE_MIN_ZOOM) return 1;
    const pxPerTile = Math.pow(2, zoom);
    return Math.max(1, Math.round((2 * BOT_PIXEL_RADIUS) / pxPerTile));
}

function cellKey(x: number, zLocal: number, cell: number): string {
    if (cell <= 1) return tileKey(x, zLocal);
    return `${Math.floor(x / cell)},${Math.floor(zLocal / cell)}`;
}

const EMPTY_ENTITIES: MapEntity[] = [];

const EMPTY_SHOPS: Shop[] = [];

function entitiesByCell(
    entities: MapEntity[],
    floor: number,
    floorHeight: number,
    cell: number,
): Map<string, MapEntity[]> {
    const m = new Map<string, MapEntity[]>();
    for (const e of entities) {
        if (Math.floor(e.z / floorHeight) !== floor) continue;
        const k = cellKey(e.x, e.z - floor * floorHeight, cell);
        const arr = m.get(k);
        if (arr) arr.push(e);
        else m.set(k, [e]);
    }
    return m;
}

/** When a spent object respawns, as a suffix: " now" or " in 12t (~8s)"; empty when unknown. */
export function formatRespawnEta(ticks: number | undefined): string {
    if (ticks == null) return "";
    if (ticks <= 0) return " now";
    return ` in ${ticks}t (~${Math.round(ticks * 0.64)}s)`;
}

const BADGE_ICON_CACHE = new Map<string, L.DivIcon>();

function badgeIcon(count: number, diameterPx: number): L.DivIcon {
    const digits = String(count).length;
    const fontPx = Math.round(Math.max(9, Math.min(diameterPx * 0.9, (diameterPx * 1.5) / digits)));
    const box = Math.max(16, Math.ceil(diameterPx));
    const key = `${count}:${fontPx}:${box}`;
    let icon = BADGE_ICON_CACHE.get(key);
    if (!icon) {
        icon = L.divIcon({
            className: "bot-count-badge",
            html: `<span style="font-size:${fontPx}px">${count}</span>`,
            iconSize: [box, box],
            iconAnchor: [box / 2, box / 2],
        });
        BADGE_ICON_CACHE.set(key, icon);
    }
    return icon;
}

const EMPTY_BADGE_ICON = L.divIcon({className: "bot-count-badge", html: "", iconSize: [0, 0]});

const LUMBRIDGE_INITIAL_BOUNDS: [[number, number], [number, number]] = [
    [580, 1008 - 60],
    [700, 1008 - 180],
];

function UrlViewSync({
    width,
    active,
    sharedView,
}: {
    width: number;
    active: boolean;
    sharedView?: {current: {x: number; z: number; zoom: number} | null};
}) {
    const map = useMap();
    useEffect(() => {
        if (!active) return;
        let timer: ReturnType<typeof setTimeout> | null = null;
        let adopting = false;
        const adopt = setTimeout(() => {
            const sv = sharedView?.current ? {...sharedView.current} : null;
            adopting = true;
            try {
                map.invalidateSize();
                if (sv) {
                    const h = map.getSize().y || 600;
                    const lz = sv.zoom > 0 ? Math.log2(h / sv.zoom) : map.getZoom();
                    map.setView(L.latLng(sv.z, mirrorX(sv.x, width)), lz, {animate: false});
                }
            } finally {
                adopting = false;
            }
        }, 60);
        const write = () => {
            const c = map.getCenter();
            const h = map.getSize().y || 600;
            const x = width - c.lng;
            const z = c.lat;
            const zoom = h / Math.pow(2, map.getZoom());
            if (sharedView) sharedView.current = {x, z, zoom};
            const p = new URLSearchParams(location.search);
            p.set("x", x.toFixed(1));
            p.set("z", z.toFixed(1));
            p.set("zoom", zoom.toFixed(1));
            history.replaceState(null, "", `${location.pathname}?${p}`);
        };
        const onMoveEnd = () => {
            if (adopting) return;
            const c = map.getCenter();
            const h = map.getSize().y || 600;
            if (sharedView)
                sharedView.current = {
                    x: width - c.lng,
                    z: c.lat,
                    zoom: h / Math.pow(2, map.getZoom()),
                };
            if (timer) clearTimeout(timer);
            timer = setTimeout(write, 300);
        };
        map.on("moveend zoomend", onMoveEnd);
        return () => {
            map.off("moveend zoomend", onMoveEnd);
            clearTimeout(adopt);
            if (timer) clearTimeout(timer);
        };
    }, [map, width, active, sharedView]);
    return null;
}

const NO_SHOPS: Shop[] = [];
const NO_GLOBAL_PLAYERS: GlobalPlayer[] = [];

/**
 * The 2D map of the world, one floor at a time: the landscape's layers, the bots, and the NPCs, players and objects
 * they see. Hovering a tile lists what is on it; a click selects the bot, shop or transport there, or pins the list.
 */
export const WorldMap = memo(function WorldMap({
    floors,
    floor,
    layers,
    world,
    active,
    sharedView,
    bots,
    objectRespawns,
    objectDefs,
    selectedBot,
    groupBots,
    shops = NO_SHOPS,
    selectedShop = null,
    onSelectBot,
    onSelectShop = noop,
    followBot,
    followPlayer,
    focusBot,
    panTarget,
    onFloorChange,
    onHover,
    interactive = true,
    globalPlayers = NO_GLOBAL_PLAYERS,
    onPickEntity,
    overlays,
}: WorldMapProps) {
    const fullBounds = useMemo<[[number, number], [number, number]]>(
        () => [
            [0, 0],
            [floors.floorHeight, floors.width],
        ],
        [floors.floorHeight, floors.width],
    );

    const fc = useFloorGeoJson(floor);

    const dangerByTile = useFloorDanger(floor, layers.dangerZones);

    const npcEntities = world.npcs;
    const playerEntities = useMemo(() => {
        const ownIndexes = new Set<number>();
        const ownNames = new Set<string>();
        for (const b of bots) {
            if (b.serverIndex != null) ownIndexes.add(b.serverIndex);
            ownNames.add(b.username.toLowerCase());
        }
        return world.players.filter(
            (p) => !ownIndexes.has(p.serverIndex) && !(p.name && ownNames.has(p.name.toLowerCase())),
        );
    }, [bots, world.players]);
    const followPlayerPos = useMemo(() => {
        if (!followPlayer) return null;
        const lc = followPlayer.toLowerCase();
        const p = playerEntities.find((e) => e.name?.toLowerCase() === lc);
        if (!p) return null;
        const fh = floors.floorHeight;
        return {x: p.x, z: p.z, floor: fh > 0 ? Math.floor(p.z / fh) : 0};
    }, [followPlayer, playerEntities, floors.floorHeight]);
    const objectEntities = useMemo<InspectorObject[]>(() => {
        const base = world.objects.filter((o) => o.id >= 0);
        if (!objectRespawns || objectRespawns.length === 0) return base;
        const byTile = new Map<string, ObjectRespawn>();
        for (const r of objectRespawns) byTile.set(`${r.x},${r.z}`, r);
        return base.map((o) => {
            const r = byTile.get(`${o.x},${o.z}`);
            if (!r) return o;
            return {...o, respawnName: objectDefs?.get(r.id)?.name ?? `#${r.id}`, respawnTicks: r.t};
        });
    }, [world.objects, objectRespawns, objectDefs]);
    const wallEntities = useMemo(() => world.wallObjects.filter((w) => w.id >= 0), [world.wallObjects]);
    const otherGlobalPlayers = useMemo(() => {
        const ownNames = new Set(bots.map((b) => b.username.toLowerCase()));
        return globalPlayers.filter((g) => !g.name || !ownNames.has(g.name.toLowerCase()));
    }, [bots, globalPlayers]);

    return (
        <MapContainer
            crs={NORTH_UP_SIMPLE_CRS}
            minZoom={-4}
            maxZoom={10}
            scrollWheelZoom={false}
            smoothWheelZoom
            smoothSensitivity={1}
            zoomSnap={0}
            zoomDelta={0.5}
            attributionControl={false}
            bounds={LUMBRIDGE_INITIAL_BOUNDS}
            maxBounds={L.latLngBounds(fullBounds).pad(0.2)}
            className="map-container"
        >
            <UrlViewSync width={floors.width} active={active ?? true} sharedView={sharedView} />
            <Pane name="terrainPane" style={{zIndex: 200}} />
            <Pane name="wallsPane" style={{zIndex: 250}} />
            <Pane name="scenerySpritesPane" style={{zIndex: 260}} />
            <Pane name="wallLinesPane" style={{zIndex: 265}} />
            <Pane name="doorsPane" style={{zIndex: 270}} />
            <Pane name="dangerPane" style={{zIndex: 280}} />
            <Pane name="transportsPane" style={{zIndex: 330}} />
            <Pane name="wallObjectsPane" style={{zIndex: 356}} />
            <Pane name="objectsPane" style={{zIndex: 358}} />
            <Pane name="playersPane" style={{zIndex: 390}} />
            <Pane name="shopsPane" style={{zIndex: 430}} />
            <Pane name="botsPane" style={{zIndex: 450}} />
            <Pane name="npcsPane" style={{zIndex: 460}} />
            <Pane name="npcWanderPane" style={{zIndex: 470}} />
            <Pane name="npcSpawnsPane" style={{zIndex: 490}} />
            <Pane name="botBadgePane" style={{zIndex: 640}} />
            <Pane name="selectionMaskPane" style={{zIndex: 645}} />

            {layers.terrain && (
                <ImageOverlay
                    key={`terrain-${floor}`}
                    url={terrainPngUrl(floor)}
                    bounds={fullBounds}
                    pane="terrainPane"
                    opacity={1.0}
                />
            )}
            {layers.blocked && (
                <ImageOverlay
                    key={`walls-fill-${floor}`}
                    url={wallsFillPngUrl(floor)}
                    bounds={fullBounds}
                    pane="wallsPane"
                    opacity={1.0}
                />
            )}
            {layers.walls && (
                <ImageOverlay
                    key={`walls-lines-${floor}`}
                    url={wallsLinesPngUrl(floor)}
                    bounds={fullBounds}
                    pane="wallLinesPane"
                    opacity={1.0}
                />
            )}
            {layers.dangerZones && (
                <ImageOverlay
                    key={`danger-${floor}`}
                    url={dangerPngUrl(floor)}
                    bounds={fullBounds}
                    pane="dangerPane"
                    opacity={1.0}
                />
            )}
            {layers.scenerySprites && (
                <ScenerySpriteLayer floor={floor} width={floors.width} pane="scenerySpritesPane" />
            )}

            <FloorGeoJsonLayers
                fc={fc}
                floor={floor}
                width={floors.width}
                showDoors={layers.doors}
                showTransports={layers.transports}
                showNpcSpawns={layers.npcSpawns}
                showNpcWanderZones={layers.npcWanderZones}
            />

            {layers.wallObjects && (
                <EntityMarkers
                    entities={wallEntities}
                    width={floors.width}
                    floorHeight={floors.floorHeight}
                    floor={floor}
                    pane="wallObjectsPane"
                    radius={WALL_OBJECT_PIXEL_RADIUS}
                    style={WALL_OBJECT_STYLE}
                />
            )}
            {layers.objects && (
                <EntityMarkers
                    entities={objectEntities}
                    width={floors.width}
                    floorHeight={floors.floorHeight}
                    floor={floor}
                    pane="objectsPane"
                    radius={OBJECT_PIXEL_RADIUS}
                    style={OBJECT_STYLE}
                />
            )}
            {layers.npcs && (
                <EntityMarkers
                    entities={npcEntities}
                    width={floors.width}
                    floorHeight={floors.floorHeight}
                    floor={floor}
                    pane="npcsPane"
                    radius={NPC_PIXEL_RADIUS}
                    style={NPC_STYLE}
                    combatStyle={NPC_STYLE_COMBAT}
                />
            )}
            {layers.players && (
                <ScaledPlayerMarkers
                    entities={playerEntities}
                    width={floors.width}
                    floorHeight={floors.floorHeight}
                    floor={floor}
                    pane="playersPane"
                    style={PLAYER_STYLE}
                />
            )}

            {layers.shops && (
                <ShopMarkers
                    shops={shops}
                    selectedShop={selectedShop}
                    width={floors.width}
                    floorHeight={floors.floorHeight}
                    floor={floor}
                />
            )}

            {overlays?.({
                width: floors.width,
                floorHeight: floors.floorHeight,
                floor,
                npcs: npcEntities,
                players: playerEntities,
                objects: objectEntities,
                wallObjects: wallEntities,
                fc,
            })}

            <BotMarkers
                bots={layers.bots ? bots : NO_BOTS}
                selectedBot={selectedBot}
                groupBots={groupBots ?? null}
                width={floors.width}
                floorHeight={floors.floorHeight}
                floor={floor}
                followBot={followBot}
                followPos={followPlayerPos}
                focusBot={focusBot}
                onFloorChange={onFloorChange}
                active={active ?? true}
            />

            <PanToTarget
                target={panTarget}
                width={floors.width}
                floorHeight={floors.floorHeight}
                floor={floor}
                onFloorChange={onFloorChange}
            />

            <HoverProbe onHover={onHover} />
            <TileInspector
                bots={layers.bots ? bots : NO_BOTS}
                npcs={layers.npcs ? npcEntities : EMPTY_ENTITIES}
                players={layers.players ? playerEntities : EMPTY_ENTITIES}
                objects={layers.objects ? objectEntities : EMPTY_ENTITIES}
                wallObjects={layers.wallObjects ? wallEntities : EMPTY_ENTITIES}
                shops={layers.shops ? shops : EMPTY_SHOPS}
                showTransports={layers.transports}
                danger={layers.dangerZones ? dangerByTile : null}
                fc={fc}
                width={floors.width}
                floorHeight={floors.floorHeight}
                floor={floor}
                selectedBot={selectedBot}
                selectedShop={selectedShop}
                onSelectBot={onSelectBot}
                onSelectShop={onSelectShop}
                onFloorChange={onFloorChange}
                selectionEnabled={interactive}
                onPickEntity={onPickEntity}
            />
            <MapSearch
                active={active ?? true}
                width={floors.width}
                floorHeight={floors.floorHeight}
                floor={floor}
                npcs={npcEntities}
                players={playerEntities}
                globalPlayers={otherGlobalPlayers}
                fc={fc}
                onFloorChange={onFloorChange}
            />
        </MapContainer>
    );
});

export function useFloorGeoJson(floor: number): GeoJSON.FeatureCollection | null {
    const [loaded, setLoaded] = useState<{floor: number; fc: GeoJSON.FeatureCollection} | null>(null);
    useEffect(() => {
        if (loadedFloorGeoJson(floor)) return;
        let cancelled = false;
        fetchFloorGeoJson(floor).then(
            (fc) => {
                if (!cancelled) setLoaded({floor, fc});
            },
            () => {
                // Surface as empty until next floor change.
            },
        );
        return () => {
            cancelled = true;
        };
    }, [floor]);
    return loadedFloorGeoJson(floor) ?? (loaded?.floor === floor ? loaded.fc : null);
}

function useFloorDanger(floor: number, enabled: boolean): Map<string, number> | null {
    const [loaded, setLoaded] = useState<{floor: number; byTile: Map<string, number>} | null>(null);
    if (!enabled && loaded) setLoaded(null);
    useEffect(() => {
        if (!enabled) return;
        let cancelled = false;
        fetchFloorDanger(floor).then(
            (d) => {
                if (cancelled) return;
                const m = new Map<string, number>();
                for (let i = 0; i < d.x.length; i++) {
                    m.set(tileKey(d.x[i], d.z[i]), d.v[i]);
                }
                setLoaded({floor, byTile: m});
            },
            () => {
                // Surface as no data until the next floor change / re-toggle.
            },
        );
        return () => {
            cancelled = true;
        };
    }, [floor, enabled]);
    return enabled && loaded?.floor === floor ? loaded.byTile : null;
}

interface FloorGeoJsonLayersProps {
    fc: GeoJSON.FeatureCollection | null;
    floor: number;
    width: number;
    showDoors: boolean;
    showTransports: boolean;
    showNpcSpawns: boolean;
    showNpcWanderZones: boolean;
}

const doorStyle = () => DOOR_STYLE;
const npcSpawnBoxStyle = (f?: GeoJSON.Feature) =>
    (f?.properties as NpcSpawnProperties | undefined)?.aggressive ? NPC_SPAWN_BOX_STYLE_AGGRO : NPC_SPAWN_BOX_STYLE;

const FloorGeoJsonLayers = memo(function FloorGeoJsonLayers({
    fc,
    floor,
    width,
    showDoors,
    showTransports,
    showNpcSpawns,
    showNpcWanderZones,
}: FloorGeoJsonLayersProps) {
    const coordsToLatLng = useMemo(() => (c: [number, number]) => L.latLng(c[1], mirrorX(c[0], width)), [width]);

    const map = useMap();
    const highlightRef = useRef<L.Rectangle | null>(null);
    const clearHighlight = useCallback(() => {
        if (highlightRef.current) {
            map.removeLayer(highlightRef.current);
            highlightRef.current = null;
        }
    }, [map]);
    useEffect(() => clearHighlight, [floor, clearHighlight]);

    if (!fc) return null;

    return (
        <>
            {showDoors && (
                <GeoJSON
                    key={`doors-${floor}`}
                    data={fc}
                    coordsToLatLng={coordsToLatLng}
                    filter={(f) => (f.properties as FeatureProperties).kind === "door"}
                    style={doorStyle}
                />
            )}
            {showTransports && (
                <GeoJSON
                    key={`transports-${floor}`}
                    data={fc}
                    coordsToLatLng={coordsToLatLng}
                    filter={(f) => (f.properties as FeatureProperties).kind === "transport"}
                    pointToLayer={(_f, latlng) => L.circleMarker(latlng, {...TRANSPORT_STYLE, interactive: false})}
                />
            )}
            {showNpcSpawns && (
                <GeoJSON
                    key={`npcspawns-${floor}`}
                    data={fc}
                    coordsToLatLng={coordsToLatLng}
                    filter={(f) =>
                        (f.properties as FeatureProperties).kind === "npcSpawn" && f.geometry.type === "Point"
                    }
                    pointToLayer={(f, latlng) => {
                        const p = f.properties as NpcSpawnProperties;
                        const m = L.circleMarker(latlng, p.aggressive ? NPC_SPAWN_STYLE_AGGRO : NPC_SPAWN_STYLE);
                        m.bindTooltip(npcSpawnTooltip(p), {direction: "top", sticky: true, className: "npc-tooltip"});
                        if (p.box) {
                            const [x0, z0, x1, z1] = p.box;
                            m.on("mouseover", () => {
                                clearHighlight();
                                const bounds = L.latLngBounds(coordsToLatLng([x0, z0]), coordsToLatLng([x1, z1]));
                                highlightRef.current = L.rectangle(
                                    bounds,
                                    p.aggressive ? NPC_WANDER_HIGHLIGHT_AGGRO : NPC_WANDER_HIGHLIGHT,
                                ).addTo(map);
                            });
                            m.on("mouseout", clearHighlight);
                        }
                        return m;
                    }}
                />
            )}
            {showNpcWanderZones && (
                <GeoJSON
                    key={`npcwander-${floor}`}
                    data={fc}
                    coordsToLatLng={coordsToLatLng}
                    filter={(f) =>
                        (f.properties as FeatureProperties).kind === "npcSpawn" && f.geometry.type === "Polygon"
                    }
                    style={npcSpawnBoxStyle}
                />
            )}
        </>
    );
});

function npcSpawnTooltip(p: NpcSpawnProperties): string {
    const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    const rows: string[] = [];
    const row = (label: string, value: string) =>
        rows.push(`<div class="npc-tip-row"><span>${label}</span><span>${value}</span></div>`);
    if (p.combatLevel != null) row("combat", String(p.combatLevel));
    if (p.hits != null) row("hits", String(p.hits));
    if (p.attack != null && (p.attack || p.strength || p.defense)) {
        row("att/str/def", `${p.attack}/${p.strength ?? 0}/${p.defense ?? 0}`);
    }
    row("aggressive", p.aggressive ? "yes" : "no");
    row("attackable", p.attackable ? "yes" : "no");
    if (p.ranged) row("ranged", "yes");
    if (p.members) row("members", "yes");
    if (p.respawnTime != null) row("respawn", `${p.respawnTime} ticks`);
    row("npc id", String(p.id));
    row("server index", String(p.serverIndex));
    const desc = p.description ? `<div class="npc-tip-desc">${esc(p.description)}</div>` : "";
    return `<div class="npc-tip"><div class="npc-tip-name">${esc(p.name)}</div>` + desc + rows.join("") + `</div>`;
}

function jumpToTransportDest(
    map: L.Map,
    props: TransportProperties,
    width: number,
    currentFloor: number,
    onFloorChange: FloorSetter,
) {
    const target = tileCenter(props.dx, props.dz, width);
    const flash = () => {
        map.setView(target, Math.max(map.getZoom(), 2), {animate: true});
        flashTile(map, target, props.name);
    };
    if (props.dfloor !== currentFloor) {
        onFloorChange(props.dfloor, "transport-dest");
        requestAnimationFrame(flash);
    } else {
        flash();
    }
}

let activeFlash: L.CircleMarker | null = null;

function flashTile(map: L.Map, latlng: L.LatLng, label: string) {
    if (activeFlash) {
        map.removeLayer(activeFlash);
        activeFlash = null;
    }
    const flash = L.circleMarker(latlng, {
        radius: 12,
        color: "#ffd166",
        weight: 3,
        fillColor: "#ffd166",
        fillOpacity: 0.4,
    }).addTo(map);
    flash.bindTooltip(`→ ${label}`, {permanent: true, direction: "top"}).openTooltip();
    activeFlash = flash;
    window.setTimeout(() => {
        if (activeFlash === flash) {
            map.removeLayer(flash);
            activeFlash = null;
        }
    }, 2500);
}

interface PanToTargetProps {
    target: {x: number; z: number; floor: number; label: string; nonce: number} | null;
    width: number;
    floorHeight: number;
    floor: number;
    onFloorChange: FloorSetter;
}

function PanToTarget({target, width, floorHeight, floor, onFloorChange}: PanToTargetProps) {
    const map = useMap();
    useEffect(() => {
        if (!target) return;
        if (target.floor !== floor) onFloorChange(target.floor, "pan-target");
        const latlng = tileCenter(target.x, target.z - target.floor * floorHeight, width);
        const go = () => {
            map.setView(latlng, Math.max(map.getZoom(), 1), {animate: true});
            flashTile(map, latlng, target.label);
        };
        if (target.floor !== floor) requestAnimationFrame(go);
        else go();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- fires only on a new pan request (nonce)
    }, [target?.nonce]);
    return null;
}

interface BotMarkersProps {
    bots: BotLive[];
    selectedBot: string | null;
    groupBots: ReadonlySet<string> | null;
    width: number;
    floorHeight: number;
    floor: number;
    followBot: boolean;
    followPos?: {x: number; z: number; floor: number} | null;
    focusBot: number;
    onFloorChange: FloorSetter;
    active: boolean;
}

const NO_BOTS: BotLive[] = [];

const BotMarkers = memo(function BotMarkers({
    bots,
    selectedBot,
    groupBots,
    width,
    floorHeight,
    floor,
    followBot,
    followPos,
    focusBot,
    onFloorChange,
    active,
}: BotMarkersProps) {
    const map = useMap();
    const [zoom, setZoom] = useState(map.getZoom());
    useMapEvents({
        zoom: () => setZoom(map.getZoom()),
        zoomend: () => setZoom(map.getZoom()),
    });
    const tileMode = zoom >= BOT_TILE_MODE_MIN_ZOOM;

    const selected = bots.find((b) => b.username === selectedBot) ?? null;
    const selPos = selected?.position ?? null;
    const selKey = selPos ? `${selPos.x},${selPos.z}` : null;
    const folPos = followPos ?? (followBot ? selPos : null);
    const folKey = folPos ? `${folPos.x},${folPos.z}` : null;

    useEffect(() => {
        if (!folPos) return;
        if (folPos.floor !== floor) onFloorChange(folPos.floor, "follow");
    }, [folKey, folPos, floor, onFloorChange]);

    useEffect(() => {
        if (focusBot === 0 || !selPos) return;
        if (selPos.floor !== floor) onFloorChange(selPos.floor, "focus-on-select");
        if (!active) return;
        const latlng = tileCenter(selPos.x, selPos.z - selPos.floor * floorHeight, width);
        map.setView(latlng, map.getZoom(), {animate: true});
        // eslint-disable-next-line react-hooks/exhaustive-deps -- intentionally fires only on a new selection (focusBot tick)
    }, [focusBot]);

    useEffect(() => {
        if (folPos?.floor !== floor) return;
        const latlng = tileCenter(folPos.x, folPos.z - folPos.floor * floorHeight, width);
        map.panTo(latlng, {animate: true, duration: 0.25});
    }, [folKey, folPos, floor, width, floorHeight, map]);

    const cellTiles = clusterCellTiles(zoom);
    const display = useMemo(() => {
        const cells = new Map<string, BotLive[]>();
        for (const b of bots) {
            const p = b.position;
            if (p?.floor !== floor) continue;
            const k = cellKey(p.x, p.z - p.floor * floorHeight, cellTiles);
            const arr = cells.get(k);
            if (arr) arr.push(b);
            else cells.set(k, [b]);
        }
        const byUser = new Map<
            string,
            {
                cx: number;
                czLocal: number;
                count: number;
                repUser: string;
                hasSelected: boolean;
                anyOnline: boolean;
                anyGrouped: boolean;
            }
        >();
        for (const group of cells.values()) {
            let sx = 0;
            let sz = 0;
            let repUser = group[0].username;
            for (const b of group) {
                const p = b.position;
                if (!p) continue;
                sx += p.x;
                sz += p.z - p.floor * floorHeight;
                if (b.username < repUser) repUser = b.username;
            }
            const hasSelected = group.some((b) => b.username === selectedBot);
            if (hasSelected && selectedBot != null) repUser = selectedBot;
            const info = {
                cx: sx / group.length,
                czLocal: sz / group.length,
                count: group.length,
                repUser,
                hasSelected,
                anyOnline: group.some((b) => b.status === "ONLINE"),
                anyGrouped: groupBots != null && group.some((b) => groupBots.has(b.username)),
            };
            for (const b of group) byUser.set(b.username, info);
        }
        return byUser;
    }, [bots, floor, floorHeight, selectedBot, groupBots, cellTiles]);

    const selectedCircleRef = useRef<L.CircleMarker>(null);
    useEffect(() => {
        selectedCircleRef.current?.bringToFront();
    }, [selectedBot, selKey, zoom, tileMode]);

    return (
        <>
            {bots.map((b) => {
                const d = display.get(b.username);
                if (!d) return null;
                const isRep = d.repUser === b.username;
                const radius = !isRep
                    ? 0
                    : tileMode
                      ? tileRadiusPx(zoom, d.hasSelected)
                      : d.hasSelected
                        ? BOT_PIXEL_RADIUS_SELECTED
                        : BOT_PIXEL_RADIUS;
                return (
                    <CircleMarker
                        key={`b-${b.username}`}
                        ref={b.username === selectedBot ? selectedCircleRef : undefined}
                        center={tileCenter(d.cx, d.czLocal, width)}
                        radius={radius}
                        pathOptions={botStyle(d.hasSelected, d.anyOnline, d.anyGrouped)}
                        interactive={false}
                    />
                );
            })}
            {bots.map((b) => {
                const d = display.get(b.username);
                if (!d) return null;
                const show = d.repUser === b.username && d.count > 1;
                const diameterPx = tileMode
                    ? 2 * tileRadiusPx(zoom, d.hasSelected)
                    : 2 * (d.hasSelected ? BOT_PIXEL_RADIUS_SELECTED : BOT_PIXEL_RADIUS);
                return (
                    <Marker
                        key={`badge-${b.username}`}
                        position={tileCenter(d.cx, d.czLocal, width)}
                        icon={show ? badgeIcon(d.count, diameterPx) : EMPTY_BADGE_ICON}
                        pane="botBadgePane"
                        interactive={false}
                    />
                );
            })}
        </>
    );
});

interface EntityMarkersProps {
    entities: MapEntity[];
    width: number;
    floorHeight: number;
    floor: number;
    pane: string;
    radius: number;
    style: L.PathOptions;
    combatStyle?: L.PathOptions;
}

interface PoolEntry {
    e: MapEntity;
    present: boolean;
}

function usePersistentEntities(entities: MapEntity[], cap: number): PoolEntry[] {
    const [pool, setPool] = useState<{entities: MapEntity[] | null; cap: number; entries: PoolEntry[]}>({
        entities: null,
        cap,
        entries: [],
    });
    if (pool.entities === entities && pool.cap === cap) return pool.entries;
    const entries = nextPool(pool.entries, entities, cap);
    setPool({entities, cap, entries});
    return entries;
}

function nextPool(prev: readonly PoolEntry[], entities: MapEntity[], cap: number): PoolEntry[] {
    const fresh = new Map<number, MapEntity>();
    for (const e of entities) fresh.set(e.serverIndex, e);
    const pool = new Map<number, PoolEntry>();
    for (const old of prev) {
        const e = fresh.get(old.e.serverIndex);
        pool.set(old.e.serverIndex, e ? {e, present: true} : {e: old.e, present: false});
    }
    for (const [k, e] of fresh) {
        if (!pool.has(k)) pool.set(k, {e, present: true});
    }
    if (pool.size > cap) {
        for (const [k, v] of pool) {
            if (pool.size <= cap) break;
            if (!v.present) pool.delete(k);
        }
    }
    return [...pool.values()];
}

const ENTITY_POOL_CAP = 3000;

const ScaledPlayerMarkers = memo(function ScaledPlayerMarkers(props: Omit<EntityMarkersProps, "radius">) {
    const map = useMap();
    const [zoom, setZoom] = useState(map.getZoom());
    useMapEvents({
        zoom: () => setZoom(map.getZoom()),
        zoomend: () => setZoom(map.getZoom()),
    });
    return <EntityMarkers {...props} radius={playerRadiusPx(zoom)} />;
});

const EntityMarkers = memo(function EntityMarkers({
    entities,
    width,
    floorHeight,
    floor,
    pane,
    radius,
    style,
    combatStyle,
}: EntityMarkersProps) {
    const onFloor = useMemo(
        () => entities.filter((e) => Math.floor(e.z / floorHeight) === floor),
        [entities, floorHeight, floor],
    );
    const pooled = usePersistentEntities(onFloor, ENTITY_POOL_CAP);
    const hidden = useMemo<L.PathOptions>(() => ({...style, opacity: 0, fillOpacity: 0}), [style]);
    const shown = useMemo<L.PathOptions>(() => ({opacity: 1, fillOpacity: 1, ...style}), [style]);
    const shownCombat = useMemo<L.PathOptions | undefined>(
        () => (combatStyle ? {opacity: 1, fillOpacity: 1, ...combatStyle} : undefined),
        [combatStyle],
    );
    return (
        <>
            {pooled.map(({e, present}) => (
                <CircleMarker
                    key={e.serverIndex}
                    center={tileCenter(e.x, e.z - floor * floorHeight, width)}
                    radius={radius}
                    pathOptions={present ? (e.inCombat && shownCombat ? shownCombat : shown) : hidden}
                    pane={pane}
                    interactive={false}
                />
            ))}
        </>
    );
});

interface ShopMarkersProps {
    shops: Shop[];
    selectedShop: string | null;
    width: number;
    floorHeight: number;
    floor: number;
}

const SHOP_STYLE: L.PathOptions = {
    color: "#3a2c00",
    weight: 1,
    fillColor: "#ffcc33",
    fillOpacity: 0.95,
};
const SHOP_STYLE_SELECTED: L.PathOptions = {
    color: "#fff",
    weight: 2,
    fillColor: "#ff8c00",
    fillOpacity: 1,
};

const ShopMarkers = memo(function ShopMarkers({shops, selectedShop, width, floorHeight, floor}: ShopMarkersProps) {
    const onFloor = useMemo(
        () => shops.filter((s) => Math.floor(s.z / floorHeight) === floor),
        [shops, floorHeight, floor],
    );
    return (
        <>
            {onFloor.map((s) => {
                const selected = s.id === selectedShop;
                return (
                    <CircleMarker
                        key={s.id}
                        center={tileCenter(s.x, s.z - floor * floorHeight, width)}
                        radius={selected ? 7 : 5}
                        pathOptions={selected ? SHOP_STYLE_SELECTED : SHOP_STYLE}
                        pane="shopsPane"
                        interactive={false}
                    />
                );
            })}
        </>
    );
});

function BotRow({bot}: {bot: BotLive}) {
    const script = bot.scriptClass ? bot.scriptClass.replace(/^.*\./, "") : null;
    const flags = [bot.inCombat ? "combat" : null, bot.sleeping ? "sleeping" : null].filter(Boolean);
    return (
        <div className="ti-bot-col">
            <div className="ti-bot">
                <span className={`bot-status-dot ${bot.status === "ONLINE" ? "on" : "off"}`} />
                <span className="ti-bot-name">{bot.username}</span>
                {bot.fatiguePercent != null && <span className="ti-dim">fat {Math.round(bot.fatiguePercent)}%</span>}
                {script && <span className="ti-dim">{script}</span>}
                {flags.length > 0 && <span className="ti-dim">{flags.join(" · ")}</span>}
            </div>
            {bot.description && <div className="ti-bot-desc">{bot.description}</div>}
        </div>
    );
}

interface TileInspectorProps {
    bots: BotLive[];
    npcs: MapEntity[];
    players: MapEntity[];
    objects: InspectorObject[];
    wallObjects: MapEntity[];
    shops: Shop[];
    showTransports: boolean;
    danger: Map<string, number> | null;
    fc: GeoJSON.FeatureCollection | null;
    width: number;
    floorHeight: number;
    floor: number;
    selectedBot: string | null;
    selectedShop: string | null;
    onSelectBot: (username: string) => void;
    onSelectShop: (id: string) => void;
    onFloorChange: FloorSetter;
    selectionEnabled: boolean;
    onPickEntity?: (pick: PickedEntity) => void;
}

interface PlacedTransport {
    props: TransportProperties;
    latlng: L.LatLng;
}

interface TilePin {
    x: number;
    zLocal: number;
    absZ: number;
    px: number;
    py: number;
    botNames: string[];
    transports: PlacedTransport[];
    npcs: MapEntity[];
    players: MapEntity[];
    objects: InspectorObject[];
    wallObjects: MapEntity[];
    shopIds: string[];
}

function TileInspector({
    bots,
    npcs,
    players,
    objects,
    wallObjects,
    shops,
    showTransports,
    danger,
    fc,
    width,
    floorHeight,
    floor,
    selectedBot,
    selectedShop,
    onSelectBot,
    onSelectShop,
    onFloorChange,
    selectionEnabled,
    onPickEntity,
}: TileInspectorProps) {
    const map = useMap();
    const [hover, setHover] = useState<{x: number; zLocal: number; px: number; py: number} | null>(null);
    const [pinned, setPinned] = useState<TilePin | null>(null);
    const [zoom, setZoom] = useState(map.getZoom());
    const cellTiles = clusterCellTiles(zoom);
    const [highlight, setHighlight] = useState<{
        latlng: L.LatLng;
        kind: "bot" | "transport" | "npc" | "player" | "object" | "wall" | "shop";
        selected: boolean;
    } | null>(null);

    const botsByCell = useMemo(() => {
        const m = new Map<string, BotLive[]>();
        for (const b of bots) {
            const p = b.position;
            if (p?.floor !== floor) continue;
            const k = cellKey(p.x, p.z - p.floor * floorHeight, cellTiles);
            const arr = m.get(k);
            if (arr) arr.push(b);
            else m.set(k, [b]);
        }
        return m;
    }, [bots, floor, floorHeight, cellTiles]);

    const transportsByCell = useMemo(() => {
        const m = new Map<string, PlacedTransport[]>();
        if (fc && showTransports) {
            for (const f of fc.features) {
                const props = f.properties as FeatureProperties | undefined;
                if (props?.kind !== "transport") continue;
                if (f.geometry.type !== "Point") continue;
                const [x, z] = f.geometry.coordinates as [number, number];
                const k = cellKey(Math.floor(x), Math.floor(z), cellTiles);
                const placed: PlacedTransport = {
                    props,
                    latlng: L.latLng(z, mirrorX(x, width)),
                };
                const arr = m.get(k);
                if (arr) arr.push(placed);
                else m.set(k, [placed]);
            }
        }
        return m;
    }, [fc, showTransports, cellTiles, width]);

    const npcsByCell = useMemo(
        () => entitiesByCell(npcs, floor, floorHeight, cellTiles),
        [npcs, floor, floorHeight, cellTiles],
    );
    const playersByCell = useMemo(
        () => entitiesByCell(players, floor, floorHeight, cellTiles),
        [players, floor, floorHeight, cellTiles],
    );
    const objectsByCell = useMemo(
        () => entitiesByCell(objects, floor, floorHeight, cellTiles),
        [objects, floor, floorHeight, cellTiles],
    );
    const wallsByCell = useMemo(
        () => entitiesByCell(wallObjects, floor, floorHeight, cellTiles),
        [wallObjects, floor, floorHeight, cellTiles],
    );
    const shopsByCell = useMemo(() => {
        const m = new Map<string, Shop[]>();
        for (const s of shops) {
            if (Math.floor(s.z / floorHeight) !== floor) continue;
            const k = cellKey(s.x, s.z - floor * floorHeight, cellTiles);
            const arr = m.get(k);
            if (arr) arr.push(s);
            else m.set(k, [s]);
        }
        return m;
    }, [shops, floor, floorHeight, cellTiles]);

    useMapEvents({
        zoom: () => setZoom(map.getZoom()),
        zoomend: () => setZoom(map.getZoom()),
        mousemove: (e) => {
            setHover({
                x: Math.floor(width - e.latlng.lng),
                zLocal: Math.floor(e.latlng.lat),
                px: e.containerPoint.x,
                py: e.containerPoint.y,
            });
        },
        mouseout: () => setHover(null),
        click: (e) => {
            if (!selectionEnabled) return;
            const x = Math.floor(width - e.latlng.lng);
            const zLocal = Math.floor(e.latlng.lat);
            const k = cellKey(x, zLocal, cellTiles);
            const tileBots = botsByCell.get(k) ?? [];
            const tileTransports = transportsByCell.get(k) ?? [];
            const tileNpcs = npcsByCell.get(k) ?? [];
            const tilePlayers = playersByCell.get(k) ?? [];
            const tileObjects = objectsByCell.get(k) ?? [];
            const tileWalls = wallsByCell.get(k) ?? [];
            const tileShops = shopsByCell.get(k) ?? [];
            const actionable = tileBots.length + tileTransports.length + tileShops.length;
            const total = actionable + tileNpcs.length + tilePlayers.length + tileObjects.length + tileWalls.length;
            if (total === 0) {
                setPinned(null);
            } else if (total === 1 && actionable === 1) {
                if (tileBots.length === 1) onSelectBot(tileBots[0].username);
                else if (tileTransports.length === 1) {
                    jumpToTransportDest(map, tileTransports[0].props, width, floor, onFloorChange);
                } else onSelectShop(tileShops[0].id);
                setPinned(null);
            } else {
                setPinned({
                    x,
                    zLocal,
                    absZ: floor * floorHeight + zLocal,
                    px: e.containerPoint.x,
                    py: e.containerPoint.y,
                    botNames: tileBots.map((b) => b.username),
                    transports: tileTransports,
                    npcs: tileNpcs,
                    players: tilePlayers,
                    objects: tileObjects,
                    wallObjects: tileWalls,
                    shopIds: tileShops.map((s) => s.id),
                });
            }
        },
    });

    const size = map.getSize();

    const hoverK = hover && cellKey(hover.x, hover.zLocal, cellTiles);
    const hoverBots = (hoverK ? botsByCell.get(hoverK) : undefined) ?? [];
    const hoverTransports = (hoverK ? transportsByCell.get(hoverK) : undefined) ?? [];
    const hoverNpcs = (hoverK ? npcsByCell.get(hoverK) : undefined) ?? [];
    const hoverPlayers = (hoverK ? playersByCell.get(hoverK) : undefined) ?? [];
    const hoverObjects = (hoverK ? objectsByCell.get(hoverK) : undefined) ?? [];
    const hoverWalls = (hoverK ? wallsByCell.get(hoverK) : undefined) ?? [];
    const hoverShops = (hoverK ? shopsByCell.get(hoverK) : undefined) ?? [];
    const hoverDanger = (hover ? danger?.get(tileKey(hover.x, hover.zLocal)) : undefined) ?? 0;
    const hoverThings =
        hoverBots.length +
        hoverTransports.length +
        hoverNpcs.length +
        hoverPlayers.length +
        hoverObjects.length +
        hoverWalls.length +
        hoverShops.length;
    const hoverClickable = selectionEnabled && hoverThings > 0;

    useEffect(() => {
        const el = map.getContainer();
        el.classList.toggle("tile-clickable", hoverClickable);
        return () => el.classList.remove("tile-clickable");
    }, [map, hoverClickable]);

    const popupStyle = (px: number, py: number) => {
        const flipX = px > size.x - 240;
        return {
            left: flipX ? undefined : px + 14,
            right: flipX ? size.x - px + 14 : undefined,
            top: py + 14,
        };
    };

    const dismiss = () => {
        setPinned(null);
        setHighlight(null);
    };
    const actBot = (username: string) => {
        onSelectBot(username);
        dismiss();
    };
    const actTransport = (t: TransportProperties) => {
        jumpToTransportDest(map, t, width, floor, onFloorChange);
        dismiss();
    };
    const actShop = (id: string) => {
        onSelectShop(id);
        dismiss();
    };
    const actEntity = (pick: PickedEntity) => {
        onPickEntity?.(pick);
        dismiss();
    };
    const ringBot = (b: BotLive) => {
        const p = b.position;
        if (!p) return;
        setHighlight({
            latlng: tileCenter(p.x, p.z - p.floor * floorHeight, width),
            kind: "bot",
            selected: b.username === selectedBot,
        });
    };
    const ringTransport = (t: PlacedTransport) => setHighlight({latlng: t.latlng, kind: "transport", selected: false});
    const ringEntity = (e: MapEntity, kind: "npc" | "player" | "object" | "wall") =>
        setHighlight({
            latlng: tileCenter(e.x, e.z - floor * floorHeight, width),
            kind,
            selected: false,
        });
    const ringShop = (s: Shop) =>
        setHighlight({
            latlng: tileCenter(s.x, s.z - floor * floorHeight, width),
            kind: "shop",
            selected: s.id === selectedShop,
        });

    const tileMode = zoom >= BOT_TILE_MODE_MIN_ZOOM;
    const highlightNode =
        pinned &&
        highlight &&
        (() => {
            const base =
                highlight.kind === "transport"
                    ? (TRANSPORT_STYLE.radius ?? 4)
                    : highlight.kind === "shop"
                      ? highlight.selected
                          ? 7
                          : 5
                      : highlight.kind === "npc"
                        ? NPC_PIXEL_RADIUS
                        : highlight.kind === "player"
                          ? PLAYER_PIXEL_RADIUS
                          : highlight.kind === "object"
                            ? OBJECT_PIXEL_RADIUS
                            : highlight.kind === "wall"
                              ? WALL_OBJECT_PIXEL_RADIUS
                              : tileMode
                                ? tileRadiusPx(zoom, highlight.selected)
                                : highlight.selected
                                  ? BOT_PIXEL_RADIUS_SELECTED
                                  : BOT_PIXEL_RADIUS;
            return (
                <CircleMarker
                    center={highlight.latlng}
                    radius={base + 4}
                    pathOptions={HIGHLIGHT_STYLE}
                    interactive={false}
                />
            );
        })();

    let pinnedBots: BotLive[] = [];
    let pinnedNpcs: MapEntity[] = [];
    let pinnedPlayers: MapEntity[] = [];
    let pinnedObjects: MapEntity[] = [];
    let pinnedWalls: MapEntity[] = [];
    let pinnedShops: Shop[] = [];
    if (pinned) {
        const botByName = new Map(bots.map((b) => [b.username, b]));
        const npcByIndex = new Map(npcs.map((e) => [e.serverIndex, e]));
        const playerByIndex = new Map(players.map((e) => [e.serverIndex, e]));
        const objectByIndex = new Map(objects.map((e) => [e.serverIndex, e]));
        const wallByIndex = new Map(wallObjects.map((e) => [e.serverIndex, e]));
        const shopById = new Map(shops.map((s) => [s.id, s]));
        pinnedBots = pinned.botNames.map((u) => botByName.get(u)).filter((b): b is BotLive => b != null);
        pinnedNpcs = pinned.npcs.map((e) => npcByIndex.get(e.serverIndex) ?? e);
        pinnedPlayers = pinned.players.map((e) => playerByIndex.get(e.serverIndex) ?? e);
        pinnedObjects = pinned.objects.map((e) => objectByIndex.get(e.serverIndex) ?? e);
        pinnedWalls = pinned.wallObjects.map((e) => wallByIndex.get(e.serverIndex) ?? e);
        pinnedShops = pinned.shopIds.map((id) => shopById.get(id)).filter((s): s is Shop => s != null);
    }

    const pinnedNode =
        pinned &&
        createPortal(
            <TilePopup
                pinned
                x={pinned.x}
                absZ={pinned.absZ}
                floor={floor}
                floorHeight={floorHeight}
                danger={danger?.get(tileKey(pinned.x, pinned.zLocal)) ?? 0}
                bots={pinnedBots}
                transports={pinned.transports}
                npcs={pinnedNpcs}
                players={pinnedPlayers}
                objects={pinnedObjects}
                wallObjects={pinnedWalls}
                shops={pinnedShops}
                style={popupStyle(pinned.px, pinned.py)}
                selectedBot={selectedBot}
                selectedShop={selectedShop}
                onPickBot={actBot}
                onPickTransport={actTransport}
                onPickShop={actShop}
                onClose={dismiss}
                onRingBot={ringBot}
                onRingTransport={ringTransport}
                onRingEntity={ringEntity}
                onRingShop={ringShop}
                onRingEnd={() => setHighlight(null)}
                onPickEntity={onPickEntity ? actEntity : undefined}
            />,
            map.getContainer().parentElement ?? map.getContainer(),
        );

    const onPinnedTile = pinned !== null && hoverK !== null && hoverK === cellKey(pinned.x, pinned.zLocal, cellTiles);
    const hoverNode =
        hover &&
        !onPinnedTile &&
        (hoverThings > 0 || hoverDanger > 0) &&
        createPortal(
            <TilePopup
                pinned={false}
                x={hover.x}
                absZ={floor * floorHeight + hover.zLocal}
                floor={floor}
                floorHeight={floorHeight}
                danger={hoverDanger}
                bots={hoverBots}
                transports={hoverTransports}
                npcs={hoverNpcs}
                players={hoverPlayers}
                objects={hoverObjects}
                wallObjects={hoverWalls}
                shops={hoverShops}
                style={popupStyle(hover.px, hover.py)}
                selectedBot={selectedBot}
                selectedShop={selectedShop}
                onPickBot={actBot}
                onPickTransport={actTransport}
                onPickShop={actShop}
                onClose={() => setPinned(null)}
                hint={selectionEnabled && hoverThings > 1}
                onPickEntity={onPickEntity ? actEntity : undefined}
            />,
            map.getContainer(),
        );

    return (
        <>
            {highlightNode}
            {hoverNode}
            {pinnedNode}
        </>
    );
}

/** Where a transport lands, as "(x, z) fN" with z absolute. */
export function transportDest(p: {dx: number; dz: number; dfloor: number}, floorHeight: number): string {
    return `(${p.dx}, ${p.dz + p.dfloor * floorHeight}) f${p.dfloor}`;
}

interface TilePopupProps {
    pinned: boolean;
    x: number;
    absZ: number;
    floor: number;
    floorHeight: number;
    danger?: number;
    bots: BotLive[];
    transports: PlacedTransport[];
    npcs: MapEntity[];
    players: MapEntity[];
    objects: InspectorObject[];
    wallObjects: MapEntity[];
    shops: Shop[];
    style: CSSProperties;
    selectedBot: string | null;
    selectedShop: string | null;
    onPickBot: (username: string) => void;
    onPickTransport: (t: TransportProperties) => void;
    onPickShop: (id: string) => void;
    onClose: () => void;
    onRingBot?: (b: BotLive) => void;
    onRingTransport?: (t: PlacedTransport) => void;
    onRingEntity?: (e: MapEntity, kind: "npc" | "player" | "object" | "wall") => void;
    onRingShop?: (s: Shop) => void;
    onRingEnd?: () => void;
    hint?: boolean;
    onPickEntity?: (pick: PickedEntity) => void;
}

function TilePopup({
    pinned,
    x,
    absZ,
    floor,
    floorHeight,
    danger,
    bots,
    transports,
    npcs,
    players,
    objects,
    wallObjects,
    shops,
    style,
    selectedBot,
    selectedShop,
    onPickBot,
    onPickTransport,
    onPickShop,
    onClose,
    onRingBot,
    onRingTransport,
    onRingEntity,
    onRingShop,
    onRingEnd,
    hint,
    onPickEntity,
}: TilePopupProps) {
    useBotInterest(
        bots.map((b) => b.username),
        "watched",
    );
    return (
        <div className={"tile-inspector" + (pinned ? " tile-inspector-pinned" : "")} style={style}>
            <div className="ti-coords">
                <span>
                    ({x}, {absZ}) · floor {floor}
                </span>
                <button type="button" className="ti-close" title="close" onClick={onClose}>
                    ×
                </button>
            </div>
            {danger != null && danger > 0 && <div className="ti-danger">danger {danger}</div>}
            {bots.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">bots ({bots.length})</div>
                    {bots.map((b) => (
                        <button
                            type="button"
                            key={b.username}
                            className={"ti-pick" + (b.username === selectedBot ? " ti-pick-current" : "")}
                            onClick={() => onPickBot(b.username)}
                            onMouseEnter={() => onRingBot?.(b)}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <BotRow bot={b} />
                        </button>
                    ))}
                </div>
            )}
            {shops.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">shops ({shops.length})</div>
                    {shops.map((s) => (
                        <button
                            type="button"
                            key={s.id}
                            className={"ti-pick ti-shop" + (s.id === selectedShop ? " ti-pick-current" : "")}
                            onClick={() => onPickShop(s.id)}
                            onMouseEnter={() => onRingShop?.(s)}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <span className="ti-shop-name">{s.name}</span>
                            {s.general && <span className="ti-dim">general store</span>}
                            {s.gated && <span className="ti-req">gated</span>}
                        </button>
                    ))}
                </div>
            )}
            {transports.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">transports ({transports.length})</div>
                    {transports.map((t, i) => (
                        <button
                            type="button"
                            key={i}
                            className="ti-pick ti-transport"
                            onClick={() => onPickTransport(t.props)}
                            onMouseEnter={() => onRingTransport?.(t)}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <span className="ti-transport-name">{t.props.name}</span>
                            <span className="ti-dim">→ {transportDest(t.props, floorHeight)}</span>
                            {t.props.req && <span className="ti-req">requires: {t.props.req}</span>}
                        </button>
                    ))}
                </div>
            )}
            {npcs.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">npcs ({npcs.length})</div>
                    {npcs.map((n) => (
                        <button
                            type="button"
                            key={n.serverIndex}
                            className="ti-pick ti-entity ti-entity-npc"
                            onClick={() =>
                                onPickEntity?.({kind: "npc", name: n.name, npcId: n.id, inCombat: n.inCombat})
                            }
                            onMouseEnter={() => onRingEntity?.(n, "npc")}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <span className="ti-entity-name">{n.name ?? `npc #${n.id}`}</span>
                            <span className="ti-dim">
                                #{n.id}
                                {n.inCombat ? " · combat" : ""}
                            </span>
                        </button>
                    ))}
                </div>
            )}
            {players.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">other players ({players.length})</div>
                    {players.map((p) => (
                        <button
                            type="button"
                            key={p.serverIndex}
                            className="ti-pick ti-entity ti-entity-player"
                            onClick={() =>
                                onPickEntity?.({
                                    kind: "player",
                                    name: p.name,
                                    npcId: null,
                                    inCombat: p.inCombat,
                                    appearance: p.appearance,
                                    skulled: p.skulled,
                                })
                            }
                            onMouseEnter={() => onRingEntity?.(p, "player")}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <span className="ti-entity-name">{p.name ?? `player #${p.serverIndex}`}</span>
                            {p.skulled && <span className="ti-req">skulled</span>}
                            {p.inCombat && <span className="ti-dim">combat</span>}
                        </button>
                    ))}
                </div>
            )}
            {objects.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">objects ({objects.length})</div>
                    {objects.map((o) => (
                        <div
                            key={o.serverIndex}
                            className="ti-pick ti-entity ti-entity-object"
                            onMouseEnter={() => onRingEntity?.(o, "object")}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <span className="ti-entity-name">{o.name ?? `object #${o.id}`}</span>
                            <span className="ti-dim">#{o.id}</span>
                            {o.respawnName && (
                                <span className="ti-respawn">
                                    → {o.respawnName}
                                    {formatRespawnEta(o.respawnTicks)}
                                </span>
                            )}
                        </div>
                    ))}
                </div>
            )}
            {wallObjects.length > 0 && (
                <div className="ti-section">
                    <div className="ti-head">walls ({wallObjects.length})</div>
                    {wallObjects.map((w) => (
                        <div
                            key={w.serverIndex}
                            className="ti-pick ti-entity ti-entity-wall"
                            onMouseEnter={() => onRingEntity?.(w, "wall")}
                            onMouseLeave={() => onRingEnd?.()}
                        >
                            <span className="ti-entity-name">{w.name ?? `wall #${w.id}`}</span>
                            <span className="ti-dim">#{w.id}</span>
                        </div>
                    ))}
                </div>
            )}
            {hint && <div className="ti-hint">click to pin · pick one</div>}
        </div>
    );
}

interface HoverProbeProps {
    onHover: (latlng: L.LatLng | null) => void;
}

function HoverProbe({onHover}: HoverProbeProps) {
    useMapEvents({
        mousemove: (e) => onHover(e.latlng),
        mouseout: () => onHover(null),
    });
    return null;
}

export interface SelectedRow {
    layer: string;
    name: string;
    id: number | null;
    serverIndex: number | null;
    x: number;
    z: number;
    extra: string;
}

/** Draws above everything but the badges: what a selection or a search picks out. */
export const SELECTION_MASK_RENDERER = L.svg({pane: "selectionMaskPane", padding: 0.5});

export const SELECTION_HOVER_RING: L.PathOptions = {
    color: "#ffffff",
    weight: 3,
    opacity: 1,
    fill: false,
    interactive: false,
    renderer: SELECTION_MASK_RENDERER,
};

interface SelectionPanelProps {
    rows: SelectedRow[];
    floor: number;
    /** Shown above the rows. */
    head?: ReactNode;
    offFloor?: boolean;
    onFocus: (row: SelectedRow) => void;
    onHoverRow: (row: SelectedRow | null) => void;
    onClose: () => void;
    title?: string;
    foot?: string;
    windowId?: string;
    anchor?: WindowAnchor;
}

/** A list of things on the map, filtered by layer and searched by exact name, id or index; a click focuses one. */
export function SelectionPanel({
    rows,
    floor,
    head,
    offFloor = false,
    onFocus,
    onHoverRow,
    onClose,
    title = "selection",
    foot,
    windowId = "selection",
    anchor = TOP_RIGHT,
}: SelectionPanelProps) {
    const [q, setQ] = useState("");
    const [layerFilter, setLayerFilter] = useState<string | null>(null);
    const [copied, setCopied] = useState(false);

    const layerCounts = useMemo(() => {
        const m = new Map<string, number>();
        for (const r of rows) m.set(r.layer, (m.get(r.layer) ?? 0) + 1);
        return [...m.entries()];
    }, [rows]);

    const filtered = useMemo(() => {
        const needle = q.trim().toLowerCase();
        return rows.filter(
            (r) =>
                (layerFilter == null || r.layer === layerFilter) &&
                (needle === "" ||
                    r.name.toLowerCase() === needle ||
                    (r.id != null && String(r.id) === needle) ||
                    (r.serverIndex != null && String(r.serverIndex) === needle)),
        );
    }, [rows, q, layerFilter]);

    const copy = () => {
        const header = ["name", "x", "z", "serverIndex", "id", "layer", "info"].join("\t");
        const body = filtered
            .map((r) => [r.name, r.x, r.z, r.serverIndex ?? "", r.id ?? "", r.layer, r.extra].join("\t"))
            .join("\n");
        const text = `${header}\n${body}`;
        const done = () => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1200);
        };
        const cb = clipboard();
        if (cb) {
            cb.writeText(text).then(done, noop);
        }
    };

    const showLayer = layerCounts.length > 1 && layerFilter == null;

    return (
        <FloatingWindow
            id={windowId}
            title={`${title} · ${rows.length}`}
            className="area-panel"
            resizable
            anchor={anchor}
            onClose={onClose}
        >
            {head}
            <div className="area-panel-chips">
                <button
                    type="button"
                    className={"area-chip" + (layerFilter == null ? " active" : "")}
                    onClick={() => setLayerFilter(null)}
                >
                    all ({rows.length})
                </button>
                {layerCounts.map(([layer, n]) => (
                    <button
                        type="button"
                        key={layer}
                        className={"area-chip" + (layerFilter === layer ? " active" : "")}
                        onClick={() => setLayerFilter(layer === layerFilter ? null : layer)}
                    >
                        {layer} ({n})
                    </button>
                ))}
            </div>
            <div className="area-panel-controls">
                <input
                    className="area-panel-search"
                    type="search"
                    placeholder="exact name / id / index…"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    autoFocus
                />
                <button type="button" className="area-copy" onClick={copy} disabled={filtered.length === 0}>
                    {copied ? "copied!" : `copy ${filtered.length}`}
                </button>
            </div>
            <div className="area-table-wrap">
                {filtered.length === 0 ? (
                    <div className="hint">{rows.length === 0 ? "nothing in the selection" : "no matches"}</div>
                ) : (
                    <table className="area-table">
                        <thead>
                            <tr>
                                <th>name</th>
                                <th>x</th>
                                <th>z</th>
                                <th>idx</th>
                                <th>id</th>
                                {showLayer && <th>layer</th>}
                            </tr>
                        </thead>
                        <tbody>
                            {filtered.map((r, i) => (
                                <tr
                                    key={i}
                                    className="area-row"
                                    title={r.extra ? `${r.extra} · click to focus` : "click to focus"}
                                    onClick={() => onFocus(r)}
                                    onMouseEnter={() => onHoverRow(r)}
                                    onMouseLeave={() => onHoverRow(null)}
                                >
                                    <td className="area-name">{r.name}</td>
                                    <td>{r.x}</td>
                                    <td>{r.z}</td>
                                    <td>{r.serverIndex ?? ""}</td>
                                    <td>{r.id ?? ""}</td>
                                    {showLayer && <td className="area-dim">{r.layer}</td>}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
            <div className="area-panel-foot">
                floor {floor} · z is absolute
                {offFloor ? " · not on this floor" : foot ? ` · ${foot}` : ""}
            </div>
        </FloatingWindow>
    );
}

const TOP_RIGHT: WindowAnchor = {top: 8, right: 8};
export const UNDER_SEARCH: WindowAnchor = {top: 84, left: 8};

export type SearchMode = "npcIndex" | "npcId" | "player";

interface SearchModeDef {
    mode: SearchMode;
    label: string;
    placeholder: string;
    numeric: boolean;
}

export const SEARCH_MODES: SearchModeDef[] = [
    {mode: "npcIndex", label: "npc index", placeholder: "server index…", numeric: true},
    {mode: "npcId", label: "npc id", placeholder: "npc id…", numeric: true},
    {mode: "player", label: "player", placeholder: "player name…", numeric: false},
];

export function searchNpcs(
    mode: SearchMode,
    value: number,
    ctx: {npcs: MapEntity[]; fc: GeoJSON.FeatureCollection | null; floor: number; floorHeight: number},
): SelectedRow[] {
    const {npcs, fc, floor, floorHeight} = ctx;
    if (!Number.isFinite(value)) return [];
    const rows: SelectedRow[] = [];
    const seenIndex = new Set<number>();
    for (const e of npcs) {
        const hit = mode === "npcIndex" ? e.serverIndex === value : e.id === value;
        if (!hit) continue;
        seenIndex.add(e.serverIndex);
        const f = Math.floor(e.z / floorHeight);
        rows.push({
            layer: "npc",
            name: e.name ?? `npc #${e.id}`,
            id: e.id,
            serverIndex: e.serverIndex,
            x: e.x,
            z: e.z,
            extra: [`floor ${f}`, e.inCombat ? "combat" : ""].filter(Boolean).join(" · "),
        });
    }
    if (fc) {
        for (const feat of fc.features) {
            const p = feat.properties as FeatureProperties | undefined;
            if (p?.kind !== "npcSpawn" || feat.geometry.type !== "Point") continue;
            const np = p;
            const hit = mode === "npcIndex" ? np.serverIndex === value : np.id === value;
            if (!hit) continue;
            if (seenIndex.has(np.serverIndex)) continue;
            const [cx, cz] = feat.geometry.coordinates as [number, number];
            rows.push({
                layer: "npc spawn",
                name: np.name,
                id: np.id,
                serverIndex: np.serverIndex,
                x: Math.floor(cx),
                z: floor * floorHeight + Math.floor(cz),
                extra: [np.combatLevel != null ? `lvl ${np.combatLevel}` : "", np.aggressive ? "aggressive" : ""]
                    .filter(Boolean)
                    .join(" · "),
            });
        }
    }
    return rows;
}

export function searchPlayers(
    value: string,
    ctx: {players: MapEntity[]; globalPlayers: GlobalPlayer[]; floorHeight: number},
): SelectedRow[] {
    const {players, globalPlayers, floorHeight} = ctx;
    const needle = value.toLowerCase();
    const asIndex = /^\d+$/.test(needle) ? Number(needle) : null;
    const rows: SelectedRow[] = [];
    const seenNames = new Set<string>();
    for (const e of players) {
        const nameHit = e.name?.toLowerCase().includes(needle);
        if (!nameHit && !(asIndex != null && e.serverIndex === asIndex)) continue;
        if (e.name) seenNames.add(e.name.toLowerCase());
        const f = Math.floor(e.z / floorHeight);
        rows.push({
            layer: "player",
            name: e.name ?? `player #${e.serverIndex}`,
            id: null,
            serverIndex: e.serverIndex,
            x: e.x,
            z: e.z,
            extra: [`floor ${f}`, e.inCombat ? "combat" : ""].filter(Boolean).join(" · "),
        });
    }
    for (const g of globalPlayers) {
        if (!g.name?.toLowerCase().includes(needle)) continue;
        if (seenNames.has(g.name.toLowerCase())) continue;
        rows.push({
            layer: "global player",
            name: g.name,
            id: null,
            serverIndex: null,
            x: g.x,
            z: g.z,
            extra: "live server",
        });
    }
    return rows;
}

interface MapSearchProps {
    width: number;
    floorHeight: number;
    floor: number;
    npcs: MapEntity[];
    players: MapEntity[];
    globalPlayers: GlobalPlayer[];
    fc: GeoJSON.FeatureCollection | null;
    onFloorChange: FloorSetter;
    active: boolean;
}

function MapSearch({
    active,
    width,
    floorHeight,
    floor,
    npcs,
    players,
    globalPlayers,
    fc,
    onFloorChange,
}: MapSearchProps) {
    const map = useMap();
    const [mode, setMode] = useState<SearchMode>("npcIndex");
    const [text, setText] = useState("");
    const [query, setQuery] = useState<{mode: SearchMode; value: string} | null>(null);
    const [hoverRow, setHoverRow] = useState<SelectedRow | null>(null);

    const rows = useMemo(() => {
        if (!query) return null;
        return query.mode === "player"
            ? searchPlayers(query.value, {players, globalPlayers, floorHeight})
            : searchNpcs(query.mode, Number(query.value), {npcs, fc, floor, floorHeight});
    }, [query, npcs, players, globalPlayers, fc, floor, floorHeight]);

    const modeDef = SEARCH_MODES.find((m) => m.mode === mode);
    const submit = (e: SubmitEvent) => {
        e.preventDefault();
        const value = text.trim();
        if (value === "" || (modeDef?.numeric && !Number.isFinite(Number(value)))) {
            setQuery(null);
            return;
        }
        setQuery({mode, value});
    };

    const placeholder = modeDef?.placeholder ?? "search…";
    const parent = map.getContainer().parentElement ?? map.getContainer();

    return (
        <>
            {hoverRow && Math.floor(hoverRow.z / floorHeight) === floor && (
                <CircleMarker
                    center={tileCenter(hoverRow.x, hoverRow.z - floor * floorHeight, width)}
                    radius={10}
                    pathOptions={SELECTION_HOVER_RING}
                    interactive={false}
                />
            )}
            {createPortal(
                <div className="map-search">
                    <div className="map-search-modes">
                        {SEARCH_MODES.map((m) => (
                            <button
                                type="button"
                                key={m.mode}
                                className={"area-chip" + (mode === m.mode ? " active" : "")}
                                onClick={() => setMode(m.mode)}
                            >
                                {m.label}
                            </button>
                        ))}
                    </div>
                    <form className="map-search-row" onSubmit={submit}>
                        <input
                            className="area-panel-search"
                            type="search"
                            inputMode={modeDef?.numeric ? "numeric" : "text"}
                            placeholder={placeholder}
                            value={text}
                            onChange={(e) => setText(e.target.value)}
                        />
                        <button className="area-copy" type="submit">
                            search
                        </button>
                    </form>
                </div>,
                parent,
            )}
            {active &&
                rows &&
                createPortal(
                    <SelectionPanel
                        rows={rows}
                        floor={floor}
                        title="matches"
                        foot="search to refine"
                        windowId="search"
                        anchor={UNDER_SEARCH}
                        onFocus={(r) => {
                            const f = Math.floor(r.z / floorHeight);
                            const latlng = tileCenter(r.x, r.z - f * floorHeight, width);
                            const go = () => {
                                map.setView(latlng, Math.max(map.getZoom(), 3), {animate: true});
                                flashTile(map, latlng, r.name);
                            };
                            if (f !== floor) {
                                onFloorChange(f, "search-result");
                                requestAnimationFrame(go);
                            } else {
                                go();
                            }
                        }}
                        onHoverRow={setHoverRow}
                        onClose={() => {
                            setQuery(null);
                            setHoverRow(null);
                        }}
                    />,
                    parent,
                )}
        </>
    );
}

interface CoordReadoutProps {
    hover: L.LatLng | null;
    width: number;
    floorHeight: number;
    floor: number;
}

/** The tile under the cursor: x and absolute z, the floor, and z within the floor. */
export function CoordReadout({hover, width, floorHeight, floor}: CoordReadoutProps) {
    if (!hover) {
        return <div className="coord-readout">—</div>;
    }
    const x = Math.floor(width - hover.lng);
    const z = Math.floor(hover.lat);
    const absZ = floor * floorHeight + z;

    return (
        <div className="coord-readout">
            ({x}, {absZ}) floor {floor} · local z={z}
        </div>
    );
}
