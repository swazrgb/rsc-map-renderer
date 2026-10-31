import {itemEntries, searchItems} from "../search/itemSearch";
import {
    autoUpdate,
    flip,
    FloatingPortal,
    offset,
    safePolygon,
    shift,
    useDismiss,
    useFloating,
    useFocus,
    useHover,
    useInteractions,
    useRole,
} from "@floating-ui/react";
import {Autocomplete} from "@base-ui/react/autocomplete";
import {useMemo, useRef, useState, type ReactNode} from "react";
import type {ItemNames, PlanLoadoutInput, PlannerInputs, PlannerOpening, PlanWant} from "./plannerApi";
import "./planner.css";

/** A row of the items table: one item, by its exact id. */
export interface PlannerRow {
    kind: "item";
    id: number;
}

/** The key per-row amounts are stored under. */
export function rowKey(row: PlannerRow): string {
    return `i${row.id}`;
}

/** The item rows of `rows`, dropping anything else a share or a stored loadout may hold. */
export function itemRows(rows: unknown): PlannerRow[] {
    if (!Array.isArray(rows)) return [];
    return rows.filter(
        (r): r is PlannerRow =>
            typeof r === "object" &&
            r !== null &&
            (r as {kind?: unknown}).kind === "item" &&
            typeof (r as {id?: unknown}).id === "number",
    );
}

/**
 * What a row's info tip says: the gate families the item counts toward, and the counts at which gates
 * want it carried or refuse it. Null for an item no gate reads.
 */
function rowInfo(
    row: PlannerRow,
    inputs: PlannerInputs | null,
    label: string,
    onFocus?: (tile: {x: number; z: number}) => void,
): {title: string; body: ReactNode} | null {
    const item = inputs?.items.find((i) => i.id === row.id);
    const families = (inputs?.itemGroups ?? []).filter((g) => g.items.some((m) => m.id === row.id));
    if (!item && families.length === 0) return null;
    return {
        title: item ? `${label} · ${item.transports} transports` : label,
        body: (
            <>
                {families.length > 0 && (
                    <ul>
                        {families.map((g) => (
                            <li key={g.label}>
                                counts as {g.label} · {g.transports} transports
                            </li>
                        ))}
                    </ul>
                )}
                {item?.carry.map((t) => (
                    <OpeningList
                        key={`c${t.at}`}
                        list={t.opens}
                        total={t.transports}
                        prefix={t.at > 1 ? `${t.at} · ` : ""}
                        onFocus={onFocus}
                    />
                ))}
                {item?.mustNotCarry.map((t) => (
                    <OpeningList
                        key={`n${t.at}`}
                        list={t.opens}
                        total={t.transports}
                        prefix={`over ${t.at} closes · `}
                        onFocus={onFocus}
                    />
                ))}
            </>
        ),
    };
}

/**
 * Transport names, each a button that shows its place on the map when it has one and `onFocus` is
 * given. `total` is the full count when `list` is only a sample.
 */
export function OpeningList({
    list,
    total,
    prefix = "",
    onFocus,
}: {
    list: PlannerOpening[];
    total?: number;
    prefix?: string;
    onFocus?: (tile: {x: number; z: number}) => void;
}) {
    const more = (total ?? list.length) - list.length;
    return (
        <ul className="planner-openings">
            {list.map(({name, x, z}, i) => (
                <li key={i}>
                    {prefix}
                    {x != null && z != null && onFocus ? (
                        <button
                            type="button"
                            className="planner-link"
                            title={`show ${x}, ${z} on the map`}
                            onClick={() => onFocus({x, z})}
                        >
                            {name}
                        </button>
                    ) : (
                        name
                    )}
                </li>
            ))}
            {more > 0 && <li className="planner-tip-note">…and {more} more</li>}
        </ul>
    );
}

function Amount(props: {value: number; disabled?: boolean; onChange: (n: number) => void}) {
    return (
        <input
            type="number"
            min={0}
            value={props.value}
            disabled={props.disabled}
            onChange={(e) => props.onChange(Math.max(0, parseInt(e.target.value, 10) || 0))}
        />
    );
}

