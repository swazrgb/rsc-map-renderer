import {useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode, type RefObject} from "react";
import type {NpcSpawnInfo} from "../api";
import type {SearchMarks} from "./searchOverlay";
import {
    FLOOR_NAME,
    newArea,
    npcEntries,
    placeEntries,
    queryKey,
    search,
    type SearchEntry,
    type SearchGroup,
} from "./worldSearch";
import "./search.css";

// How long the text must sit still before it is reported, and the matches before they are outlined:
// outlining a query typed past is wasted, and one letter can outline thousands of tiles.
const REPORT_AFTER_MS = 400;
const OUTLINE_AFTER_MS = 150;

// Every line's height, held to it in search.css.
const LINE_PX = 20;
const OVERSCAN = 12;
// A pointed group lights its entries only up to this many.
const LIGHT_GROUP_MAX = 1000;

const FLOOR_TAG = ["", "F1", "F2", "UG"];

// A list line the cursor can stand on; the cursor holds its `key`, so it keeps its place as the list grows.
type Item =
    | {type: "group"; key: string; group: SearchGroup; open: boolean}
    | {type: "row"; key: string; entry: SearchEntry; area: boolean};

/**
 * `onGo` takes the camera to an entry. `onMarks` receives what the world should show each time it
 * changes. `onTextSettled` and `onExactChange` report the text, once settled, and the exact toggle.
 */
interface Props {
    spawns: readonly NpcSpawnInfo[];
    onGo: (entry: SearchEntry) => void;
    onMarks: (marks: SearchMarks) => void;
    initialText?: string;
    initialExact?: boolean;
    onExactChange?: (exact: boolean) => void;
    onTextSettled?: (text: string) => void;
}

/**
 * A search box over every NPC spawn, transport and placed object. Matches are listed in groups,
 * pointing at a row or group lights it in the world, and clicking a row goes there. Transports and
 * objects are fetched on first focus, or at once when it opens with text.
 */
