import {
    useCallback,
    useEffect,
    useId,
    useLayoutEffect,
    useRef,
    useState,
    useSyncExternalStore,
    type CSSProperties,
    type ReactNode,
} from "react";
import {createPortal} from "react-dom";
import {useStoredState} from "./useStoredState";
import "./floatingWindow.css";

/** Where a window opens until moved: pixel insets from the edges of the element it is mounted in. */
export interface WindowAnchor {
    top?: number;
    right?: number;
    bottom?: number;
    left?: number;
}

/**
 * `id` keys the remembered position and size in localStorage, so windows given one id share them.
 * `anchor` defaults to 8px in from the top left. `actions` go in the title bar before its buttons.
 * A change of `raiseOn` brings the window to the front.
 */
export interface FloatingWindowProps {
    id: string;
    title: ReactNode;
    children: ReactNode;
    onClose?: () => void;
    anchor?: WindowAnchor;
    resizable?: boolean;
    className?: string;
    actions?: ReactNode;
    raiseOn?: unknown;
}

interface Placement {
    pos?: {x: number; y: number};
    size?: {w: number; h: number};
}

interface Placed {
    pos: {x: number; y: number};
    size: {w: number; h: number};
}

// Every open window in one front-to-back order, last on top. z-indexes are ranks above this base,
// below the popups that portal to the same root at 2000 and up.
const Z_BASE = 1200;
let stack: string[] = [];
const listeners = new Set<() => void>();

function publish(next: string[]) {
    stack = next;
    for (const l of listeners) l();
}

function raise(key: string) {
    if (stack[stack.length - 1] === key) return;
    publish([...stack.filter((k) => k !== key), key]);
}

function subscribe(l: () => void) {
    listeners.add(l);
    return () => listeners.delete(l);
}

const getStack = () => stack;

// Pixels from a screen edge within which a dropped window snaps flush to it.
const SNAP = 10;
// The smallest resize, unless the window's CSS min-width or min-height asks for more.
const MIN_W = 180;
const MIN_H = 90;

/**
 * A movable window: dragged by its title bar, raised by any click in it, rolled up from the title bar,
 * resized from its corner when `resizable`, closed by its button or Escape. Portalled to `<body>`, so
 * all windows share one stacking context. Until moved it sits at its anchor and follows its host's
 * resizes; it is always kept on screen, its title bar at least.
 */
