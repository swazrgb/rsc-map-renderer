import {useEffect, useMemo, useRef, useState} from "react";
import {createRoot} from "react-dom/client";
import {need} from "./need";
import type {MapEntity, NpcDefInfo, NpcSpawnInfo, Observer, WorldEntities} from "./api";
import {World3DView, floorIndexForKey} from "./World3DView";
import {EntityCard, type CardRow, type PickedEntity} from "./EntityCard";
import {boxPerimeter, drapeGate, overlay} from "./World3DOverlays";
import type {OverlayContext, OverlayPoint, ViewerOverlay} from "./World3DOverlays";
import {predictedOverlay} from "./World3DPaths";
import type {Ribbon} from "./World3DSight";
import type {TagPool} from "./World3DTags";
import {
    type FloorTransports,
    fetchFloorTransports,
    fetchNpcDefs,
    fetchNpcSpawns,
    formatAppearance,
    parseAppearance,
} from "./api";
import {DemoServer, type ServerTransport} from "./sim/demoServer";
import {PackWindow, type PackItem} from "./sim/PackWindow";
import type {World3DHandle} from "./World3DView";
import {PlannerPanel, type PlannerHostState} from "./planner/PlannerPanel";
import {routeEndsOverlay} from "./planner/routeEndsOverlay";
import {enginePlanPoints} from "./planner/enginePlan";
import {usePlannerUrlSync} from "./planner/plannerUrl";
import {usePlannerSession} from "./planner/usePlannerSession";
import {usePlannerPreview} from "./planner/usePlannerPreview";
import {wasmPlannerHost, type WasmPlannerHost} from "./planner/wasmPlannerHost";
import type {ItemNames, PlannerHost, PlanRequest, PlanResponse} from "./planner/plannerApi";
import {SearchBox} from "./search/SearchBox";
import {LABEL_FILL, NO_MARKS, searchOverlay} from "./search/searchOverlay";
import {FLOOR_NAME, FLOOR_TILES, npcEntries, wanderBox} from "./search/worldSearch";
import {useCampSession} from "./scriptgen/campUrl";
import {GeneratePanel} from "./scriptgen/GeneratePanel";
import {campOverlay, NO_CAMP, type CampMarks} from "./scriptgen/campOverlay";
import type {CampResponse} from "./scriptgen/campApi";

// A labelled tile box, z absolute.
interface Region {
    id: string;
    name: string;
    x0: number;
    z0: number;
    x1: number;
    z1: number;
}

// An example overlay: draped boxes with a label each, highlighted on hover. The label takes the click,
// not the box, which would swallow clicks on the ground inside it.
function regionOverlay(onSelect: (id: string) => void): ViewerOverlay<Region[]> {
    const ribbons = new Map<string, Ribbon>();
    let labels: TagPool | null = null;
    let hovered: string | null = null;
    const stale = drapeGate();
    const restyle = (ribbon: Ribbon, id: string) =>
        id === hovered ? ribbon.setStyle(0x7ee3ff, 0.95, 0.34) : ribbon.setStyle(0x7ee3ff, 0.5, 0.18);
    return {
        mount(ctx: OverlayContext) {
            labels = ctx.tags({
                css:
                    "font:600 10px monospace;color:#7ee3ff;background:rgba(10,24,32,.8);" +
                    "padding:1px 5px;border:1px solid rgba(126,227,255,.5);border-radius:4px;",
                band: "chrome",
                interactive: true,
            });
            labels.onClick = (key) => onSelect(key);
        },
        frame(ctx: OverlayContext, regions: Region[]) {
            const redrape = stale(ctx, regions);
            for (const r of regions) {
                let ribbon = ribbons.get(r.id);
                if (!ribbon) {
                    ribbon = ctx.ribbon({color: 0x7ee3ff, opacity: 0.5, ghostOpacity: 0.18});
                    ribbons.set(r.id, ribbon);
                }
                if (redrape) {
                    ribbon.extrude([{pts: boxPerimeter(r), closed: true}], ctx.toWorld);
                }
            }
            labels?.begin(ctx.view, ctx.camera);
            for (const r of regions) {
                if (Math.floor(r.z0 / 944) !== ctx.floor) continue;
                labels?.put(r.id, ctx.toWorld((r.x0 + r.x1) / 2, (r.z0 + r.z1) / 2), r.name);
            }
            labels?.end();
        },
        hitTest(at: OverlayPoint, regions: Region[]): string | null {
            for (const r of regions) {
                if (at.x >= r.x0 && at.x <= r.x1 && at.z >= r.z0 && at.z <= r.z1) {
                    return r.id;
                }
            }
            return null;
        },
        hover(hit: string | null) {
            hovered = hit;
            for (const [id, ribbon] of ribbons) restyle(ribbon, id);
        },
    };
}

