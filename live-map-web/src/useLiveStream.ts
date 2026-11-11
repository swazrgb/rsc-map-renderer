import {type SetStateAction, useCallback, useEffect, useLayoutEffect, useRef, useState} from "react";
import type {BotLive} from "./api";
import type {BotInterest} from "./botInterest";
import {setServerSkew} from "./clock";
import {entry} from "./lookup";
import {topics} from "./topics";
import {applyWorldDelta, EMPTY_WORLD, emptyPoolMaps, type WorldDeltaWire, type WorldPools} from "./worldPools";

export type Connection = "idle" | "connecting" | "live" | "error";

export type BotMap = Record<string, BotLive>;

export interface LiveStreamOptions {
    /** Every event the socket carries, before the stream applies the ones it knows. */
    onEvent?: (event: string, data: unknown) => void;
    /** The socket closed or was parked: whatever the page holds of the stream's moment is over. */
    onDisconnect?: () => void;
}

export interface LiveStream {
    bots: BotMap;
    /** Changes the bots as the stream's own frames do, for a page's own events that carry bots. */
    landBots: (update: SetStateAction<BotMap>) => void;
    connection: Connection;
    world: WorldPools;
    /** When the bots last changed, in this browser's epoch ms. */
    batchAtMs: number;
    /** How far the server's clock is ahead of this browser's, in ms. */
    clockSkewMs: number;
    /** Asks for `bot`'s and the watched bots' detail fields, and the listed bots' first line. */
    watch: (bot: string | null, interest: BotInterest) => void;
    /** Sends an op over the socket, or once it opens. */
    send: (op: object) => void;
}

const RETRY_MIN_MS = 1_000;
const RETRY_MAX_MS = 10_000;

const PARK_HIDDEN_MS = 60_000;

interface BotDeltaWire {
    f?: BotLive[];
    d?: (Partial<Record<keyof BotLive, unknown>> & {username: string})[];
}

const DETAIL_FIELDS = ["description", "dialog", "fatiguePercent", "windows"] as const;
const LISTED_FIELDS = ["doing"] as const;
const DETAIL_ENTRY: readonly string[] = [...DETAIL_FIELDS, "serverTick"];
const LISTED_ENTRY: readonly string[] = LISTED_FIELDS;

/** {@code b} as the shared stream sends it, keeping the detail fields only bx frames carry. */
function withDetailOf(b: BotLive, had: BotLive | undefined): BotLive {
    if (!had) return b;
    const out: Record<string, unknown> = {...b};
    for (const f of [...DETAIL_FIELDS, ...LISTED_FIELDS]) {
        if (had[f] != null) out[f] = had[f];
    }
    return out as unknown as BotLive;
}

function withoutFields(b: BotLive, fields: readonly (keyof BotLive)[]): BotLive {
    const out: Partial<BotLive> = {...b};
    for (const f of fields) Reflect.deleteProperty(out, f);
    return out as BotLive;
}

/**
 * The live map's socket (`/api/ws`): every bot the viewer may see, the world the bots see, and the topics the page
 * subscribes to. It reconnects when the socket drops, parks a minute after the page is hidden, and reconnects when
 * `reconnectKey` changes.
 */