/** The arrive amount `*`: any amount. It never forces a bank stop, but a stop made anyway banks it. */
const ARRIVE_ANY = -1;

// A text field, since a number input refuses `*`; the draft keeps "1" on the way to "12".
function ArriveAmount(props: {value: number; onChange: (n: number) => void}) {
    const shown = props.value === ARRIVE_ANY ? "*" : String(props.value);
    const [draft, setDraft] = useState<string | null>(null);
    return (
        <input
            type="text"
            inputMode="numeric"
            value={draft ?? shown}
            title="how many to arrive holding — * is any amount, but a bank stop on the way deposits it"
            onChange={(e) => {
                const t = e.target.value.trim();
                setDraft(t);
                if (t === "*") props.onChange(ARRIVE_ANY);
                else if (/^\d+$/.test(t)) props.onChange(parseInt(t, 10));
                else if (t === "") props.onChange(0);
            }}
            onBlur={() => setDraft(null)}
        />
    );
}

const TIP_MIDDLEWARE = [offset(6), flip({padding: 8}), shift({padding: 8})];

// The one open info tip's closer: opening another closes it.
let closeOpenInfo: (() => void) | null = null;

/** An ⓘ button whose tip floats beside the panel on hover or focus; renders nothing when it has no content. */
export function Info(props: {title?: string; lines?: string[]; children?: ReactNode; wide?: boolean}) {
    const [open, setOpen] = useState(false);
    const close = useRef(() => setOpen(false)).current;
    const onOpenChange = (next: boolean) => {
        if (next) {
            if (closeOpenInfo && closeOpenInfo !== close) closeOpenInfo();
            closeOpenInfo = close;
        } else if (closeOpenInfo === close) {
            closeOpenInfo = null;
        }
        setOpen(next);
    };
    const {refs, floatingStyles, context} = useFloating({
        open,
        onOpenChange,
        // Left of the button, so the tip stays over the panel instead of the route it describes.
        placement: "left",
        middleware: TIP_MIDDLEWARE,
        whileElementsMounted: autoUpdate,
    });
    const {getReferenceProps, getFloatingProps} = useInteractions([
        useHover(context, {move: false, handleClose: safePolygon({blockPointerEvents: false})}),
        useFocus(context),
        useDismiss(context),
        useRole(context, {role: "tooltip"}),
    ]);
    if (!props.children && !props.lines?.length && !props.title) return null;
    return (
        <>
            {/* eslint-disable-next-line @typescript-eslint/unbound-method -- floating-ui's refs setters are bound callbacks, meant to be passed as refs */}
            <button type="button" className="planner-info" ref={refs.setReference} {...getReferenceProps()}>
                ⓘ
            </button>
            {open && (
                <FloatingPortal>
                    {/* Focusable, so a click inside keeps focus in the tip rather than closing it. */}
                    <div
                        // eslint-disable-next-line @typescript-eslint/unbound-method, react-hooks/refs -- floating-ui's setter, a callback ref; nothing is read during render
                        ref={refs.setFloating}
                        style={floatingStyles}
                        tabIndex={-1}
                        className={`planner-tip${props.wide ? " wide" : ""}`}
                        {...getFloatingProps()}
                    >
                        {props.title && <div className="planner-tip-title">{props.title}</div>}
                        {props.children ?? (
                            <ul>
                                {props.lines?.map((l, i) => (
                                    <li key={i}>{l}</li>
                                ))}
                            </ul>
                        )}
                    </div>
                </FloatingPortal>
            )}
        </>
    );
}

interface ItemMatch {
    row: PlannerRow;
    label: string;
    note: string;
}

