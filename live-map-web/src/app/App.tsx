import {useCallback, useEffect, useMemo, useRef, useState} from "react";
import type L from "leaflet";
import {CircleMarker} from "react-leaflet";
import {
    configureViewerHost,
    EntityCard,
    enginePlanPoints,
    fetchWorldMeshManifest,
    floorIndexForKey,
    httpPlannerHost,
    overlay,
    type PickedEntity,
    type PlannerBot,
    PlannerPanel,
    type PlannerCopySource,
    type PlannerSessionBot,
    routeEndsOverlay,
    type RoutePoint,
    stateFromAccount,
    useDefaultPolicy,
    usePlannerPreview,
    usePlannerSession,
    type UsedItem,
    World3DView,
    type World3DHandle,
} from "@world3d";
import {
    type BotPanelState,
    fetchFloors,
    type ServerMessage,
    fetchItemDefs,
    type FloorsResponse,
    type ItemDefs,
    type ItemRow,
} from "../api";
import {useBotInterests} from "../botInterest";
import {BotWindows} from "../BotWindows";
import {MenuDock, UseItemDock} from "../BotDocks";
import {ChatLog} from "../ChatLog";
import {BotPanel, type InventoryControl} from "../BotPanel";
import {tileCenter} from "../coords";
import {errorMessage, noop} from "../errors";
import {type FloorSurface, useFloorController} from "../floor";
import {ItemPanel} from "../ItemPanel";
import {ItemsView} from "../ItemsView";
import {RouteLine} from "../RouteLine";
import {
    BakeWarning,
    ConnectionStatus,
    describeBot,
    FloorPicker,
    type LayerRow,
    LayerList,
    MAP_LAYER_ROWS,
    World3DOptions,
    type World3DOptionValues,
} from "../SidebarParts";
import {TileClickProbe} from "../TileClickProbe";
import {topics, useTopicList, useTopicObject} from "../topics";
import {useLiveStream} from "../useLiveStream";
import {useChatFeed} from "./useChatFeed";
import {useObjectDefs} from "../useObjectDefs";
import {world3dTransports} from "../world3d";
import {CoordReadout, type MapLayers, type TilePoint, useFloorGeoJson, WorldMap} from "../WorldMap";
import {
    actBot,
    bankMove,
    chatBot,
    type ControlState,
    duelMove,
    fetchFeatures,
    openClient,
    pauseClients,
    type ProxyFeatures,
    stopWalk,
    tradeMove,
    walkBot,
} from "./control";

// The walk's own route: the path being walked solid, the engine's route dotted.
const WALK_PATH_STYLE: L.PolylineOptions = {color: "#4da3ff", weight: 3.5, opacity: 0.95};
const WALK_PLAN_STYLE: L.PolylineOptions = {color: "#ff6ad5", weight: 2.5, opacity: 0.85, dashArray: "2 6"};
const PLANNED_STYLE: L.PolylineOptions = {color: "#4ec9ff", weight: 3, opacity: 0.9};

const NO_ENDS = {start: null, end: null};

const NO_ITEMS: {id: number; amount: number}[] = [];
const NO_SERVER_LINES: ServerMessage[] = [];

const END_STYLE: Record<"start" | "end", L.PathOptions> = {
    start: {color: "#3fb950", fillColor: "#3fb950", fillOpacity: 0.9, weight: 2},
    end: {color: "#f85149", fillColor: "#f85149", fillOpacity: 0.9, weight: 2},
};

/** The planner's start and end on the 2D map's floor; its 3D view draws its own. */
function RouteEndMarkers(props: {
    ends: {start: {x: number; z: number} | null; end: {x: number; z: number} | null};
    width: number;
    floorHeight: number;
    floor: number;
}) {
    const {ends, width, floorHeight, floor} = props;
    return (
        <>
            {(["start", "end"] as const).map((which) => {
                const t = ends[which];
                if (!t || Math.floor(t.z / floorHeight) !== floor) return null;
                return (
                    <CircleMarker
                        key={which}
                        center={tileCenter(t.x, t.z - floor * floorHeight, width)}
                        radius={6}
                        pathOptions={END_STYLE[which]}
                        interactive={false}
                    />
                );
            })}
        </>
    );
}

