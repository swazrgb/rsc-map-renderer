import type {EnginePlanStep, InteractAction, NavPolicy, PlanLoadoutInput} from "@world3d";
import type {BankMove, DuelMove, TradeMove} from "../api";

/** What the proxy's map may offer: the browser game client on a session. */
export interface ProxyFeatures {
    client: boolean;
}

/** The `control` topic: what the map has set on a bot's session. */
export interface ControlState {
    /** Every client's input but the map's is ignored. */
    paused: boolean;
    /** The tile the map's walk is heading for. */
    walkingTo?: {x: number; z: number};
    /**
     * The route the map's walk last planned: from the tile it planned on, the engine's own route, and the tiles of the
     * path it is walking.
     */
    walkRoute?: {from: {x: number; z: number}; plan: EnginePlanStep[]; path: {x: number; z: number}[]};
    /** How the last walk ended, until the next one starts. */
    walkNote?: string;
    /** The options of the conversation's open menu. */
    menu?: string[];
}

export async function fetchFeatures(): Promise<ProxyFeatures> {
    const r = await fetch("/api/proxy");
    if (!r.ok) throw new Error(`/api/proxy → ${r.status}`);
    return (await r.json()) as ProxyFeatures;
}

// The proxy takes a write only as JSON, which no other site's page can send it unasked.
async function play(method: "POST" | "DELETE", path: string, body?: unknown): Promise<void> {
    const r = await fetch(path, {
        method,
        headers: {"Content-Type": "application/json"},
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!r.ok) throw new Error((await r.text()) || `${path} → ${r.status}`);
}

function botPath(user: string, verb: string): string {
    return `/api/bots/${encodeURIComponent(user)}/${verb}`;
}

/**
 * Walks the bot to the tile, its z counting the floors below it, under `policy` and arriving with `loadout`; a null
 * one is the bot's default routing, or whatever it carries.
 */
export function walkBot(
    user: string,
    tile: {x: number; z: number},
    policy: NavPolicy | null,
    loadout: PlanLoadoutInput | null,
): Promise<void> {
    return play("POST", `${botPath(user, "walk")}?x=${tile.x}&z=${tile.z}`, {policy, loadout});
}

/**
 * Takes an entry of the game's menus for the bot; the walk to its target, when it needs one, plans under `policy`, a
 * null one being the bot's default routing.
 */
export function actBot(
    user: string,
    action: InteractAction,
    target: {x: number; z: number; id?: number; dir?: number; item?: number; noted?: boolean},
    policy: NavPolicy | null,
): Promise<void> {
    const q = new URLSearchParams({
        action,
        x: String(target.x),
        z: String(target.z),
        id: String(target.id ?? 0),
        dir: String(target.dir ?? 0),
        item: String(target.item ?? 0),
        noted: String(target.noted ?? false),
    });
    return play("POST", `${botPath(user, "interact")}?${q.toString()}`, {policy});
}

/** Says `message` as the bot: in public chat, or privately to `to`. */
export function chatBot(user: string, to: string | null, message: string): Promise<void> {
    return play("POST", botPath(user, "chat"), {to, message});
}

/** Makes a move at the bot's open bank. */
export function bankMove(user: string, move: BankMove): Promise<void> {
    return play("POST", botPath(user, "bank"), {item: 0, amount: 0, noted: false, slot: 0, to: 0, ...move});
}

/** Replaces the bot's side of its open trade, or takes one of the trade screens' buttons. */
export function tradeMove(user: string, move: TradeMove): Promise<void> {
    return play("POST", botPath(user, "trade"), move);
}

/** Replaces the bot's stake in its open duel, sets the duel's rules, or takes one of the duel screens' buttons. */
export function duelMove(user: string, move: DuelMove): Promise<void> {
    return play("POST", botPath(user, "duel"), move);
}

export function stopWalk(user: string): Promise<void> {
    return play("DELETE", botPath(user, "walk"));
}

/** Ignores every client's input but the map's, or takes it again. */
export function pauseClients(user: string, on: boolean): Promise<void> {
    return play(on ? "POST" : "DELETE", botPath(user, "pause"));
}

/** Opens the browser game client on the bot, in a window of its own: one per bot. */
export function openClient(user: string): void {
    const socket = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/api/client`;
    const q = new URLSearchParams({server: socket, user});
    window.open(`/client/index.html?${q}`, `client-${user}`, "popup=yes,width=512,height=346");
}