const DEMO_REGIONS: Region[] = [{id: "swamp", name: "Lumbridge swamp", x0: 108, z0: 680, x1: 140, z1: 700}];

const NO_OBSERVERS: Observer[] = [];
// Stable empty references: with the simulation off the view draws every spawn as a ghost.
const NOTHING_ALIVE: WorldEntities = {
    npcs: [],
    players: [],
    objects: [],
    wallObjects: [],
    groundItems: [],
};

// A search hit's NPC: plate forced on, ring and plate outline in the label fill. The one the list
// points at: plate filled, ring cyan over even a fight's.
const SEARCH_RING: Partial<MapEntity> = {ringColor: LABEL_FILL, tagOutline: LABEL_FILL, tagAlways: true};
// A spawn the script generator camps: plate forced on, ring and plate outline in the camp overlay's colour.
const CAMP_TARGET_RING: Partial<MapEntity> = {ringColor: 0xff9e3d, tagOutline: 0xff9e3d, tagAlways: true};
const SEARCH_RING_LIT: Partial<MapEntity> = {
    ringColor: 0x5ff2ff,
    ringColorFirst: true,
    tagColor: LABEL_FILL,
    tagAlways: true,
};

function npcRows(
    pick: PickedEntity,
    spawns: readonly NpcSpawnInfo[],
    defs: ReadonlyMap<number, NpcDefInfo> | null,
): CardRow[] {
    const rows: CardRow[] = [];
    const id = pick.npcId;
    if (id != null) rows.push({label: "id", value: String(id)});
    const i = pick.serverIndex;
    // The table is in server index order; a spawn of another definition there means a stale pick.
    const spawn = i != null && i >= 0 && i < spawns.length && spawns[i].id === id ? spawns[i] : null;
    if (spawn) {
        const floor = Math.floor(spawn.z / FLOOR_TILES);
        rows.push({label: "index", value: `#${spawn.serverIndex}`});
        rows.push({label: "spawn", value: `${spawn.x}, ${spawn.z}` + (floor > 0 ? ` (${FLOOR_NAME[floor]})` : "")});
        const box = wanderBox(spawn);
        const radius = Math.max(spawn.x - box.x0, box.x1 - spawn.x, spawn.z - box.z0, box.z1 - spawn.z);
        rows.push({label: "radius", value: String(radius)});
        rows.push({label: "wander", value: `${box.x0}, ${box.z0} – ${box.x1}, ${box.z1}`});
    }
    const def = id != null ? defs?.get(id) : undefined;
    if (def) {
        rows.push({label: "respawn", value: def.respawnSeconds > 0 ? `${def.respawnSeconds}s` : "never"});
        if (def.attackable) {
            rows.push({label: "combat", value: String(def.combatLevel)});
            rows.push({label: "hits", value: String(def.hits)});
            rows.push({label: "stats", value: `att ${def.attack} · str ${def.strength} · def ${def.defense}`});
            rows.push({label: "max hit", value: String(def.maxHit)});
            rows.push({label: "aggro", value: def.aggressive ? "yes" : "no"});
        } else {
            rows.push({label: "combat", value: "not attackable"});
        }
        if (def.members) rows.push({label: "members", value: "yes"});
        if (def.description) rows.push({label: "examine", value: def.description});
    }
    if (id != null) rows.push({label: "spawns", value: String(spawns.filter((s) => s.id === id).length)});
    if (pick.inCombat) rows.push({label: "state", value: "in combat"});
    return rows;
}

const NOT_NPC = (kind: string) => kind !== "npc";

const ROUTE_BOT = "route bot";

// The server's tick, in milliseconds.
const TICK_MS = 640;
const SPEEDS = [1, 2, 4, 8, 16];

function urlParam(key: string): string | null {
    return new URLSearchParams(location.search).get(key);
}

function writeUrlParam(key: string, value: string | null): void {
    const p = new URLSearchParams(location.search);
    if (value == null) p.delete(key);
    else p.set(key, value);
    history.replaceState(null, "", `${location.pathname}?${p}${location.hash}`);
}

