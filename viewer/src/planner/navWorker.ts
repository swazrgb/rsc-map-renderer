/// <reference lib="webworker" />
import {asset} from "../api";

/** The exports of nav-web, nav's planner compiled to WebAssembly by TeaVM. */
interface NavExports {
    main(args: string[]): void;
    loadWorld(gzippedSnapshot: Int8Array): string;
    buildPathfinder(): string;
    itemNames(): string;
    wearableItems(): string;
    edibleItems(): string;
    inputs(): string;
    plan(json: string): string;
    preview(json: string): string;
    account(json: string): string;
    reachable(json: string): string;
    serverWorld(): string;
    stepMap(): Int8Array | null;
    walkStart(json: string): string;
    walkTick(x: number, z: number, inCombat: boolean, npcs: Int32Array): string;
    serverTransports(): string;
    campRecipe(json: string): string;
    campSlots(json: string): string;
}

/**
 * One request to the worker. `world` downloads and reads the world snapshot and must come first;
 * `walkStart` starts a simulated walk and each `walkTick` advances it one tick from `view`.
 */
export type NavOp =
    | "world"
    | "pathfinder"
    | "items"
    | "inputs"
    | "plan"
    | "preview"
    | "account"
    | "wearables"
    | "edibles"
    | "reachable"
    | "serverWorld"
    | "serverTransports"
    | "walkStart"
    | "walkTick"
    | "campRecipe"
    | "campSlots";

/** `baked` is the world3d manifest's stamp, sent with `world`; `json` is a serialized request. */
export interface NavRequest {
    id: number;
    op: NavOp;
    json?: string;
    baked?: number;
    view?: {x: number; z: number; inCombat: boolean; npcs: Int32Array};
}

export type NavReply = {id: number; ok: true; value: unknown} | {id: number; ok: false; error: string};

/** A load stage, posted as it starts; `at` is on the worker's clock. */
export interface NavProgress {
    progress: string;
    at: number;
}

let baked = 0;
// The engine and the snapshot must come from the same bake, but their file names never change:
// the bake stamp makes each bake's URLs distinct.
const url = (path: string) => `${new URL(asset(path), self.location.href).href}?v=${baked}`;

const download = async (path: string): Promise<ArrayBuffer> => {
    const r = await fetch(url(path));
    if (!r.ok) throw new Error(`${path} → ${r.status}`);
    return r.arrayBuffer();
};

let nav: Promise<NavExports> | null = null;

interface TeaVmRuntime {
    load(wasm: ArrayBuffer): Promise<{exports: unknown}>;
}

function module(): Promise<NavExports> {
    nav ??= (async () => {
        const runtime = (await import(/* @vite-ignore */ url("/api/nav/wasm-gc-module-runtime.js"))) as TeaVmRuntime;
        const teavm = await runtime.load(await download("/api/nav/nav.wasm"));
        const x = teavm.exports as NavExports;
        x.main([]);
        return x;
    })();
    return nav;
}

/** A module answer: JSON, or `{"error": …}` when the Java side threw, which is rethrown here. */
function parsed(text: string): unknown {
    const value: unknown = JSON.parse(text);
    if (value && typeof value === "object" && "error" in value && Object.keys(value).length === 1) {
        throw new Error(String(value.error));
    }
    return value;
}

async function answer(req: NavRequest): Promise<unknown> {
    if (req.op === "world") {
        baked = req.baked ?? 0;
        const t0 = performance.now();
        self.postMessage({
            progress: "downloading the pathfinder and the world",
            at: performance.now(),
        } satisfies NavProgress);
        const [x, snapshot] = await Promise.all([module(), download("/api/nav/world.snap.gz")]);
        const t1 = performance.now();
        const info = parsed(x.loadWorld(new Int8Array(snapshot))) as Record<string, unknown>;
        return {
            ...info,
            fetchMs: Math.round(t1 - t0),
            totalMs: Math.round(performance.now() - t0),
            doneAt: performance.now(),
        };
    }
    const x = await module();
    switch (req.op) {
        case "pathfinder": {
            const t0 = performance.now();
            const info = parsed(x.buildPathfinder()) as Record<string, unknown>;
            return {...info, doneAt: performance.now(), totalMs: Math.round(performance.now() - t0)};
        }
        case "items":
            return parsed(x.itemNames());
        case "wearables":
            return parsed(x.wearableItems());
        case "edibles":
            return parsed(x.edibleItems());
        case "inputs":
            return parsed(x.inputs());
        case "plan":
            return parsed(x.plan(req.json ?? "{}"));
        case "preview":
            return parsed(x.preview(req.json ?? "{}"));
        case "account":
            return parsed(x.account(req.json ?? "{}"));
        case "reachable":
            return parsed(x.reachable(req.json ?? "{}"));
        case "serverWorld": {
            const world = parsed(x.serverWorld());
            const steps = x.stepMap();
            if (!steps) throw new Error("no world loaded");
            return {world, steps};
        }
        case "serverTransports":
            return parsed(x.serverTransports());
        case "walkStart":
            return parsed(x.walkStart(req.json ?? "{}"));
        case "campRecipe":
            return parsed(x.campRecipe(req.json ?? "{}"));
        case "campSlots":
            return parsed(x.campSlots(req.json ?? "{}"));
        case "walkTick": {
            const v = req.view;
            if (!v) throw new Error("walkTick without a view");
            return parsed(x.walkTick(v.x, v.z, v.inCombat, v.npcs));
        }
    }
}

// Requests run one at a time in arrival order, so none reaches a module whose world is still loading.
let queue: Promise<unknown> = Promise.resolve();

self.onmessage = (e: MessageEvent<NavRequest>) => {
    const req = e.data;
    queue = queue
        .then(() => answer(req))
        .then(
            (value) =>
                self.postMessage({id: req.id, ok: true, value} satisfies NavReply, {
                    // The walk graph is megabytes: handed over, not copied.
                    transfer:
                        req.op === "serverWorld" ? [(value as {steps: Int8Array}).steps.buffer as ArrayBuffer] : [],
                }),
            (err: unknown) =>
                self.postMessage({
                    id: req.id,
                    ok: false,
                    error: err instanceof Error ? err.message : String(err),
                } satisfies NavReply),
        );
};
