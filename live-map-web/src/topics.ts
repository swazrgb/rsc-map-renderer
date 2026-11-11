import {useCallback, useMemo, useSyncExternalStore} from "react";

/** Topic arguments; undefined and null values are left out. */
export type TopicArgs = Record<string, string | number | boolean | null | undefined>;

export type Records = ReadonlyMap<string, unknown>;

export interface TopicState<T> {
    data: T | undefined;
    /** Why the last read failed, as `topic → status`; `data` still holds the last good records. */
    error: string | null;
    /** The failed read's HTTP status (0 when it succeeded) and the error text the server gave. */
    status: number;
    reason: string | null;
    /** When `data` last changed (epoch ms), 0 before it arrived. */
    at: number;
}

type Pair = [string, unknown];

interface TopicFrame {
    id: number;
    f?: Pair[];
    u?: Pair[];
    r?: string[];
    o?: string[];
    err?: number;
    msg?: string | null;
}

interface Entry {
    key: string;
    id: number;
    topic: string;
    args: Record<string, string>;
    refs: number;
    active: boolean;
    state: TopicState<Records>;
    listeners: Set<() => void>;
    timer: number | undefined;
    /** The server refused the subscription for now (429); it is asked for again until it is granted. */
    refused: boolean;
    retry: number | undefined;
    retryMs: number;
}

/** How long a released topic stays subscribed, and how long after that its last records are kept. */
const LINGER_MS = 5_000;
const KEEP_MS = 60_000;
/** A subscription the server refused for now (429) is asked for again, waiting twice as long each time. */
const RETRY_FIRST_MS = 1_000;
const RETRY_MAX_MS = 16_000;

const NONE: TopicState<never> = {data: undefined, error: null, status: 0, reason: null, at: 0};

function canonical(args: TopicArgs): string {
    const out: Record<string, string> = {};
    for (const k of Object.keys(args).sort()) {
        const v = args[k];
        if (v != null) out[k] = String(v);
    }
    return JSON.stringify(out);
}

class TopicClient {
    private nextId = 1;
    private readonly byKey = new Map<string, Entry>();
    private readonly byId = new Map<number, Entry>();
    private send: ((msg: string) => void) | null = null;

    attach(send: (msg: string) => void): void {
        this.send = send;
        for (const e of this.byKey.values()) {
            if (e.active && e.refs === 0) this.retire(e);
            else if (e.active) this.subscribe(e);
        }
    }

    detach(): void {
        this.send = null;
    }

    peek(topic: string, argsJson: string): TopicState<Records> {
        return this.byKey.get(`${topic}?${argsJson}`)?.state ?? NONE;
    }

    acquire(topic: string, argsJson: string): Entry {
        const key = `${topic}?${argsJson}`;
        let e = this.byKey.get(key);
        if (!e) {
            e = {
                key,
                id: this.nextId++,
                topic,
                args: JSON.parse(argsJson) as Record<string, string>,
                refs: 0,
                active: false,
                state: NONE,
                listeners: new Set(),
                timer: undefined,
                refused: false,
                retry: undefined,
                retryMs: 0,
            };
            this.byKey.set(key, e);
            this.byId.set(e.id, e);
        }
        if (!e.active || (e.refused && e.retry === undefined)) {
            e.active = true;
            this.subscribe(e);
        }
        window.clearTimeout(e.timer);
        e.timer = undefined;
        e.refs++;
        return e;
    }

    release(e: Entry): void {
        if (--e.refs > 0) return;
        window.clearTimeout(e.retry);
        e.retry = undefined;
        e.timer = window.setTimeout(() => this.retire(e), LINGER_MS);
    }

    /** Unsubscribes an entry nobody holds, keeping its last records a while for a quick return. */
    private retire(e: Entry): void {
        window.clearTimeout(e.timer);
        e.active = false;
        this.send?.(JSON.stringify({op: "unsub", id: e.id}));
        e.timer = window.setTimeout(() => {
            e.timer = undefined;
            this.byKey.delete(e.key);
            this.byId.delete(e.id);
        }, KEEP_MS);
    }

    private retryLater(e: Entry): void {
        for (const other of this.byKey.values()) if (other.active && other.refs === 0) this.retire(other);
        e.retryMs = Math.min(e.retryMs > 0 ? e.retryMs * 2 : RETRY_FIRST_MS, RETRY_MAX_MS);
        window.clearTimeout(e.retry);
        e.retry = window.setTimeout(() => {
            e.retry = undefined;
            if (e.active && e.refs > 0) this.subscribe(e);
        }, e.retryMs);
    }

    /** Asks the server to read a polled topic again now. */
    refresh(topic: string, args: TopicArgs = {}): void {
        const e = this.byKey.get(`${topic}?${canonical(args)}`);
        if (e?.active) this.send?.(JSON.stringify({op: "refresh", id: e.id}));
    }