export function SearchBox({
    spawns,
    onGo,
    onMarks,
    initialText = "",
    onTextSettled,
    initialExact = false,
    onExactChange,
}: Props) {
    const [text, setText] = useState(initialText);
    const [exact, setExact] = useState(initialExact);
    const toggleExact = () => {
        setExact(!exact);
        onExactChange?.(!exact);
    };
    const [wanted, setWanted] = useState(initialText !== "");
    const settledRef = useRef(initialText);
    useEffect(() => {
        if (!onTextSettled || text === settledRef.current) return;
        const t = setTimeout(() => {
            settledRef.current = text;
            onTextSettled(text);
        }, REPORT_AFTER_MS);
        return () => clearTimeout(t);
    }, [text, onTextSettled]);
    const [places, setPlaces] = useState<SearchEntry[] | null>(null);
    useEffect(() => {
        if (!wanted) return;
        let alive = true;
        placeEntries().then(
            (list) => alive && setPlaces(list),
            (e: unknown) => {
                console.warn("[search] places:", e);
                if (alive) setPlaces([]);
            },
        );
        return () => {
            alive = false;
        };
    }, [wanted]);
    const npcs = useMemo(() => npcEntries(spawns), [spawns]);
    const index = useMemo(() => (places ? [...npcs, ...places] : npcs), [npcs, places]);
    const groups = useMemo(() => search(index, text, exact), [index, text, exact]);
    const total = useMemo(() => groups.reduce((n, g) => n + g.entries.length, 0), [groups]);

    // The collapsed groups and the cursor are stamped with the query they belong to, so a new query
    // starts with its own defaults.
    const q = queryKey(text, exact);
    const [collapsed, setCollapsed] = useState<{q: string; keys: ReadonlySet<string>}>({q: "", keys: NO_KEYS});
    const [cursor, setCursor] = useState<{q: string; key: string} | null>(null);
    const shut = collapsed.q === q ? collapsed.keys : NO_KEYS;

    const items = useMemo(() => {
        const out: Item[] = [];
        for (const group of groups) {
            const open = !shut.has(group.key);
            out.push({type: "group", key: group.key, group, open});
            if (!open) continue;
            group.entries.forEach((entry, k) => {
                const area = k > 0 && newArea(group.entries[k - 1], entry);
                out.push({type: "row", key: entry.key, entry, area});
            });
        }
        return out;
    }, [groups, shut]);

    const at = cursor?.q === q ? items.findIndex((item) => item.key === cursor.key) : -1;
    const pointed: Item | null = at >= 0 ? items[at] : null;
    const matches = useMemo(() => groups.flatMap((g) => g.entries), [groups]);
    const lit = useMemo(
        () =>
            pointed?.type === "row"
                ? [pointed.entry]
                : pointed && pointed.group.entries.length <= LIGHT_GROUP_MAX
                  ? pointed.group.entries
                  : NO_ENTRIES,
        [pointed],
    );
    const [settled, setSettled] = useState<readonly SearchEntry[]>(NO_ENTRIES);
    useEffect(() => {
        const t = setTimeout(() => setSettled(matches), OUTLINE_AFTER_MS);
        return () => clearTimeout(t);
    }, [matches]);
    // No outlines until the matches settle, rather than an older query's.
    const outlined = settled === matches ? matches : NO_ENTRIES;
    useEffect(() => onMarks({matches: outlined, lit}), [outlined, lit, onMarks]);

    const listRef = useRef<HTMLDivElement>(null);
    const point = (i: number) => {
        setCursor({q, key: items[i].key});
        const list = listRef.current;
        if (!list) return;
        // By the line's index, since the line may not be drawn yet.
        const top = i * LINE_PX;
        if (top < list.scrollTop) list.scrollTop = top;
        else if (top + LINE_PX > list.scrollTop + list.clientHeight) list.scrollTop = top + LINE_PX - list.clientHeight;
    };
    const activate = (item: Item) => {
        if (item.type === "row") {
            onGo(item.entry);
            return;
        }
        const keys = new Set(shut);
        if (!keys.delete(item.group.key)) keys.add(item.group.key);
        setCollapsed({q, keys});
    };
    const hover = (item: Item) => {
        // On mouse move, not enter: rows sliding under a resting mouse while the keys scroll must not take the cursor.
        if (cursor?.q !== q || cursor.key !== item.key) setCursor({q, key: item.key});
    };
    const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
        if (e.key === "ArrowDown" || e.key === "ArrowUp") {
            e.preventDefault();
            if (items.length === 0) return;
            const step = e.key === "ArrowDown" ? 1 : -1;
            point(at < 0 ? (step > 0 ? 0 : items.length - 1) : (at + step + items.length) % items.length);
        } else if (e.key === "Enter") {
            if (pointed) activate(pointed);
            else if (items.length > 0) activate(items.find((i) => i.type === "row") ?? items[0]);
        } else if (e.key === "Escape") {
            if (text) setText("");
            else e.currentTarget.blur();
        }
    };

    const line = (item: Item, i: number) => {
        const style = {top: i * LINE_PX};
        return item.type === "group" ? (
            <div
                key={item.key}
                style={style}
                className={"wsearch-group" + (i === at ? " pointed" : "")}
                title={item.open ? "collapse" : "expand"}
                onMouseMove={() => hover(item)}
                onClick={() => activate(item)}
            >
                <span className="wsearch-caret">{item.open ? "▾" : "▸"}</span>
                <span className={"wsearch-kind " + item.group.kind}>{item.group.kind}</span>
                <span className="wsearch-title">
                    {item.group.title}
                    {item.group.defId != null && <span className="wsearch-id"> ({item.group.defId})</span>}
                </span>
                <span className="wsearch-count">{item.group.entries.length}</span>
            </div>
        ) : (
            <div
                key={item.key}
                style={style}
                className={"wsearch-row" + (i === at ? " pointed" : "") + (item.area ? " area" : "")}
                title={`${item.entry.detail} — ${FLOOR_NAME[item.entry.floor]}; click to go there`}
                onMouseMove={() => hover(item)}
                onClick={() => activate(item)}
            >
                <span className="wsearch-detail">{item.entry.detail}</span>
                {item.entry.floor > 0 && <span className="wsearch-floor">{FLOOR_TAG[item.entry.floor]}</span>}
                <span className="wsearch-at">
                    {item.entry.x}, {item.entry.z}
                </span>
            </div>
        );
    };

    const query = text.trim();
    return (
        <div className="wsearch">
            <div className="wsearch-field">
                <input
                    type="text"
                    placeholder="Search…"
                    spellCheck={false}
                    value={text}
                    onFocus={() => setWanted(true)}
                    onChange={(e) => setText(e.target.value)}
                    onKeyDown={onKeyDown}
                />
                <button
                    type="button"
                    className={"wsearch-exact" + (exact ? " on" : "")}
                    title={exact ? "matching exact names — click to match parts" : "match exact names only"}
                    aria-pressed={exact}
                    // Keeps focus in the input, so the next Enter is the search's.
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={toggleExact}
                >
                    =
                </button>
                {text && (
                    <button type="button" className="wsearch-clear" title="clear (Esc)" onClick={() => setText("")}>
                        ×
                    </button>
                )}
            </div>
            {query && (
                <div className="wsearch-results" onMouseLeave={() => setCursor(null)}>
                    <div className="wsearch-summary">
                        {total === 0 ? "no matches" : `${total} ${total === 1 ? "match" : "matches"}`}
                        {wanted && !places && <span className="wsearch-loading"> · loading objects…</span>}
                    </div>
                    {/* Remounted per query, so a new query starts at the top. */}
                    <Lines key={q} items={items} listRef={listRef} render={line} />
                </div>
            )}
        </div>
    );
}

// Draws only the lines in view, each placed by its index.
function Lines({
    items,
    listRef,
    render,
}: {
    items: readonly Item[];
    listRef: RefObject<HTMLDivElement | null>;
    render: (item: Item, i: number) => ReactNode;
}) {
    const [scrollTop, setScrollTop] = useState(0);
    const [viewH, setViewH] = useState(() => window.innerHeight);
    useEffect(() => {
        const resized = () => setViewH(window.innerHeight);
        window.addEventListener("resize", resized);
        return () => window.removeEventListener("resize", resized);
    }, []);
    const first = Math.max(0, Math.floor(scrollTop / LINE_PX) - OVERSCAN);
    const last = Math.min(items.length, Math.ceil((scrollTop + viewH) / LINE_PX) + OVERSCAN);
    return (
        <div className="wsearch-list" ref={listRef} onScroll={(e) => setScrollTop(e.currentTarget.scrollTop)}>
            <div style={{position: "relative", height: items.length * LINE_PX}}>
                {items.slice(first, last).map((item, k) => render(item, first + k))}
            </div>
        </div>
    );
}

const NO_KEYS: ReadonlySet<string> = new Set();
const NO_ENTRIES: readonly SearchEntry[] = [];