export function FloatingWindow({
    id,
    title,
    children,
    onClose,
    anchor,
    resizable,
    className,
    actions,
    raiseOn,
}: FloatingWindowProps) {
    const instanceId = useId();
    const titleId = `${instanceId}-title`;
    const [stored, setStored] = useStoredState<Placement>(`window.${id}`, {});
    const markerRef = useRef<HTMLSpanElement>(null);
    const rootRef = useRef<HTMLDivElement>(null);
    // A gesture's placement, stored once on release rather than on every move; the ref is for a release
    // that runs before the last move has rendered.
    const [live, setLive] = useState<Placement | null>(null);
    const liveRef = useRef<Placement | null>(null);
    const [collapsed, setCollapsed] = useState(false);
    const gestureRef = useRef<{
        kind: "move" | "size";
        dx: number;
        dy: number;
        start: Placed;
        minW: number;
        minH: number;
    } | null>(null);
    const [measure, setMeasure] = useState({w: 0, h: 0, host: null as DOMRect | null, vw: 0, vh: 0});

    const order = useSyncExternalStore(subscribe, getStack);
    const rank = order.indexOf(instanceId);
    const active = rank >= 0 && rank === order.length - 1;

    useLayoutEffect(() => {
        raise(instanceId);
        return () => publish(stack.filter((k) => k !== instanceId));
    }, [instanceId]);
    useLayoutEffect(() => raise(instanceId), [instanceId, raiseOn]);

    useLayoutEffect(() => {
        const el = rootRef.current;
        const host = markerRef.current?.parentElement ?? null;
        const remeasure = () =>
            setMeasure((m) => {
                const w = el?.offsetWidth ?? 0;
                const h = el?.offsetHeight ?? 0;
                const box = host?.getBoundingClientRect() ?? null;
                const vw = window.innerWidth;
                const vh = window.innerHeight;
                const same = m.w === w && m.h === h && m.vw === vw && m.vh === vh && sameRect(m.host, box);
                return same ? m : {w, h, host: box, vw, vh};
            });
        remeasure();
        const ro = new ResizeObserver(remeasure);
        if (el) ro.observe(el);
        if (host) ro.observe(host);
        window.addEventListener("resize", remeasure);
        return () => {
            ro.disconnect();
            window.removeEventListener("resize", remeasure);
        };
    }, []);

    const place = live ?? stored;
    const pos = clamp(place.pos ?? anchored(anchor, measure), measure);

    useEffect(() => {
        const el = rootRef.current;
        if (!el || !onClose) return;
        // A native listener: React would bubble an Escape from a popup this window opened, meant only to
        // close the popup. Text fields keep their own Escape.
        const onKey = (e: KeyboardEvent) => {
            if (e.key !== "Escape" || e.defaultPrevented) return;
            const t = e.target as HTMLElement;
            if (t.closest("input, textarea, select, [contenteditable=true]")) return;
            e.preventDefault();
            onClose();
        };
        el.addEventListener("keydown", onKey);
        return () => el.removeEventListener("keydown", onKey);
    }, [onClose]);

    const startGesture = (kind: "move" | "size", e: React.PointerEvent) => {
        if (e.button !== 0) return;
        const el = rootRef.current;
        if (!el) return;
        const start: Placed = {pos, size: stored.size ?? {w: el.offsetWidth, h: el.offsetHeight}};
        const css = getComputedStyle(el);
        const minW = Math.max(MIN_W, parseFloat(css.minWidth) || 0);
        const minH = Math.max(MIN_H, parseFloat(css.minHeight) || 0);
        gestureRef.current =
            kind === "move"
                ? {kind, dx: e.clientX - pos.x, dy: e.clientY - pos.y, start, minW, minH}
                : {kind, dx: e.clientX - start.size.w, dy: e.clientY - start.size.h, start, minW, minH};
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        e.preventDefault();
    };
    const update = (p: Placement) => {
        liveRef.current = p;
        setLive(p);
    };
    const onGestureMove = (e: React.PointerEvent) => {
        const g = gestureRef.current;
        if (!g) return;
        if (g.kind === "move") {
            const x = e.clientX - g.dx;
            const y = e.clientY - g.dy;
            update({
                ...g.start,
                size: stored.size,
                pos: snap({x, y}, measure),
            });
        } else {
            const vw = window.innerWidth;
            const vh = window.innerHeight;
            const w = Math.round(Math.max(g.minW, Math.min(vw, e.clientX - g.dx)));
            const h = Math.round(Math.max(g.minH, Math.min(vh, e.clientY - g.dy)));
            const at = g.start.pos;
            update({
                ...g.start,
                // Growing past the screen's edge pushes the window back from it.
                pos: {x: Math.max(0, Math.min(at.x, vw - w)), y: Math.max(0, Math.min(at.y, vh - h))},
                size: {w, h},
            });
        }
    };
    const endGesture = () => {
        if (!gestureRef.current) return;
        gestureRef.current = null;
        if (liveRef.current) setStored(liveRef.current);
        liveRef.current = null;
        setLive(null);
    };

    const onTitleDoubleClick = (e: React.MouseEvent) => {
        if ((e.target as HTMLElement).closest("button, input, select")) return;
        setCollapsed(!collapsed);
    };

    const onFocusIn = useCallback(() => raise(instanceId), [instanceId]);

    const style: CSSProperties = {
        left: pos.x,
        top: pos.y,
        zIndex: Z_BASE + Math.max(0, rank),
        // Hidden until first measured, so an anchored window does not flash at the host's top left.
        visibility: measure.vw === 0 ? "hidden" : undefined,
    };
    const size = place.size;
    if (size && resizable) {
        style.width = size.w;
        // A size stored on a bigger screen must not put the grip out of reach.
        style.maxWidth = "100vw";
        if (!collapsed) {
            style.height = size.h;
            style.maxHeight = "100vh";
        }
    }

    return (
        <>
            <span ref={markerRef} hidden />
            {createPortal(
                <div
                    ref={rootRef}
                    role="dialog"
                    aria-labelledby={titleId}
                    // Focusable, so Escape reaches the window clicked into.
                    tabIndex={-1}
                    className={
                        "fw" +
                        (active ? " fw-active" : "") +
                        (collapsed ? " fw-collapsed" : "") +
                        (live ? " fw-gesture" : "") +
                        (className ? " " + className : "")
                    }
                    style={style}
                    onPointerDownCapture={onFocusIn}
                    onFocusCapture={onFocusIn}
                >
                    <header
                        className="fw-head"
                        onPointerDown={(e) => {
                            if ((e.target as HTMLElement).closest("button, input, select, a")) return;
                            rootRef.current?.focus({preventScroll: true});
                            startGesture("move", e);
                        }}
                        onPointerMove={onGestureMove}
                        onPointerUp={endGesture}
                        onPointerCancel={endGesture}
                        onLostPointerCapture={endGesture}
                        onDoubleClick={onTitleDoubleClick}
                    >
                        <span className="fw-title" id={titleId}>
                            {title}
                        </span>
                        {actions}
                        <button
                            type="button"
                            className="fw-btn"
                            title={collapsed ? "unroll" : "roll up (or double-click the title)"}
                            aria-expanded={!collapsed}
                            onClick={() => setCollapsed(!collapsed)}
                        >
                            {collapsed ? "▸" : "▾"}
                        </button>
                        {onClose && (
                            <button type="button" className="fw-btn fw-close" title="close (Esc)" onClick={onClose}>
                                ×
                            </button>
                        )}
                    </header>
                    {/* Kept mounted while rolled up, so its contents' state survives. */}
                    <div className="fw-body" hidden={collapsed}>
                        {children}
                    </div>
                    {resizable && !collapsed && (
                        <div
                            className="fw-grip"
                            title="drag to resize · double-click to reset"
                            onPointerDown={(e) => startGesture("size", e)}
                            onPointerMove={onGestureMove}
                            onPointerUp={endGesture}
                            onPointerCancel={endGesture}
                            onLostPointerCapture={endGesture}
                            onDoubleClick={() => setStored((s) => ({...s, size: undefined}))}
                        />
                    )}
                </div>,
                document.body,
            )}
        </>
    );
}