    frame(d: TopicFrame): void {
        const e = this.byId.get(d.id);
        if (!e) return;
        if (d.err !== undefined) {
            e.state = {...e.state, error: `${e.topic} → ${d.err}`, status: d.err, reason: d.msg ?? null};
            e.refused = d.err === 429;
            if (e.refused && e.refs > 0) this.retryLater(e);
        } else if (d.f) {
            e.refused = false;
            e.retryMs = 0;
            e.state = {data: new Map(d.f), error: null, status: 0, reason: null, at: Date.now()};
        } else if (e.state.data) {
            let next = new Map(e.state.data);
            for (const [k, v] of d.u ?? []) next.set(k, v);
            for (const k of d.r ?? []) next.delete(k);
            if (d.o) {
                const byKey = next;
                next = new Map(d.o.map((k) => [k, byKey.get(k)]));
            }
            e.state = {data: next, error: null, status: 0, reason: null, at: Date.now()};
        } else {
            return;
        }
        for (const l of e.listeners) l();
    }

    private subscribe(e: Entry): void {
        this.send?.(JSON.stringify({op: "sub", id: e.id, topic: e.topic, args: e.args}));
    }
}

/** The map socket's topic subscriptions; `useLiveStream` attaches it to each socket it opens. */
export const topics = new TopicClient();

/**
 * The records of `topic` for `args`, pushed over the map socket as they change; a record that did not change
 * keeps its identity. `null` args subscribe to nothing.
 */
export function useTopic(topic: string, args: TopicArgs | null): TopicState<Records> {
    const argsJson = args == null ? null : canonical(args);
    const subscribe = useCallback(
        (onChange: () => void) => {
            if (argsJson == null) return () => undefined;
            const e = topics.acquire(topic, argsJson);
            e.listeners.add(onChange);
            return () => {
                e.listeners.delete(onChange);
                topics.release(e);
            };
        },
        [topic, argsJson],
    );
    const snapshot = useCallback(() => (argsJson == null ? NONE : topics.peek(topic, argsJson)), [topic, argsJson]);
    return useSyncExternalStore(subscribe, snapshot);
}

function mapped<A, B>(s: TopicState<A>, data: B | undefined): TopicState<B> {
    return {data, error: s.error, status: s.status, reason: s.reason, at: s.at};
}

/** A topic whose records are the rows of a list, in arrival order. */
export function useTopicList<T>(topic: string, args: TopicArgs | null): TopicState<T[]> {
    const s = useTopic(topic, args);
    const list = useMemo(() => (s.data ? ([...s.data.values()] as T[]) : undefined), [s.data]);
    return useMemo(() => mapped(s, list), [s, list]);
}

/** A topic whose records are the parts of one object (`Parts` on the server), put back together. */
export function useTopicObject<T>(topic: string, args: TopicArgs | null): TopicState<T> {
    const s = useTopic(topic, args);
    const value = useMemo(() => (s.data ? (assemble(s.data) as T) : undefined), [s.data]);
    return useMemo(() => mapped(s, value), [s, value]);
}

type Obj = Record<string, unknown>;

function nodeAt(root: Obj, path: string): Obj {
    let node = root;
    for (const step of path.split(".")) {
        let next = node[step];
        if (next == null || typeof next !== "object") {
            next = {};
            node[step] = next;
        }
        node = next as Obj;
    }
    return node;
}

function parentOf(root: Obj, path: string): [Obj, string] {
    const dot = path.lastIndexOf(".");
    return dot < 0 ? [root, path] : [nodeAt(root, path.slice(0, dot)), path.slice(dot + 1)];
}

function isMarker(k: string): boolean {
    return !k.includes("/") && (k.endsWith(".") || k.endsWith("[]") || k.endsWith("{}"));
}

/**
 * The object `Parts` laid out as records: `name` a field, `name[]` with `name[]/key` a list, `name{}` with
 * `name/key` a map, `name.` with `name.field` a nested object.
 */
function assemble(parts: Records): Obj {
    const root: Obj = {};
    const markers = [...parts.keys()].filter(isMarker).sort((a, b) => a.split(".").length - b.split(".").length);
    for (const k of markers) {
        const [parent, name] = parentOf(root, k.endsWith(".") ? k.slice(0, -1) : k.slice(0, -2));
        parent[name] = k.endsWith("[]") ? [] : {};
    }
    for (const [k, v] of parts) {
        if (isMarker(k)) continue;
        const slash = k.indexOf("/");
        if (slash < 0) {
            const [parent, name] = parentOf(root, k);
            parent[name] = v;
            continue;
        }
        const head = k.slice(0, slash);
        if (!head.endsWith("[]")) {
            nodeAt(root, head)[k.slice(slash + 1)] = v;
            continue;
        }
        const [parent, name] = parentOf(root, head.slice(0, -2));
        const list = parent[name];
        if (Array.isArray(list)) list.push(v);
        else parent[name] = [v];
    }
    return root;
}