// The open-source demo. With the simulation on, the page plays the server (sim/demoServer.ts) over the
// world snapshot the bake wrote; the route planner runs in the page too, and a found plan is walked
// by a route bot that the planner's worker drives tick by tick.
function Demo({spawns}: {spawns: NpcSpawnInfo[]}) {
    const names = useMemo(() => {
        const out: Record<number, string> = {};
        for (const s of spawns) if (s.name) out[s.id] = s.name;
        return out;
    }, [spawns]);
    const [simulation, setSimulation] = useState(() => urlParam("sim") !== "0");
    const chooseSimulation = (on: boolean) => {
        setSimulation(on);
        writeUrlParam("sim", on ? null : "0");
    };
    const [follow, setFollow] = useState(() => urlParam("follow") === "1");
    const chooseFollow = (on: boolean) => {
        setFollow(on);
        writeUrlParam("follow", on ? "1" : null);
    };
    const [danger, setDanger] = useState(() => urlParam("danger") === "1");
    const chooseDanger = (on: boolean) => {
        setDanger(on);
        writeUrlParam("danger", on ? "1" : null);
    };
    const [picked, setPicked] = useState<PickedEntity | null>(null);
    const [npcDefs, setNpcDefs] = useState<ReadonlyMap<number, NpcDefInfo> | null>(null);
    useEffect(() => {
        let alive = true;
        fetchNpcDefs().then(
            (defs) => alive && setNpcDefs(new Map(defs.map((d) => [d.id, d]))),
            (e: unknown) => console.warn("[demo] npc defs:", e),
        );
        return () => {
            alive = false;
        };
    }, []);
    // The overlay is stable and only its data changes per render, so a fresh `overlays` array remounts nothing.
    const regions = useMemo(
        () =>
            regionOverlay((id) =>
                setPicked({
                    kind: "region",
                    name: DEMO_REGIONS.find((r) => r.id === id)?.name ?? id,
                    npcId: null,
                    inCombat: false,
                }),
            ),
        [],
    );
    const viewRef = useRef<World3DHandle>(null);
    const routeEnds = useMemo(() => routeEndsOverlay(944), []);
    // Its pointers stay clear of the search list (8px in, 300px wide) and the toolbar above it.
    const searchMarks = useMemo(() => searchOverlay({left: 308, top: 44}), []);
    const [marks, setMarks] = useState(NO_MARKS);
    const [speed, setSpeed] = useState(() => {
        const v = Number(urlParam("speed"));
        return SPEEDS.includes(v) ? v : 1;
    });
    const nextSpeed = () => {
        const v = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.length];
        setSpeed(v);
        writeUrlParam("speed", v === 1 ? null : String(v));
    };
    const tickMs = TICK_MS / speed;
    const [plannerOpen, setPlannerOpen] = useState(false);
    const gen = useCampSession();
    const genOpen = gen.open;
    const genTargets = gen.state.targets;
    const setGenTargets = (update: (targets: number[]) => number[]) =>
        gen.setState((s) => ({...s, targets: update(s.targets)}));
    const [genResult, setGenResult] = useState<CampResponse | null>(null);
    const host = useNavHost(simulation || plannerOpen || genOpen);
    const genItems = useItemNames(genOpen ? host : null);
    const genWearables = useItemSet(genOpen ? host : null, "wearableItems");
    const genEdibles = useItemSet(genOpen ? host : null, "edibleItems");
    const planner = useDemoPlanner(host, plannerOpen, setPlannerOpen);
    const campMarks = useMemo<CampMarks>(
        () =>
            genOpen
                ? {
                      targets: genTargets.flatMap((i) => (spawns[i] ? [spawns[i]] : [])),
                      camp: genResult?.camp && genResult.campBox ? {...genResult.camp, box: genResult.campBox} : null,
                      bank:
                          genResult?.bankTile && genResult.bank ? {...genResult.bankTile, name: genResult.bank} : null,
                  }
                : NO_CAMP,
        [genOpen, genTargets, genResult, spawns],
    );
    const campLayer = useMemo(() => campOverlay(), []);
    const genRoute = useMemo(
        () => (genOpen && genResult && !genResult.error ? [...genResult.toBank, ...genResult.toCamp] : null),
        [genOpen, genResult],
    );
    const attackable = (s: NpcSpawnInfo) => npcDefs?.get(s.id)?.attackable === true;
    const pickEntity = (pick: PickedEntity | null) => {
        const i = pick?.kind === "npc" ? pick.serverIndex : null;
        if (genOpen && i != null && i >= 0 && spawns[i]?.id === pick?.npcId) {
            if (attackable(spawns[i])) setGenTargets((t) => (t.includes(i) ? t.filter((x) => x !== i) : [...t, i]));
            return;
        }
        setPicked(pick);
    };
    const [genSweeping, setGenSweeping] = useState(false);
    // The box is floor-local: its spawns are the shown floor's.
    const sweptSpawns = (box: {x0: number; z0: number; x1: number; z1: number}) => {
        setGenSweeping(false);
        const dz = floor * 944;
        const hits = spawns.filter(
            (s) => s.x >= box.x0 && s.x <= box.x1 && s.z >= box.z0 + dz && s.z <= box.z1 + dz && attackable(s),
        );
        setGenTargets((t) => [...t, ...hits.map((s) => s.serverIndex).filter((i) => !t.includes(i))]);
    };
    const wasOpenRef = useRef(plannerOpen);
    useEffect(() => {
        // Closing the planner ends the follow; on the closing edge only, so a `?follow=1` link survives load.
        if (wasOpenRef.current && !plannerOpen) chooseFollow(false);
        wasOpenRef.current = plannerOpen;
    }, [plannerOpen]);
    const sim = useDemoWorld(host, simulation, tickMs, planner.walk);
    const [floor, setFloor] = useState(() => floorIndexForKey(new URLSearchParams(location.search).get("floor")));
    const transports = useFloorTransports(floor, planner.open || genOpen);
    const [reachable, setReachable] = useState<ReadonlySet<number> | null>(null);

    const spawnEntries = useMemo(
        () => new Map(npcEntries(spawns).flatMap((e) => (e.npc ? [[e.npc.serverIndex, e] as const] : []))),
        [spawns],
    );
    const pickedEntry =
        picked?.kind === "npc" && picked.serverIndex != null ? spawnEntries.get(picked.serverIndex) : undefined;
    const openCard = pickedEntry?.npc?.id === picked?.npcId ? pickedEntry : undefined;
    const target = useMemo(
        () => (marks.lit.length === 0 && openCard ? {matches: marks.matches, lit: [openCard]} : marks),
        [marks, openCard],
    );
    const searchRings = useMemo(() => {
        const out = new Map<number, Partial<MapEntity>>();
        for (const e of target.matches) if (e.npc) out.set(e.npc.serverIndex, SEARCH_RING);
        for (const e of target.lit) if (e.npc) out.set(e.npc.serverIndex, SEARCH_RING_LIT);
        if (genOpen) for (const i of genTargets) out.set(i, CAMP_TARGET_RING);
        return out;
    }, [target, genOpen, genTargets]);
    const world: WorldEntities = useMemo(() => {
        const server = sim.server;
        if (!simulation || !server) return NOTHING_ALIVE;
        const {npcs, players} = server.entities();
        return {
            npcs: npcs.map((n) => ({...n, name: names[n.id] ?? null, ...searchRings.get(n.serverIndex)})),
            players: players.map((p) => ({...p, name: null})),
            objects: [],
            wallObjects: [],
            groundItems: [],
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps -- sim.tick versions the mutable server
    }, [sim.server, sim.tick, simulation, names, searchRings]);
    const botLook = sim.carried?.look ?? ROUTE_BOT_LOOK;
    const bot = sim.server?.bot ?? null;
    const botX = bot?.x;
    const botZ = bot?.z;
    const botFighting = bot?.opponent != null;
    const botDir = sim.server?.botDir() ?? null;
    // One observer with no position, which carries the tick that stamps timed events, and the route bot.
    const observers: Observer[] = useMemo(
        () =>
            simulation
                ? [
                      {
                          username: "demo",
                          status: "online",
                          scriptClass: null,
                          description: null,
                          serverTick: sim.tick,
                          position: null,
                          fatiguePercent: 0,
                          sleeping: false,
                          inCombat: false,
                      },
                      ...(botX != null && botZ != null
                          ? [
                                {
                                    username: ROUTE_BOT,
                                    status: "online",
                                    scriptClass: null,
                                    description: null,
                                    serverTick: sim.tick,
                                    position: {x: botX, z: botZ, floor: Math.floor(botZ / 944)},
                                    fatiguePercent: 0,
                                    sleeping: false,
                                    inCombat: botFighting,
                                    dir: botDir,
                                    snapSeq: sim.walkSeq,
                                    appearance: botLook,
                                    ringColor: ROUTE_BOT_RING,
                                },
                            ]
                          : []),
                  ]
                : NO_OBSERVERS,
        [sim.tick, simulation, botX, botZ, botFighting, botDir, sim.walkSeq, botLook],
    );
    const route = genRoute ?? (simulation ? sim.botPath : planner.route);
    const predicted = simulation ? planner.route : null;

    return (
        <div style={{width: "100vw", height: "100vh"}}>
            <World3DView
                ref={viewRef}
                observers={observers}
                world={world}
                focus={{x: 122, z: 650}}
                hideSight
                onPickEntity={pickEntity}
                selectOnClick={NOT_NPC}
                layers={{dangerZones: danger, transports: planner.open || genOpen}}
                transports={transports}
                floorIndex={floor}
                onFloorIndexChange={setFloor}
                overlays={[
                    overlay("regions", regions, DEMO_REGIONS),
                    overlay("predicted", predictedOverlay, predicted),
                    overlay("route-ends", routeEnds, planner.ends),
                    overlay("search", searchMarks, target),
                    overlay("camp", campLayer, campMarks),
                ]}
                route={route}
                plan={planner.plan}
                follow={follow && simulation && plannerOpen ? ROUTE_BOT : null}
                onFollowBreak={() => chooseFollow(false)}
                reachableComponents={planner.open ? reachable : null}
                pickTool={planner.picking}
                areaTool={genOpen && genSweeping}
                areaRect={null}
                onAreaRectChange={sweptSpawns}
                onTilePick={planner.session.tilePicked}
                onTileHover={planner.hover}
                togglesAfterRoofs={
                    <>
                        <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                            <input
                                type="checkbox"
                                checked={simulation}
                                onChange={(e) => chooseSimulation(e.target.checked)}
                            />
                            simulation
                        </label>
                        {simulation && (
                            <button
                                type="button"
                                style={{marginLeft: 4, minWidth: 34}}
                                title="simulation speed: the NPCs and the route bot"
                                onClick={nextSpeed}
                            >
                                {speed}x
                            </button>
                        )}
                    </>
                }
                tickMs={tickMs}
                togglesAfterTags={
                    <>
                        <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                            <input
                                type="checkbox"
                                checked={planner.open}
                                onChange={(e) => setPlannerOpen(e.target.checked)}
                            />
                            route planner
                        </label>
                        <label style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}>
                            <input type="checkbox" checked={genOpen} onChange={(e) => gen.setOpen(e.target.checked)} />
                            generate script
                        </label>
                        <label
                            style={{marginLeft: 8, display: "flex", alignItems: "center", gap: 4}}
                            title="tiles an aggressive NPC makes a route pay for"
                        >
                            <input type="checkbox" checked={danger} onChange={(e) => chooseDanger(e.target.checked)} />
                            danger zones
                        </label>
                    </>
                }
            />
            <SearchBox
                spawns={spawns}
                initialText={INITIAL_SEARCH}
                onTextSettled={keepSearchInUrl}
                initialExact={INITIAL_EXACT}
                onExactChange={keepExactInUrl}
                onGo={(e) => {
                    viewRef.current?.panTo({x: e.x, z: e.z, floor: e.floor});
                    if (e.kind === "object" || e.kind === "boundary") {
                        setPicked({kind: e.kind, name: e.title, npcId: e.defId, inCombat: false});
                    } else if (e.npc) {
                        setPicked({
                            kind: "npc",
                            name: e.title,
                            npcId: e.npc.id,
                            serverIndex: e.npc.serverIndex,
                            inCombat: false,
                        });
                    }
                }}
                onMarks={setMarks}
            />
            {planner.open && planner.host && (
                <PlannerPanel
                    host={planner.host}
                    hostState={planner.hostState}
                    hostProgress={planner.progress}
                    inputs={planner.session.inputs}
                    copySources={NO_COPY_SOURCES}
                    itemDefs={planner.items}
                    wearableItems={planner.wearables}
                    start={planner.session.start}
                    end={planner.session.end}
                    picking={planner.picking}
                    onClearRoute={planner.session.clear}
                    onReplan={planner.session.replan}
                    follow={follow}
                    onFollowChange={simulation ? chooseFollow : undefined}
                    pickTarget={planner.session.pick?.which ?? null}
                    onArmEndpoint={planner.session.armEndpoint}
                    onSetEndpoint={planner.session.setEndpoint}
                    state={planner.session.state}
                    onStateChange={planner.session.setState}
                    plan={planner.session.plan}
                    loading={planner.session.loading}
                    error={planner.session.error}
                    onHoverStep={NO_HOVER}
                    onFocusStep={(t) => viewRef.current?.panTo({x: t.x, z: t.z, floor: Math.floor(t.z / 944)})}
                    onCopyFrom={planner.session.copyFrom}
                    onClose={() => setPlannerOpen(false)}
                    onReachable={setReachable}
                />
            )}
            {genOpen && (
                <GeneratePanel
                    host={host}
                    spawns={spawns}
                    npcDefs={npcDefs}
                    itemNames={genItems}
                    wearables={genWearables}
                    edibles={genEdibles}
                    targets={genTargets}
                    onTargets={(targets) => setGenTargets(() => targets)}
                    sweeping={genSweeping}
                    onSweeping={setGenSweeping}
                    settings={gen.state.settings}
                    onSettings={(update) => gen.setState((s) => ({...s, settings: update(s.settings)}))}
                    onResult={setGenResult}
                    onFocus={(t) => viewRef.current?.panTo({x: t.x, z: t.z, floor: Math.floor(t.z / 944)})}
                    onClose={() => gen.setOpen(false)}
                />
            )}
            {sim.carried && <PackWindow items={sim.carried.items} names={planner.items} />}
            {picked && (
                <div style={{position: "absolute", bottom: 34, right: 8}}>
                    <EntityCard
                        pick={picked}
                        onClose={() => setPicked(null)}
                        details={picked.kind === "npc" ? npcRows(picked, spawns, npcDefs) : undefined}
                    />
                </div>
            )}
        </div>
    );
}