// Items a gate reads rank first, then any item at all, since a loadout may want an item no gate
// reads. `filter={null}`: this ranking is the whole filter.
function ItemSearch(props: {
    inputs: PlannerInputs | null;
    itemDefs: ItemNames | null;
    search: string;
    onSearch: (s: string) => void;
    onAdd: (row: PlannerRow) => void;
}) {
    const {inputs, itemDefs, search} = props;
    const matches = useMemo(() => {
        const gates = new Map((inputs?.items ?? []).map((i) => [i.id, i]));
        const gated: ItemMatch[] = searchItems(
            [...gates.values()].map((i) => [i.id, i.name] as const),
            search,
            12,
        ).map((h) => ({
            row: {kind: "item", id: h.id},
            label: h.name,
            note: `${gates.get(h.id)?.transports ?? 0} transports`,
        }));
        const rest: ItemMatch[] = searchItems(
            itemEntries(itemDefs ?? {}),
            search,
            12 - gated.length,
            (id) => !gates.has(id),
        ).map((h) => ({row: {kind: "item", id: h.id}, label: h.name, note: "for a loadout"}));
        return [...gated, ...rest];
    }, [inputs, itemDefs, search]);

    return (
        <div className="planner-search">
            <Autocomplete.Root
                items={matches}
                filter={null}
                value={search}
                onValueChange={(v, details) => {
                    if (details.reason !== "item-press") props.onSearch(v);
                }}
                onOpenChange={(open, details) => {
                    if (!open && details.reason === "escape-key") props.onSearch("");
                }}
                autoHighlight
                itemToStringValue={(m) => m.label}
            >
                <Autocomplete.Input type="search" placeholder="+ add an item…" />
                <Autocomplete.Portal>
                    <Autocomplete.Positioner className="planner-matches-positioner" sideOffset={2} collisionPadding={8}>
                        <Autocomplete.Popup className="planner-matches">
                            <Autocomplete.Empty className="planner-matches-empty">no such item</Autocomplete.Empty>
                            <Autocomplete.List>
                                {(m: ItemMatch) => (
                                    <Autocomplete.Item
                                        key={rowKey(m.row)}
                                        value={m}
                                        className="planner-match"
                                        onClick={() => props.onAdd(m.row)}
                                    >
                                        {m.label} <em>{m.note}</em>
                                    </Autocomplete.Item>
                                )}
                            </Autocomplete.List>
                        </Autocomplete.Popup>
                    </Autocomplete.Positioner>
                </Autocomplete.Portal>
            </Autocomplete.Root>
        </div>
    );
}

/**
 * The loadout half of an items table, keyed by {@link rowKey}: what to arrive holding
 * ({@link ARRIVE_ANY} for any amount), and which rows to arrive wearing.
 */
export interface LoadoutRows {
    rows: PlannerRow[];
    arrive: Record<string, number>;
    equip: Record<string, boolean | undefined>;
}

/**
 * The rows as wants: the whole loadout to arrive with, not a floor. A row at 0 is left out, a `*`
 * row has target 0 and no ceiling, and every other row is exact.
 */
function loadoutWants(state: LoadoutRows): PlanWant[] {
    const wants: PlanWant[] = [];
    for (const row of state.rows) {
        const key = rowKey(row);
        const arrive = state.arrive[key] ?? 0;
        if (arrive === ARRIVE_ANY) {
            wants.push({item: row.id, min: 0, target: 0, max: -1});
        } else if (arrive > 0) {
            wants.push({
                item: row.id,
                min: arrive,
                target: arrive,
                max: arrive,
                ...(state.equip[key] ? {worn: true} : {}),
            });
        }
    }
    return wants;
}

/** The rows as a request's loadout. Whatever the rows do not name is banked on the way. */
export function loadoutOf(state: LoadoutRows): PlanLoadoutInput {
    return {
        keepEverythingElse: false,
        bankWhenFull: false,
        wants: loadoutWants(state),
    };
}

/** An extra amount column before arrive, such as the account's pack or bank. */
interface LoadoutColumn {
    label: string;
    title: string;
    value: (key: string) => number;
    onChange: (key: string, n: number) => void;
}