interface Measure {
    w: number;
    h: number;
    host: DOMRect | null;
    vw: number;
    vh: number;
}

function sameRect(a: DOMRect | null, b: DOMRect | null) {
    if (a == null || b == null) return a === b;
    return a.left === b.left && a.top === b.top && a.width === b.width && a.height === b.height;
}

function anchored(a: WindowAnchor | undefined, m: Measure): {x: number; y: number} {
    const box = m.host ?? {left: 0, top: 0, right: m.vw, bottom: m.vh};
    const x = a?.right != null ? box.right - a.right - m.w : box.left + (a?.left ?? 8);
    const y = a?.bottom != null ? box.bottom - a.bottom - m.h : box.top + (a?.top ?? 8);
    return {x, y};
}

function clamp(p: {x: number; y: number}, m: Measure): {x: number; y: number} {
    if (m.vw === 0) return p;
    return {
        x: Math.round(Math.max(0, Math.min(m.vw - m.w, p.x))),
        y: Math.round(Math.max(0, Math.min(m.vh - m.h, p.y))),
    };
}

function snap(p: {x: number; y: number}, m: Measure): {x: number; y: number} {
    let {x, y} = p;
    if (Math.abs(x) < SNAP) x = 0;
    if (Math.abs(m.vw - (x + m.w)) < SNAP) x = m.vw - m.w;
    if (Math.abs(y) < SNAP) y = 0;
    if (Math.abs(m.vh - (y + m.h)) < SNAP) y = m.vh - m.h;
    return clamp({x, y}, m);
}