const NO_COPY_SOURCES: never[] = [];
const INITIAL_SEARCH = urlParam("q") ?? "";
const keepSearchInUrl = (text: string) => writeUrlParam("q", text.trim() === "" ? null : text);
const INITIAL_EXACT = urlParam("exact") === "1";
const keepExactInUrl = (exact: boolean) => writeUrlParam("exact", exact ? "1" : null);
const NO_ENDS = {start: null, end: null};
const NO_HOVER = () => {
    // The demo draws nothing for a hovered step.
};

const floorTransports = new Map<number, Promise<FloorTransports>>();

// `floor`'s transports, fetched once per floor and only once `enabled`; a failed fetch is retried.
function useFloorTransports(floor: number, enabled: boolean): FloorTransports | undefined {
    const [shown, setShown] = useState<{floor: number; list: FloorTransports} | null>(null);
    useEffect(() => {
        if (!enabled) return;
        let alive = true;
        let p = floorTransports.get(floor);
        if (!p) {
            p = fetchFloorTransports(floor).catch((e: unknown) => {
                floorTransports.delete(floor);
                console.warn(`[transports] floor ${floor}:`, e);
                return [];
            });
            floorTransports.set(floor, p);
        }
        void p.then((list) => alive && setShown({floor, list}));
        return () => {
            alive = false;
        };
    }, [floor, enabled]);
    return enabled && shown?.floor === floor ? shown.list : undefined;
}

