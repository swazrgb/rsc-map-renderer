import type {
    ItemNames,
    PlannerAccount,
    PlannerReach,
    PlannerHost,
    PlannerInputs,
    PlanRequest,
    PlanResponse,
} from "./plannerApi";
import type {NavOp, NavProgress, NavReply, NavRequest} from "./navWorker";
import {fetchWorldMeshManifest} from "../api";
import type {BotTick, BotView, ServerTransport, ServerWorld} from "../sim/demoServer";
import {latestSlot} from "./latestSlot";
import type {CampItem, CampRequest, CampResponse} from "../scriptgen/campApi";

/** The world snapshot as read, with how long each stage took in milliseconds. `doneAt` is on the worker's clock. */
interface NavWorldInfo {
    world: string;
    fetchMs: number;
    unzipMs: number;
    readMs: number;
    totalMs: number;
    doneAt: number;
}

/** The pathfinder as built, timed in milliseconds; `doneAt` is on the worker's clock. */
interface NavPathfinderInfo {
    transports: number;
    buildMs: number;
    totalMs: number;
    doneAt: number;
}

/**
 * A {@link PlannerHost} running nav's planner as WebAssembly in a worker, plus what the in-page
 * server simulation needs from it. Every call first loads the world, and most also build the
 * pathfinder, each once; `plan` and `preview` share one latest-wins slot.
 */
export interface WasmPlannerHost extends PlannerHost {
    /** Builds the pathfinder, once; settles when it can plan. */
    pathfinder(): Promise<NavPathfinderInfo>;
    preview(req: PlanRequest): Promise<PlanResponse>;
    /** Every defined item's display name. */
    items(): Promise<ItemNames>;
    /** The ids of every item that may be worn. */
    wearableItems(): Promise<number[]>;
    /** The ids of every item that heals when eaten. */
    edibleItems(): Promise<number[]>;
    /** What the in-page server needs: spawns, definitions and the walk graph. */
    serverWorld(): Promise<{world: ServerWorld; steps: Int8Array}>;
    /**
     * Starts walking `req` as a bot one tick at a time through {@link WasmPlannerHost.walkTick}, replacing any walk.
     */
    walkStart(req: PlanRequest): Promise<{viewRadius: number; combatLevel: number}>;
    /** One tick of the walk: what the bot does, having seen `view`. */
    walkTick(view: BotView): Promise<BotTick>;
    /** The transports as the in-page server executes them. */
    serverTransports(): Promise<ServerTransport[]>;
    /** A camp recipe for `req`: its text, the bank it chose and both routes. */
    campRecipe(req: CampRequest): Promise<CampResponse>;
    /** The inventory slots `items` take at their targets, by the world's rules, of the inventory's size. */
    campSlots(items: CampItem[]): Promise<{used: number; of: number}>;
    /** Calls `listener` with each load stage, at once with the current one; returns the unsubscribe. */
    onProgress(listener: (stage: string) => void): () => void;
    /** Terminates the worker; every pending call rejects. */
    dispose(): void;
}

/** Starts the worker. Nothing is downloaded until the first call. */
export function wasmPlannerHost(): WasmPlannerHost {
    const worker = new Worker(new URL("./navWorker.ts", import.meta.url), {type: "module"});
    let nextId = 1;
    const waiting = new Map<number, {resolve: (v: unknown) => void; reject: (e: Error) => void}>();
    let stage = "starting";
    const listeners = new Set<(stage: string) => void>();
    // Stage start times on the worker's clock; the page's runs late while it draws.
    let started: {stage: string; at: number}[] = [];
    const logStages = (what: string, doneAt: number) => {
        const parts = started.map((s, i) => `${s.stage} ${Math.round((started[i + 1]?.at ?? doneAt) - s.at)} ms`);
        console.info(`[planner] ${what} — ${parts.join(" · ")}`);
        started = [];
    };
    worker.onmessage = (e: MessageEvent<NavReply | NavProgress>) => {
        if ("progress" in e.data) {
            stage = e.data.progress;
            started.push({stage, at: e.data.at});
            for (const l of listeners) l(stage);
            return;
        }
        const reply = e.data;
        const w = waiting.get(reply.id);
        if (!w) return;
        waiting.delete(reply.id);
        if (reply.ok) w.resolve(reply.value);
        else w.reject(new Error(reply.error));
    };
    const call = <T>(op: NavOp, json?: string, baked?: number, view?: BotView): Promise<T> =>
        new Promise<T>((resolve, reject) => {
            const id = nextId++;
            waiting.set(id, {resolve: resolve as (v: unknown) => void, reject});
            worker.postMessage({id, op, json, baked, view} satisfies NavRequest);
        });

    let loaded: Promise<NavWorldInfo> | null = null;
    const world = () => {
        if (!loaded) {
            loaded = fetchWorldMeshManifest().then((m) => call<NavWorldInfo>("world", undefined, m.baked));
            loaded.then(
                (info) => logStages(`${info.world} read in ${info.totalMs} ms`, info.doneAt),
                (err: unknown) => console.error("[planner] the world did not load:", err),
            );
        }
        return loaded;
    };

    let built: Promise<NavPathfinderInfo> | null = null;
    const pathfinder = () => {
        if (!built) {
            built = world().then(() => call<NavPathfinderInfo>("pathfinder"));
            built.then(
                (info) =>
                    logStages(`pathfinder over ${info.transports} transports built in ${info.totalMs} ms`, info.doneAt),
                (err: unknown) => console.error("[planner] the pathfinder did not build:", err),
            );
        }
        return built;
    };

    const latest = latestSlot((job: {op: "plan" | "preview"; req: PlanRequest}) =>
        pathfinder().then(() => call<PlanResponse>(job.op, JSON.stringify(job.req))),
    );

    return {
        pathfinder,
        items: () => world().then(() => call<ItemNames>("items")),
        wearableItems: () => world().then(() => call<number[]>("wearables")),
        edibleItems: () => world().then(() => call<number[]>("edibles")),
        inputs: () => pathfinder().then(() => call<PlannerInputs>("inputs")),
        account: (req: PlanRequest) => pathfinder().then(() => call<PlannerAccount>("account", JSON.stringify(req))),
        reachable: (req: PlanRequest) => pathfinder().then(() => call<PlannerReach>("reachable", JSON.stringify(req))),
        plan: (req: PlanRequest) => latest({op: "plan", req}),
        preview: (req: PlanRequest) => latest({op: "preview", req}),
        serverWorld: () => world().then(() => call<{world: ServerWorld; steps: Int8Array}>("serverWorld")),
        walkStart: (req: PlanRequest) =>
            pathfinder().then(() => call<{viewRadius: number; combatLevel: number}>("walkStart", JSON.stringify(req))),
        walkTick: (view: BotView) => call<BotTick>("walkTick", undefined, undefined, view),
        serverTransports: () => pathfinder().then(() => call<ServerTransport[]>("serverTransports")),
        campSlots: (items: CampItem[]) =>
            world().then(() => call<{used: number; of: number}>("campSlots", JSON.stringify({items}))),
        campRecipe: (req: CampRequest) =>
            pathfinder().then(() => call<CampResponse>("campRecipe", JSON.stringify(req))),
        onProgress: (listener) => {
            listeners.add(listener);
            listener(stage);
            return () => {
                listeners.delete(listener);
            };
        },
        dispose: () => {
            worker.terminate();
            for (const w of waiting.values()) w.reject(new Error("planner closed"));
            waiting.clear();
        },
    };
}