function routePoints(steps: {x: number; z: number; type?: string}[]): RoutePoint[] {
    return steps.map((s) => ({x: s.x, z: s.z, hop: s.type === "hop"}));
}

const VIEWS = ["world", "map", "items"] as const;
type View = (typeof VIEWS)[number];

const VIEW_LABEL: Record<View, string> = {world: "Map 3D", map: "Map 2D", items: "Items"};

interface Layers extends MapLayers {
    wilderness: boolean;
}

const DEFAULT_LAYERS: Layers = {
    terrain: true,
    walls: true,
    blocked: true,
    doors: true,
    transports: true,
    bots: true,
    npcs: true,
    npcSpawns: false,
    npcWanderZones: false,
    dangerZones: false,
    players: true,
    wilderness: true,
    shops: false,
    objects: false,
    wallObjects: false,
    scenerySprites: false,
};

// The proxy knows no shops: their row would toggle nothing.
const LAYER_ROWS: LayerRow<keyof Layers>[] = [
    ...MAP_LAYER_ROWS.filter((r) => r.key !== "shops"),
    {key: "wilderness", label: "wilderness levels", on3d: true},
];

function initialView(): View {
    const t = new URLSearchParams(location.search).get("tab");
    return (VIEWS as readonly string[]).includes(t ?? "") ? (t as View) : "world";
}

function initialSharedView(): {x: number; z: number; zoom: number} | null {
    const q = new URLSearchParams(location.search);
    const x = parseFloat(q.get("x") ?? "");
    const z = parseFloat(q.get("z") ?? "");
    if (!Number.isFinite(x) || !Number.isFinite(z)) return null;
    const zoom = parseFloat(q.get("zoom") ?? "");
    return {x, z, zoom: Number.isFinite(zoom) && zoom > 0 ? zoom : 60};
}