/**
 * `wearableItems` absent offers wear for nothing; `columns` come between the item and arrive;
 * `removable` defaults to every row; `blocked` holds the row keys a failed plan blames; `onAdd`
 * gives a new row's starting amounts beyond arrive 1.
 */
export interface LoadoutTableProps<S extends LoadoutRows> {
    inputs: PlannerInputs | null;
    itemDefs: ItemNames | null;
    wearableItems?: ReadonlySet<number> | null;
    state: S;
    onChange: (patch: Partial<S>) => void;
    columns?: LoadoutColumn[];
    removable?: (row: PlannerRow) => boolean;
    blocked?: ReadonlySet<string>;
    onAdd?: (row: PlannerRow) => Partial<S>;
    onFocus?: (tile: {x: number; z: number}) => void;
}

/** The items table: name with info tip, the extra columns, arrive and wear, and a search to add a row. */
export function LoadoutTable<S extends LoadoutRows>(props: LoadoutTableProps<S>) {
    const {inputs, itemDefs, state, onChange} = props;
    const columns = props.columns ?? [];
    const [search, setSearch] = useState("");
    const nameOf = (id: number) => itemDefs?.[String(id)] ?? `item#${id}`;
    const grid = {gridTemplateColumns: `1fr ${"50px ".repeat(columns.length + 1)}40px`};
    return (
        <div className="loadout-table">
            <div className="planner-item-head" style={grid}>
                <span>
                    <span className="planner-drop" aria-hidden="true" />
                    item
                </span>
                {columns.map((c) => (
                    <span key={c.label} title={c.title}>
                        {c.label}
                    </span>
                ))}
                <span title="how many to arrive holding — * is any amount, but a bank stop on the way deposits it">
                    arrive
                </span>
                <span title="arrive WEARING it, not merely holding it">wear</span>
            </div>
            {state.rows.map((row) => {
                const key = rowKey(row);
                const label = nameOf(row.id);
                const info = rowInfo(row, inputs, label, props.onFocus);
                const canWear = props.wearableItems?.has(row.id) ?? false;
                return (
                    <div
                        key={key}
                        className={`planner-item-row${props.blocked?.has(key) ? " planner-item-blocked" : ""}`}
                        style={grid}
                    >
                        <span className="probe-item-name">
                            {(props.removable?.(row) ?? true) ? (
                                <button
                                    type="button"
                                    className="planner-drop"
                                    title="remove row"
                                    onClick={() =>
                                        onChange({rows: state.rows.filter((r) => rowKey(r) !== key)} as Partial<S>)
                                    }
                                >
                                    ×
                                </button>
                            ) : (
                                <span className="planner-drop" aria-hidden="true" />
                            )}
                            {label} {info && <Info title={info.title}>{info.body}</Info>}
                        </span>
                        {columns.map((c) => (
                            <Amount key={c.label} value={c.value(key)} onChange={(n) => c.onChange(key, n)} />
                        ))}
                        <ArriveAmount
                            value={state.arrive[key] ?? 0}
                            onChange={(n) => onChange({arrive: {...state.arrive, [key]: n}} as Partial<S>)}
                        />
                        <input
                            type="checkbox"
                            className="planner-equip"
                            title={canWear ? "arrive wearing it" : "this item cannot be worn"}
                            disabled={!canWear}
                            checked={state.equip[key] ?? false}
                            onChange={(e) => onChange({equip: {...state.equip, [key]: e.target.checked}} as Partial<S>)}
                        />
                    </div>
                );
            })}
            <ItemSearch
                inputs={inputs}
                itemDefs={itemDefs}
                search={search}
                onSearch={setSearch}
                onAdd={(row) => {
                    const key = rowKey(row);
                    if (state.rows.some((r) => rowKey(r) === key)) return;
                    onChange({
                        rows: [...state.rows, row],
                        arrive: {...state.arrive, [key]: 1},
                        ...props.onAdd?.(row),
                    } as Partial<S>);
                    setSearch("");
                }}
            />
        </div>
    );
}