const ROUTE_BOT_LOOK = formatAppearance({
    sprites: [7, 2, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    hair: 7,
    top: 11,
    bottom: 11,
    skin: 0,
});
const ROUTE_BOT_RING = 0x58ff8a;

// Every item's name, once the host has read the world; null while it has not, or without a host.
function useItemNames(host: WasmPlannerHost | null): ItemNames | null {
    const [names, setNames] = useState<ItemNames | null>(null);
    useEffect(() => {
        if (!host || names) return;
        let alive = true;
        host.items().then(
            (n) => alive && setNames(n),
            () => alive && setNames({}),
        );
        return () => {
            alive = false;
        };
    }, [host, names]);
    return names;
}

// The ids `list` answers, once the host has read the world; null while it has not, or without a host.
function useItemSet(host: WasmPlannerHost | null, list: "wearableItems" | "edibleItems"): ReadonlySet<number> | null {
    const [ids, setIds] = useState<ReadonlySet<number> | null>(null);
    useEffect(() => {
        if (!host || ids) return;
        let alive = true;
        host[list]().then(
            (n) => alive && setIds(new Set(n)),
            () => alive && setIds(new Set()),
        );
        return () => {
            alive = false;
        };
    }, [host, ids, list]);
    return ids;
}

// One planner worker for the page's life, started when first wanted, so its world and caches stay warm.
let pageNavHost: WasmPlannerHost | null = null;

function useNavHost(wanted: boolean): WasmPlannerHost | null {
    const [started, setStarted] = useState(wanted);
    if (wanted && !started) setStarted(true);
    return started ? (pageNavHost ??= wasmPlannerHost()) : null;
}

interface DemoWalk {
    req: PlanRequest;
    seq: number;
}

const walkOf = new WeakMap<PlanResponse, DemoWalk>();
let walkSeq = 0;

// The planner as the demo hosts it: nothing is built until the panel first opens, then kept.
function useDemoPlanner(host: WasmPlannerHost | null, open: boolean, setOpen: (open: boolean) => void) {
    const [opened, setOpened] = useState(false);
    const [items, setItems] = useState<ItemNames | null>(null);
    const [wearables, setWearables] = useState<ReadonlySet<number> | null>(null);
    const [built, setBuilt] = useState(false);
    const [failed, setFailed] = useState<string | null>(null);
    const [progress, setProgress] = useState<string | null>(null);
    if (open && !opened) setOpened(true);
    useEffect(() => {
        if (!host || !opened) return;
        const unfollow = host.onProgress(setProgress);
        host.pathfinder().then(
            () => setBuilt(true),
            (e: unknown) => setFailed(e instanceof Error ? e.message : String(e)),
        );
        void host.items().then(setItems, () => setItems({}));
        void host.wearableItems().then(
            (ids) => setWearables(new Set(ids)),
            () => setWearables(new Set()),
        );
        return unfollow;
    }, [host, opened]);
    const sessionHost = useMemo<PlannerHost | null>(
        () =>
            host && {
                inputs: () => host.inputs(),
                account: (req) => host.account(req),
                reachable: (req) => host.reachable(req),
                plan: (req) =>
                    host.plan(req).then((r) => {
                        walkOf.set(r, {req, seq: ++walkSeq});
                        return r;
                    }),
            },
        [host],
    );
    const session = usePlannerSession(opened ? sessionHost : null);
    usePlannerUrlSync({
        open,
        setOpen,
        inputs: session.inputs,
        state: session.state,
        setState: session.setState,
        start: session.start,
        end: session.end,
        setEndpoint: session.setEndpoint,
    });
    const {pick, start, end, state, inputs} = session;
    const [hovered, setHovered] = useState<{x: number; z: number} | null>(null);
    const {previewing, from, to, shown, shownFrom} = usePlannerPreview({
        host,
        pick: open ? pick : null,
        start,
        end,
        state,
        inputs,
        hovered,
        plan: session.plan,
    });
    // Ending a preview drops its hover, since the view reports only the tiles the cursor enters.
    if (!previewing && hovered) setHovered(null);
    const walk = useMemo<DemoWalk | null>(
        () => (open && !previewing && session.plan?.found ? (walkOf.get(session.plan) ?? null) : null),
        [open, previewing, session.plan],
    );
    const ends = useMemo(() => (open ? {start: from, end: to} : NO_ENDS), [open, from, to]);
    const route = useMemo(
        () => (open && shown?.found ? shown.steps.map((s) => ({x: s.x, z: s.z, hop: s.type === "hop"})) : null),
        [open, shown],
    );
    const plan = useMemo(
        () => (open && shown?.found ? enginePlanPoints(shownFrom, shown.plan) : null),
        [open, shown, shownFrom],
    );
    const hostState: PlannerHostState = failed ? {failed} : built ? "ready" : "loading";
    return {
        open,
        host: opened ? host : null,
        items,
        wearables,
        session,
        route,
        plan,
        ends,
        hostState,
        progress,
        walk,
        picking: open && session.pick != null,
        hover: previewing ? setHovered : undefined,
    };
}

// Players wandering Lumbridge, with appearance tokens as the wire carries them.
const DEMO_PLAYERS = [
    {x: 122, z: 648, appearance: "1,34,42,103,53,75,0,0,47,12,0,63|0,8,14,0"},
    {x: 135, z: 655, appearance: "1,2,3|3,0,11,2"},
    {x: 115, z: 660, appearance: "6,2,3|6,9,4,1"},
    {x: 128, z: 640, appearance: "4,5,3|1,12,2,3"},
];

// Ticks the loop plays in one frame before it skips ahead, as after a tab spent in the background.
const MAX_CATCH_UP = 10;

// The demo's server, built the first time the simulation runs and stepped once a tick. With a plan to
// `walk`, a route bot starts on its first tile and the worker answers each tick's view with its packets.
function useDemoWorld(host: WasmPlannerHost | null, simulation: boolean, tickMs: number, walk: DemoWalk | null) {
    const [server, setServer] = useState<DemoServer | null>(null);
    const [tick, setTick] = useState(0);
    const [botPath, setBotPath] = useState<{x: number; z: number; hop: boolean}[] | null>(null);
    const [carried, setCarried] = useState<{items: PackItem[]; look: string} | null>(null);
    const [walkSeq, setWalkSeq] = useState<number | undefined>(undefined);
    useEffect(() => {
        if (!host || !simulation || server) return;
        let alive = true;
        host.serverWorld().then(
            ({world, steps}) => {
                if (alive) setServer(new DemoServer(world, steps, DEMO_PLAYERS));
            },
            (e: unknown) => console.error("[simulation] the world did not load:", e),
        );
        return () => {
            alive = false;
        };
    }, [host, simulation, server]);

    const transportsRef = useRef<Promise<ServerTransport[]> | null>(null);
    const walkingRef = useRef<{seq: number; radius: number; busy: boolean; done: boolean; waited: boolean} | null>(
        null,
    );
    useEffect(() => {
        if (!server || !host || !simulation || !walk) return;
        const {req, seq} = walk;
        let alive = true;
        Promise.all([host.walkStart(req), (transportsRef.current ??= host.serverTransports())]).then(
            ([info, list]) => {
                if (!alive) return;
                server.setTransports(list);
                server.spawnBot(req.from.x, req.from.z, info.combatLevel);
                walkingRef.current = {seq, radius: info.viewRadius, busy: false, done: false, waited: false};
                setWalkSeq(seq);
            },
            (e: unknown) => console.warn("[route bot] the walk did not start:", e),
        );
        return () => {
            alive = false;
            walkingRef.current = null;
            server.removeBot();
            setBotPath(null);
            setCarried(null);
        };
    }, [server, host, simulation, walk]);

    useEffect(() => {
        if (!server || !host || !simulation) return;
        let anchor: {at: number; tick: number} | null = null;
        let raf = 0;
        const loop = (t: number) => {
            anchor ??= {at: t, tick: server.tick};
            let due = anchor.tick + Math.floor((t - anchor.at) / tickMs) - server.tick;
            if (due > MAX_CATCH_UP) {
                anchor = {at: t, tick: server.tick + 1};
                due = 1;
            }
            for (; due > 0; due--) {
                const w = walkingRef.current;
                // Lockstep: no tick is played before the bot has answered the last one, or it would move from where
                // it stood ticks ago. The clock restarts from here, so the speed is a cap.
                if (w?.busy) {
                    if (!w.waited) {
                        w.waited = true;
                        console.info(
                            "[route bot] the simulation is waiting on the bot's answers:" +
                                " it runs slower than the chosen speed",
                        );
                    }
                    anchor = {at: t - tickMs, tick: server.tick};
                    break;
                }
                server.step();
                ask();
            }
            setTick(server.tick);
            raf = requestAnimationFrame(loop);
        };
        const ask = () => {
            const w = walkingRef.current;
            if (!w || w.busy || w.done) return;
            const seen = server.view(w.radius);
            if (!seen) return;
            w.busy = true;
            host.walkTick(seen).then(
                (move) => {
                    if (walkingRef.current !== w) return;
                    w.busy = false;
                    server.send(move.packets, move.hop);
                    if (move.move === "arrived" || move.move === "stuck") {
                        w.done = true;
                        if (move.move === "stuck") console.warn(`[route bot] stuck: ${move.why}`);
                    } else if (move.unsent) {
                        w.done = true;
                        console.warn(`[route bot] cannot take ${move.unsent}`);
                    }
                    const p = move.path;
                    setBotPath(
                        p
                            ? Array.from({length: p.length / 2}, (_, i) => ({x: p[2 * i], z: p[2 * i + 1], hop: false}))
                            : null,
                    );
                    setCarried(packOf(move.pack, move.bank, move.worn));
                },
                (e: unknown) => {
                    w.busy = false;
                    w.done = true;
                    console.warn("[route bot] the walk failed:", e);
                },
            );
        };
        raf = requestAnimationFrame(loop);
        return () => cancelAnimationFrame(raf);
    }, [server, host, simulation, tickMs]);
    return {server, tick, botPath, walkSeq, carried};
}

// What the bot carries and banks, and its look with each worn piece's sprite in its wield position.
function packOf(
    pack: [number, number, number][] = [],
    bank: [number, number][] = [],
    worn: [number, number][] = [],
): {items: PackItem[]; look: string} {
    const items = new Map<number, PackItem>();
    for (const [id, count, wearing] of pack) {
        items.set(id, {id, held: count, banked: 0, worn: wearing !== 0});
    }
    for (const [id, count] of bank) {
        const at = items.get(id);
        if (at) at.banked = count;
        else items.set(id, {id, held: 0, banked: count, worn: false});
    }
    const look = parseAppearance(ROUTE_BOT_LOOK);
    const sprites = [...look.sprites];
    for (const [slot, sprite] of worn) {
        if (slot >= 0 && slot < sprites.length) sprites[slot] = sprite;
    }
    return {items: [...items.values()], look: formatAppearance({...look, sprites})};
}

const NO_PICK = () => {
    // Nothing to show a pick in while the world failed to load.
};

function App() {
    const [spawns, setSpawns] = useState<NpcSpawnInfo[] | null>(null);
    const [err, setErr] = useState<string | null>(null);
    useEffect(() => {
        fetchNpcSpawns()
            .then(setSpawns)
            .catch((e: unknown) => setErr(String(e)));
    }, []);
    if (err) {
        return (
            <div style={{width: "100vw", height: "100vh"}}>
                <World3DView observers={NO_OBSERVERS} focus={{x: 122, z: 650}} onPickEntity={NO_PICK} />
            </div>
        );
    }
    if (!spawns) return <div style={{padding: 16}}>Loading world…</div>;
    return <Demo spawns={spawns} />;
}

createRoot(need(document.getElementById("root"), "#root")).render(<App />);