/** The session proxy's live map: every bot it proxies, on the 2D and 3D world, with its panel and the items tab. */
export default function App() {
    const [floors, setFloors] = useState<FloorsResponse | null>(null);
    const [floorsError, setFloorsError] = useState<string | null>(null);
    useEffect(() => {
        fetchFloors().then(setFloors, (e: unknown) => setFloorsError(errorMessage(e)));
    }, []);
    const [itemDefs, setItemDefs] = useState<ItemDefs>({});
    useEffect(() => {
        fetchItemDefs().then(setItemDefs, noop);
    }, []);
    const objectDefs = useObjectDefs();

    const [view, setView] = useState<View>(initialView);
    // The 3D world is baked from the client's cache: without one, the 2D map is the one to open on.
    const [tabAsked] = useState(() => new URLSearchParams(location.search).has("tab"));
    const [has3d, setHas3d] = useState<boolean | null>(null);
    useEffect(() => {
        fetchWorldMeshManifest().then(
            () => setHas3d(true),
            () => {
                setHas3d(false);
                if (!tabAsked) setView((v) => (v === "world" ? "map" : v));
            },
        );
    }, [tabAsked]);
    useEffect(() => {
        const p = new URLSearchParams(location.search);
        if (p.get("tab") === view) return;
        p.set("tab", view);
        history.replaceState(null, "", `${location.pathname}?${p}`);
    }, [view]);
    const [worldMounted, setWorldMounted] = useState(view === "world");
    if (view === "world" && !worldMounted) setWorldMounted(true);
    const surface: FloorSurface | null = view === "map" ? "map" : view === "world" ? "world" : null;
    const floorCtl = useFloorController(surface, floorIndexForKey(new URLSearchParams(location.search).get("floor")));
    const floor = floorCtl.floor;
    const sharedViewRef = useRef(initialSharedView());

    const [layers, setLayers] = useState<Layers>(DEFAULT_LAYERS);
    const [w3dOptions, setW3dOptions] = useState<World3DOptionValues>(() => {
        const q = new URLSearchParams(location.search);
        return {
            roofs: q.get("roofs") === "1",
            sight: q.get("sight") !== "0",
            tags: q.get("tags") !== "0",
            clusters: q.get("clusters") === "1",
            components: q.get("components") === "1",
            below: q.get("below") === "1",
        };
    });
    const [flying, setFlying] = useState(false);
    const w3dRef = useRef<World3DHandle>(null);

    const chatFeed = useChatFeed();
    const {bots, connection, world, watch} = useLiveStream(undefined, {
        onEvent: chatFeed.onEvent,
        onDisconnect: chatFeed.onDisconnect,
    });
    const botList = useMemo(() => Object.values(bots).sort((a, b) => a.username.localeCompare(b.username)), [bots]);
    const observers = useMemo(() => botList.map((b) => (b.status === "ONLINE" ? b : {...b, offline: true})), [botList]);

    const [selectedBot, setSelectedBot] = useState<string | null>(null);
    const [inspectorOpen, setInspectorOpen] = useState(true);
    const [focusTick, setFocusTick] = useState(0);
    const [followBot, setFollowBot] = useState(false);
    const [hover, setHover] = useState<L.LatLng | null>(null);
    const [picked, setPicked] = useState<PickedEntity | null>(null);
    const selectBot = useCallback((username: string | null) => {
        setSelectedBot(username);
        if (username) {
            setFocusTick((n) => n + 1);
            setInspectorOpen(true);
        }
    }, []);
    const pickBot = useCallback(
        (username: string) => {
            selectBot(username);
            setView((v) => (v === "items" ? (has3d === false ? "map" : "world") : v));
        },
        [selectBot, has3d],
    );

    const interest = useBotInterests();
    useEffect(() => {
        if (connection !== "live") return;
        watch(selectedBot, interest);
    }, [connection, selectedBot, interest, watch]);

    const [plannerOpen, setPlannerOpen] = useState(false);
    const selected = selectedBot ? (bots[selectedBot] ?? null) : null;

    const [features, setFeatures] = useState<ProxyFeatures | null>(null);
    useEffect(() => {
        fetchFeatures().then(setFeatures, noop);
    }, []);
    const canPlay = selected?.status === "ONLINE";
    const control = useTopicObject<ControlState>("control", canPlay && selectedBot ? {user: selectedBot} : null).data;
    const windowOpen = selected?.windows != null;
    // The panel flows while something shows it: the inspector, the planner, or a bank, trade or duel window.
    const panel = useTopicObject<BotPanelState>(
        "bot",
        selectedBot && ((inspectorOpen && view !== "items") || plannerOpen || windowOpen) ? {user: selectedBot} : null,
    );
    const [failed, setFailed] = useState<{bot: string; error: string} | null>(null);
    const playError = failed?.bot === selectedBot ? failed.error : null;
    // Plays the selected bot, showing a failure; the promise rejects with it.
    const attempt = useCallback(
        (act: (user: string) => Promise<void>): Promise<void> => {
            if (!selectedBot) return Promise.reject(new Error("no bot is selected"));
            const bot = selectedBot;
            setFailed(null);
            return act(bot).catch((e: unknown) => {
                setFailed({bot, error: errorMessage(e)});
                throw e;
            });
        },
        [selectedBot],
    );
    const playing = useCallback(
        (act: (user: string) => Promise<void>) => {
            attempt(act).catch(() => undefined);
        },
        [attempt],
    );

    const [defaultPolicy, setDefaultPolicy] = useDefaultPolicy();
    // The view's menu acts under the page's default routing, keeping whatever the bot carries.
    useEffect(() => {
        const shown = (user: string, act: Promise<void>) => {
            setFailed(null);
            return act.catch((e: unknown) => setFailed({bot: user, error: errorMessage(e)}));
        };
        configureViewerHost({
            sendWalk: (user, tile) => shown(user, walkBot(user, tile, defaultPolicy, null)),
            sendInteract: (user, action, x, z, id, dir, item, noted) =>
                shown(user, actBot(user, action, {x, z, id, dir, item, noted}, defaultPolicy)),
        });
    }, [defaultPolicy]);
    const [useItem, setUseItem] = useState<UsedItem | null>(null);
    const [useItemFor, setUseItemFor] = useState(selectedBot);
    if (useItemFor !== selectedBot) {
        setUseItemFor(selectedBot);
        setUseItem(null);
    }
    const inventoryControl = useMemo<InventoryControl | undefined>(
        () =>
            canPlay
                ? {
                      useItem,
                      onUseItemChange: setUseItem,
                      act: (action, item, other = 0) =>
                          playing((u) =>
                              actBot(u, action, {x: 0, z: 0, id: other, item: item.id, noted: item.noted}, null),
                          ),
                  }
                : undefined,
        [canPlay, useItem, playing],
    );
    const botX = selected?.position?.x;
    const botZ = selected?.position?.z;
    const botTile = useMemo(() => (botX != null && botZ != null ? {x: botX, z: botZ} : null), [botX, botZ]);
    const walking = control?.walkingTo != null;
    const sessionBot = useMemo<PlannerSessionBot | null>(
        () => (canPlay && selectedBot ? {name: selectedBot, tile: botTile, walking} : null),
        [canPlay, selectedBot, botTile, walking],
    );
    // Opened once, the planner keeps its editor across closing and opening the panel.
    const [plannerOpened, setPlannerOpened] = useState(false);
    if (plannerOpen && !plannerOpened) setPlannerOpened(true);
    const planner = usePlannerSession(plannerOpened ? httpPlannerHost : null, sessionBot, defaultPolicy, plannerOpen);
    const [hover3d, setHover3d] = useState<TilePoint | null>(null);
    let hoverKey = "";
    if (view === "world" && hover3d) {
        hoverKey = `${hover3d.x},${hover3d.z}`;
    } else if (view === "map" && hover && floors) {
        hoverKey = `${Math.floor(floors.width - hover.lng)},${floor * floors.floorHeight + Math.floor(hover.lat)}`;
    }
    const plannerHover = useMemo<TilePoint | null>(() => {
        if (!hoverKey) return null;
        const [x, z] = hoverKey.split(",").map(Number);
        return {x, z};
    }, [hoverKey]);
    const preview = usePlannerPreview({
        host: plannerOpen ? httpPlannerHost : null,
        pick: plannerOpen ? planner.pick : null,
        start: planner.start,
        end: planner.end,
        state: planner.state,
        inputs: planner.inputs,
        as: planner.as,
        hovered: plannerHover,
        plan: planner.plan,
    });
    // Ending a preview drops its 3D hover, since the view reports only the tiles the cursor enters.
    if (!preview.previewing && hover3d) setHover3d(null);
    const plannerPicking = plannerOpen && planner.pick != null;
    const controlWalkingTo = control?.walkingTo ?? null;
    const controlWalkNote = control?.walkNote ?? null;
    const botPack = panel.data?.inventory ?? NO_ITEMS;
    const botBank = panel.data?.bankSeen ? panel.data.bank : null;
    const plannerBot = useMemo<PlannerBot | null>(
        () =>
            sessionBot && {
                name: sessionBot.name,
                tile: sessionBot.tile,
                walkingTo: controlWalkingTo,
                walkNote: controlWalkNote,
                error: playError,
                pack: botPack,
                bank: botBank,
                walk: (to, policy, loadout) => playing((u) => walkBot(u, to, policy, loadout)),
                stop: () => playing(stopWalk),
            },
        [sessionBot, controlWalkingTo, controlWalkNote, playError, botPack, botBank, playing],
    );
    const copySources = useMemo<PlannerCopySource[]>(
        () => (selectedBot && panel.data ? [{value: selectedBot, label: selectedBot}] : []),
        [selectedBot, panel.data],
    );
    const {setState: setPlannerState, inputs: plannerInputs, copyFrom: plannerCopyFrom} = planner;
    const copyFrom = useCallback(
        (choice: string) => {
            const account = panel.data;
            if (choice === selectedBot && account) {
                setPlannerState((s) => stateFromAccount(plannerInputs, account, s));
            } else {
                plannerCopyFrom(choice);
            }
        },
        [panel.data, selectedBot, setPlannerState, plannerInputs, plannerCopyFrom],
    );

    const shown = plannerOpen && preview.shown?.found ? preview.shown : null;
    const plannedRoute = useMemo(() => (shown ? routePoints(shown.steps) : null), [shown]);
    const plannedPlan = useMemo(
        () => (shown ? enginePlanPoints(preview.shownFrom, shown.plan) : null),
        [shown, preview.shownFrom],
    );
    const walkRoute = control?.walkRoute ?? null;
    const walkPath = useMemo(() => (walkRoute ? routePoints(walkRoute.path) : null), [walkRoute]);
    const walkPlan = useMemo(() => (walkRoute ? enginePlanPoints(walkRoute.from, walkRoute.plan) : null), [walkRoute]);
    // One route at a time: the bot's walk, unless the planner is open on an account of its own making.
    const showsWalk = walkRoute != null && (!plannerOpen || planner.as != null);
    const shownRoute = showsWalk ? walkPath : plannerOpen ? plannedRoute : null;
    const shownPlan = showsWalk ? walkPlan : plannerOpen ? plannedPlan : null;
    const routeEnds = useMemo(() => (floors ? routeEndsOverlay(floors.floorHeight) : null), [floors]);
    // Planning as the bot, the bot itself marks the start.
    const routeEndsData = useMemo(
        () => (plannerOpen ? {start: planner.as ? null : preview.from, end: preview.to} : NO_ENDS),
        [plannerOpen, planner.as, preview.from, preview.to],
    );
    const overlays3d = useMemo(
        () => (routeEnds ? [overlay("route-ends", routeEnds, routeEndsData)] : []),
        [routeEnds, routeEndsData],
    );

    const itemsTopic = useTopicList<ItemRow>("items/owned", view === "items" ? {} : null);
    const [selectedItemId, setSelectedItemId] = useState<number | null>(null);

    const fc = useFloorGeoJson(floor);
    const transports = useMemo(() => world3dTransports(fc), [fc]);

    // A player the chat names: one of the sessions' bots, else one a session sees.
    const panToPlayer = useCallback(
        (username: string, senderIndex: number) => {
            const name = username.toLowerCase();
            const bot = Object.values(bots).find((b) => b.username.toLowerCase() === name);
            if (bot?.position) {
                w3dRef.current?.panTo(bot.position);
                return;
            }
            const seen = world.players.find(
                (p) => (senderIndex >= 0 && p.serverIndex === senderIndex) || p.name?.toLowerCase() === name,
            );
            const height = floors?.floorHeight ?? 0;
            if (seen)
                w3dRef.current?.panTo({x: seen.x, z: seen.z, floor: height > 0 ? Math.floor(seen.z / height) : 0});
        },
        [bots, world, floors],
    );

    // The planner, open for the bot, has the walk's own stop, status and error.
    const plannerWalks = plannerOpen && planner.as != null;

    if (!floors) {
        return <div className="boot">{floorsError ? `the map is unavailable: ${floorsError}` : "loading…"}</div>;
    }

    return (
        <div id="app">
            <aside id="sidebar">
                <div className="side-head">
                    <h1>live map</h1>
                    <ConnectionStatus connection={connection} />
                </div>
                <BakeWarning />

                <section className="side-section">
                    <h2>floor</h2>
                    <FloorPicker floors={floors.floors} floor={floor} onFloorChange={floorCtl.setterFor("chrome")} />
                </section>

                <section className="side-section">
                    <h2>
                        bot
                        <span className="side-meta">
                            {botList.filter((b) => b.status === "ONLINE").length} of {botList.length} online
                        </span>
                    </h2>
                    <select
                        id="bot"
                        className="side-wide"
                        value={selectedBot ?? ""}
                        onChange={(e) => selectBot(e.target.value || null)}
                        disabled={botList.length === 0}
                    >
                        <option value="">{botList.length === 0 ? "(no bots)" : "(none)"}</option>
                        {botList.map((b) => (
                            <option key={b.username} value={b.username}>
                                {b.username} ({b.status.toLowerCase()})
                            </option>
                        ))}
                    </select>
                    {selected && <div id="botinfo">{describeBot(selected)}</div>}
                    {selectedBot && (
                        <div className="side-buttons">
                            <button
                                type="button"
                                id="follow"
                                className={(followBot ? "active" : "") + (canPlay ? "" : " side-span")}
                                aria-pressed={followBot}
                                title={
                                    followBot ? "The view follows the bot: click to stop" : "Keep the view on the bot"
                                }
                                onClick={() => setFollowBot((v) => !v)}
                            >
                                follow
                            </button>
                            {canPlay && (
                                <button
                                    type="button"
                                    id="open-client"
                                    disabled={!features?.client}
                                    title={
                                        features?.client
                                            ? "Play the bot in the game client, in a window of its own"
                                            : "set client in session-proxy.yaml to the game client's folder"
                                    }
                                    onClick={() => openClient(selectedBot)}
                                >
                                    open client
                                </button>
                            )}
                        </div>
                    )}
                </section>

                <section className="side-section">
                    <h2>{canPlay ? "play" : "route planner"}</h2>
                    {selectedBot && !canPlay && <div className="hint">{selectedBot} is offline</div>}
                    <div className="side-buttons">
                        <button
                            type="button"
                            id="planner-btn"
                            className={"side-span" + (plannerOpen ? " active" : "")}
                            onClick={() => setPlannerOpen((v) => !v)}
                        >
                            {plannerOpen ? "hide route planner" : "plan a route"}
                        </button>
                        {canPlay && !plannerWalks && (
                            <button
                                type="button"
                                id="walk-stop"
                                disabled={!control?.walkingTo}
                                onClick={() => playing(stopWalk)}
                            >
                                stop walking
                            </button>
                        )}
                        {canPlay && (
                            <button
                                type="button"
                                id="pause-clients"
                                className={(control?.paused ? "active" : "") + (plannerWalks ? " side-span" : "")}
                                aria-pressed={control?.paused ?? false}
                                title={
                                    control?.paused
                                        ? "Every client's input but the map's own is ignored: click to take it again"
                                        : "Ignore every client's input but the map's own"
                                }
                                onClick={() => playing((u) => pauseClients(u, !control?.paused))}
                            >
                                pause clients
                            </button>
                        )}
                    </div>
                    {canPlay && !plannerWalks && (control?.walkingTo != null || control?.walkNote != null) && (
                        <div className="hint" id="play-status">
                            {control.walkingTo
                                ? `walking to ${control.walkingTo.x}, ${control.walkingTo.z}`
                                : `last walk: ${control.walkNote}`}
                        </div>
                    )}
                    {canPlay && !plannerWalks && playError && <div className="hint err">{playError}</div>}
                </section>

                {view === "world" && (
                    <section className="side-section">
                        <h2>view</h2>
                        <World3DOptions
                            values={w3dOptions}
                            onChange={setW3dOptions}
                            flying={flying}
                            onFly={() => w3dRef.current?.toggleFly()}
                        />
                    </section>
                )}

                <section className="side-section">
                    <h2>layers</h2>
                    <LayerList
                        rows={LAYER_ROWS}
                        layers={layers}
                        onChange={(key, on) => setLayers((l) => ({...l, [key]: on}))}
                        on3dView={view === "world"}
                    />
                </section>
            </aside>
            <div id="main">
                <div id="viewbar">
                    <div className="view-switch">
                        {VIEWS.map((v) => (
                            <button
                                type="button"
                                key={v}
                                className={view === v ? "active" : ""}
                                onClick={() => setView(v)}
                            >
                                {VIEW_LABEL[v]}
                            </button>
                        ))}
                    </div>
                    <div className="viewbar-spacer" />
                    {view !== "items" && selectedBot && (
                        <button
                            type="button"
                            className={"inspect-toggle" + (inspectorOpen ? " active" : "")}
                            onClick={() => setInspectorOpen((v) => !v)}
                        >
                            {inspectorOpen ? "hide inspector ▸" : `◂ inspect ${selectedBot}`}
                        </button>
                    )}
                </div>

                <div id="workspace">
                    <div id="center">
                        <div id="map">
                            <WorldMap
                                floors={floors}
                                floor={floor}
                                layers={layers}
                                world={world}
                                active={view === "map"}
                                sharedView={sharedViewRef}
                                bots={botList}
                                objectDefs={objectDefs}
                                selectedBot={selectedBot}
                                onSelectBot={selectBot}
                                followBot={followBot}
                                focusBot={focusTick}
                                panTarget={null}
                                onFloorChange={floorCtl.setterFor("map")}
                                onHover={setHover}
                                onPickEntity={setPicked}
                                interactive={!plannerPicking}
                                overlays={(ctx) => (
                                    <>
                                        <TileClickProbe
                                            active={plannerPicking}
                                            width={ctx.width}
                                            floorHeight={ctx.floorHeight}
                                            floor={ctx.floor}
                                            onTile={planner.tilePicked}
                                        />
                                        <RouteEndMarkers
                                            ends={routeEndsData}
                                            width={ctx.width}
                                            floorHeight={ctx.floorHeight}
                                            floor={ctx.floor}
                                        />
                                        <RouteLine
                                            route={shownPlan}
                                            style={WALK_PLAN_STYLE}
                                            width={ctx.width}
                                            floorHeight={ctx.floorHeight}
                                            floor={ctx.floor}
                                        />
                                        <RouteLine
                                            route={shownRoute}
                                            style={showsWalk ? WALK_PATH_STYLE : PLANNED_STYLE}
                                            width={ctx.width}
                                            floorHeight={ctx.floorHeight}
                                            floor={ctx.floor}
                                        />
                                    </>
                                )}
                            />
                            <CoordReadout
                                hover={hover}
                                width={floors.width}
                                floorHeight={floors.floorHeight}
                                floor={floor}
                            />
                        </div>
                        {view === "items" && (
                            <ItemsView
                                items={itemsTopic.data ?? null}
                                error={itemsTopic.error}
                                onRefresh={() => topics.refresh("items/owned")}
                                selectedId={selectedItemId}
                                onSelectItem={setSelectedItemId}
                            />
                        )}
                        {worldMounted && (
                            <div id="world3d-view" style={{display: view === "world" ? undefined : "none"}}>
                                <World3DView
                                    ref={w3dRef}
                                    visible={view === "world"}
                                    toolbar={false}
                                    sharedView={sharedViewRef}
                                    observers={observers}
                                    world={world}
                                    selectedBot={selectedBot}
                                    onSelectBot={selectBot}
                                    menu={canPlay}
                                    useItem={canPlay ? useItem : null}
                                    onUseItemDone={() => setUseItem(null)}
                                    floorIndex={floor}
                                    onFloorIndexChange={floorCtl.setterFor("world")}
                                    toggles={w3dOptions}
                                    onTogglesChange={setW3dOptions}
                                    follow={followBot ? selectedBot : null}
                                    onPickEntity={setPicked}
                                    layers={layers}
                                    transports={transports}
                                    onFlyingChange={setFlying}
                                    pickTool={plannerPicking}
                                    onTilePick={planner.tilePicked}
                                    onTileHover={preview.previewing ? setHover3d : undefined}
                                    route={shownRoute}
                                    plan={shownPlan}
                                    overlays={overlays3d}
                                />
                            </div>
                        )}
                        {view !== "items" && picked && <EntityCard pick={picked} onClose={() => setPicked(null)} />}
                        {view !== "items" && canPlay && selected.windows && panel.data && (
                            <BotWindows
                                key={selectedBot}
                                windows={selected.windows}
                                pack={panel.data.inventory}
                                bank={panel.data.bankSeen ? panel.data.bank : null}
                                bankCapacity={panel.data.bankMaxSize}
                                defs={itemDefs}
                                onBank={(move) => attempt((u) => bankMove(u, move))}
                                onTrade={(move) => attempt((u) => tradeMove(u, move))}
                                onDuel={(move) => attempt((u) => duelMove(u, move))}
                            />
                        )}
                        <ChatLog
                            chat={chatFeed.chat}
                            privateMessages={chatFeed.privateMessages}
                            serverMessages={
                                (selectedBot ? chatFeed.serverMessages[selectedBot] : undefined) ?? NO_SERVER_LINES
                            }
                            selectedBot={selectedBot}
                            canControl={canPlay}
                            onSay={(user, message) => chatBot(user, null, message)}
                            onTell={(user, to, message) => chatBot(user, to, message)}
                            onPanToPlayer={panToPlayer}
                            onSelectBot={selectBot}
                            hidden={view === "items"}
                        />
                    </div>

                    {view !== "items" && plannerOpen && (
                        <PlannerPanel
                            host={httpPlannerHost}
                            inputs={planner.inputs}
                            copySources={copySources}
                            itemDefs={itemDefs}
                            start={planner.start}
                            end={planner.end}
                            picking={plannerPicking}
                            onClearRoute={planner.clear}
                            onReplan={planner.replan}
                            pickTarget={planner.pick?.which ?? null}
                            onArmEndpoint={planner.armEndpoint}
                            onSetEndpoint={planner.setEndpoint}
                            state={planner.state}
                            onStateChange={planner.setState}
                            plan={planner.plan}
                            loading={planner.loading}
                            error={planner.error}
                            onHoverStep={noop}
                            onFocusStep={noop}
                            onCopyFrom={copyFrom}
                            onClose={() => setPlannerOpen(false)}
                            bot={plannerBot}
                            asBot={planner.as != null}
                            onAsBotChange={planner.setAsBot}
                            defaultPolicy={defaultPolicy}
                            onSetDefaultPolicy={setDefaultPolicy}
                        />
                    )}
                    {view === "items" && selectedItemId != null && (
                        <ItemPanel
                            itemId={selectedItemId}
                            onPickBot={pickBot}
                            onClose={() => setSelectedItemId(null)}
                        />
                    )}
                    {view !== "items" && inspectorOpen && selectedBot && (
                        <BotPanel
                            username={selectedBot}
                            state={panel.data}
                            error={panel.error}
                            badges={
                                selected && selected.status !== "ONLINE" ? (
                                    <span className="insp-cached" title="last-known state">
                                        offline
                                    </span>
                                ) : control?.paused ? (
                                    <span className="insp-cached" title="every client's input but the map's is ignored">
                                        clients paused
                                    </span>
                                ) : undefined
                            }
                            script={selected?.scriptClass}
                            itemDefs={itemDefs}
                            inventoryControl={inventoryControl}
                            dock={
                                canPlay && (
                                    <>
                                        {useItem && (
                                            <UseItemDock name={useItem.name} onCancel={() => setUseItem(null)} />
                                        )}
                                        {control?.menu && (
                                            <MenuDock
                                                options={control.menu}
                                                onAnswer={(option) =>
                                                    playing((u) => actBot(u, "answer", {x: 0, z: 0, id: option}, null))
                                                }
                                            />
                                        )}
                                    </>
                                )
                            }
                            onClose={() => setInspectorOpen(false)}
                        />
                    )}
                </div>
            </div>
        </div>
    );
}