export function useLiveStream(reconnectKey?: unknown, options: LiveStreamOptions = {}): LiveStream {
    const [bots, setBots] = useState<BotMap>({});
    const poolMapsRef = useRef(emptyPoolMaps());
    const [world, setWorld] = useState<WorldPools>(EMPTY_WORLD);
    const [clockSkewMs, setClockSkewMs] = useState(0);
    const [connection, setConnection] = useState<Connection>("connecting");
    const [batchAtMs, setBatchAtMs] = useState(() => Date.now());
    const wsRef = useRef<WebSocket | null>(null);
    const pendingOpsRef = useRef<string[]>([]);
    const optionsRef = useRef(options);
    useLayoutEffect(() => {
        optionsRef.current = options;
    });

    const send = useCallback((op: object) => {
        const msg = JSON.stringify(op);
        const ws = wsRef.current;
        if (!ws) return;
        if (ws.readyState === WebSocket.OPEN) ws.send(msg);
        else if (ws.readyState === WebSocket.CONNECTING) pendingOpsRef.current.push(msg);
    }, []);

    const landBots = useCallback((update: SetStateAction<BotMap>) => {
        setBots(update);
        setBatchAtMs(Date.now());
    }, []);

    const shownRef = useRef<{watched: Set<string>; listed: Set<string>}>({watched: new Set(), listed: new Set()});
    const watch = useCallback(
        (bot: string | null, interest: BotInterest) => {
            const watched = new Set(interest.watched);
            if (bot) watched.add(bot);
            const listed = new Set(interest.listed.filter((u) => !watched.has(u)));
            const was = shownRef.current;
            shownRef.current = {watched, listed};
            const unwatched = [...was.watched].filter((u) => !watched.has(u));
            const unlisted = [...was.listed].filter((u) => !listed.has(u));
            if (unwatched.length > 0 || unlisted.length > 0) {
                setBots((prev) => {
                    const next = {...prev};
                    for (const u of unwatched) {
                        const b = entry(next, u);
                        if (b) next[u] = withoutFields(b, DETAIL_FIELDS);
                    }
                    for (const u of unlisted) {
                        const b = entry(next, u);
                        if (b) next[u] = withoutFields(b, LISTED_FIELDS);
                    }
                    return next;
                });
            }
            send({op: "watch", bot, bots: [...watched], lines: [...listed]});
        },
        [send],
    );

    const connGenRef = useRef(0);
    const [connectedFor, setConnectedFor] = useState(reconnectKey);
    if (connectedFor !== reconnectKey) {
        setConnectedFor(reconnectKey);
        setConnection("connecting");
    }

    useLayoutEffect(() => {
        connGenRef.current++;
    }, [reconnectKey]);

    useEffect(() => {
        let disposed = false;
        let retryTimer: number | undefined;
        let retryMs = RETRY_MIN_MS;

        const handle = (event: string, d: unknown) => {
            optionsRef.current.onEvent?.(event, d);
            switch (event) {
                case "snapshot": {
                    const list = d as BotLive[];
                    const next: BotMap = {};
                    for (const b of list) next[b.username] = b;
                    landBots(next);
                    setConnection("live");
                    break;
                }
                case "bd": {
                    const {f = [], d: parts = []} = d as BotDeltaWire;
                    if (f.length === 0 && parts.length === 0) return;
                    landBots((prev) => {
                        const next = {...prev};
                        for (const b of f) next[b.username] = withDetailOf(b, entry(prev, b.username));
                        for (const p of parts) {
                            const merged: Record<string, unknown> = {...entry(next, p.username)};
                            for (const [k, v] of Object.entries(p)) {
                                if (v === null) Reflect.deleteProperty(merged, k);
                                else merged[k] = v;
                            }
                            next[p.username] = merged as unknown as BotLive;
                        }
                        return next;
                    });
                    setConnection("live");
                    break;
                }
                case "bx": {
                    const entries = d as BotDeltaWire["d"];
                    if (!entries || entries.length === 0) return;
                    landBots((prev) => {
                        const next = {...prev};
                        const {watched, listed} = shownRef.current;
                        for (const p of entries) {
                            const b = entry(next, p.username);
                            if (!b) continue;
                            const merged: Record<string, unknown> = {...b};
                            const fields = watched.has(p.username)
                                ? DETAIL_ENTRY
                                : listed.has(p.username)
                                  ? LISTED_ENTRY
                                  : [];
                            for (const [k, v] of Object.entries(p)) {
                                if (!fields.includes(k)) continue;
                                if (v === null) Reflect.deleteProperty(merged, k);
                                else merged[k] = v;
                            }
                            next[p.username] = merged as unknown as BotLive;
                        }
                        return next;
                    });
                    break;
                }
                case "batch": {
                    const list = d as BotLive[];
                    if (list.length === 0) return;
                    landBots((prev) => {
                        const next = {...prev};
                        for (const b of list) {
                            const old = entry(next, b.username);
                            next[b.username] = {
                                ...b,
                                npcs: b.npcs ?? old?.npcs,
                                players: b.players ?? old?.players,
                                objects: b.objects ?? old?.objects,
                                wallObjects: b.wallObjects ?? old?.wallObjects,
                                groundItems: b.groundItems ?? old?.groundItems,
                            };
                        }
                        return next;
                    });
                    setConnection("live");
                    break;
                }
                case "bgone": {
                    const names = d as string[];
                    landBots((prev) => {
                        const gone = new Set(names);
                        return Object.fromEntries(Object.entries(prev).filter(([n]) => !gone.has(n)));
                    });
                    break;
                }
                case "serverTime":
                    setClockSkewMs((d as number) - Date.now());
                    setServerSkew((d as number) - Date.now());
                    break;
                case "t":
                    topics.frame(d as Parameters<typeof topics.frame>[0]);
                    break;
                case "world": {
                    const maps = poolMapsRef.current;
                    const touched = new Set(applyWorldDelta(maps, d as WorldDeltaWire));
                    if (touched.size === 0) return;
                    setWorld((prev) => ({
                        npcs: touched.has("npcs") ? [...maps.npcs.entities.values()] : prev.npcs,
                        players: touched.has("players") ? [...maps.players.entities.values()] : prev.players,
                        objects: touched.has("objects") ? [...maps.objects.entities.values()] : prev.objects,
                        wallObjects: touched.has("wallObjects")
                            ? [...maps.wallObjects.entities.values()]
                            : prev.wallObjects,
                        groundItems: touched.has("groundItems")
                            ? [...maps.groundItems.entities.values()]
                            : prev.groundItems,
                    }));
                    break;
                }
                default:
                    break;
            }
        };

        const disconnected = () => {
            wsRef.current = null;
            pendingOpsRef.current = [];
            topics.detach();
            optionsRef.current.onDisconnect?.();
        };

        let parked = false;
        let parkTimer: number | undefined;

        const connect = () => {
            if (disposed || parked) return;
            const proto = location.protocol === "https:" ? "wss:" : "ws:";
            const ws = new WebSocket(`${proto}//${location.host}/api/ws`);
            const gen = connGenRef.current;
            wsRef.current = ws;
            ws.onopen = () => {
                if (wsRef.current !== ws) return;
                retryMs = RETRY_MIN_MS;
                for (const m of pendingOpsRef.current) ws.send(m);
                pendingOpsRef.current = [];
                topics.attach((m) => {
                    ws.send(m);
                });
            };
            ws.onmessage = (ev: MessageEvent<string>) => {
                if (wsRef.current !== ws || connGenRef.current !== gen) return;
                try {
                    const {e, d} = JSON.parse(ev.data) as {e: string; d: unknown};
                    handle(e, d);
                } catch {
                    // Ignore malformed payloads — next event brings a fresh one.
                }
            };
            ws.onclose = () => {
                if (disposed || wsRef.current !== ws) return;
                disconnected();
                setConnection("error");
                retryTimer = window.setTimeout(connect, retryMs);
                retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
            };
        };
        const park = () => {
            parkTimer = undefined;
            if (!document.hidden) return;
            parked = true;
            if (retryTimer !== undefined) window.clearTimeout(retryTimer);
            const ws = wsRef.current;
            disconnected();
            ws?.close();
            setConnection("idle");
        };
        const onVisibility = () => {
            if (document.hidden) {
                parkTimer ??= window.setTimeout(park, PARK_HIDDEN_MS);
                return;
            }
            if (parkTimer !== undefined) {
                window.clearTimeout(parkTimer);
                parkTimer = undefined;
            }
            if (parked) {
                parked = false;
                retryMs = RETRY_MIN_MS;
                setConnection("connecting");
                connect();
            }
        };
        document.addEventListener("visibilitychange", onVisibility);
        if (document.hidden) parkTimer = window.setTimeout(park, PARK_HIDDEN_MS);
        connect();

        return () => {
            disposed = true;
            document.removeEventListener("visibilitychange", onVisibility);
            if (parkTimer !== undefined) window.clearTimeout(parkTimer);
            if (retryTimer !== undefined) window.clearTimeout(retryTimer);
            const ws = wsRef.current;
            wsRef.current = null;
            pendingOpsRef.current = [];
            topics.detach();
            ws?.close();
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [reconnectKey]);

    return {bots, landBots, connection, world, batchAtMs, clockSkewMs, watch, send};
}
