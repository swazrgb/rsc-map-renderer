import {memo, useMemo, useState, useDeferredValue} from "react";
import type {ItemRow} from "./api";
import {at, clipboard} from "./lookup";
import {noop} from "./errors";
import {ratePerHour, thousands} from "./format";
import {VirtualRows} from "./VirtualRows";

const ROW_H = 28;

const fmt = thousands;

type SortKey = "total" | "name" | "inInventory" | "inBank" | "perHour";

export interface ItemMark {
    name: string;
    note: string;
}

/**
 * Items singled out by the host: each row shows its note, and a toggle limits the list to them,
 * listing a marked item nobody holds as an empty row.
 */
export interface ItemMarks {
    label: string;
    byId: ReadonlyMap<number, ItemMark>;
}

interface ItemsViewProps {
    items: ItemRow[] | null;
    error: string | null;
    onRefresh: () => void;
    selectedId: number | null;
    onSelectItem: (id: number) => void;
    marks?: ItemMarks;
}

export const ItemsView = memo(function ItemsView({
    items,
    error,
    onRefresh,
    selectedId,
    onSelectItem,
    marks,
}: ItemsViewProps) {
    const [filter, setFilter] = useState("");
    const query = useDeferredValue(filter);
    const [sort, setSort] = useState<SortKey>("total");
    const [markedOnly, setMarkedOnly] = useState(false);
    const markById = marks?.byId;
    const onlyMarked = markedOnly && markById != null;

    const rows = useMemo(() => {
        if (!items) return [];
        const q = query.trim().toLowerCase();
        const shown = onlyMarked ? markedRows(items, markById) : items;
        const filtered = q ? shown.filter((it) => it.name.toLowerCase().includes(q) || String(it.id) === q) : shown;
        const sorted = [...filtered];
        sorted.sort((a, b) => {
            if (sort === "name") return a.name.localeCompare(b.name);
            if (sort === "perHour") return Math.abs(b.perHour) - Math.abs(a.perHour);
            return b[sort] - a[sort];
        });
        return sorted;
    }, [items, query, sort, onlyMarked, markById]);

    const distinct = items?.length ?? 0;

    const [copied, setCopied] = useState(false);
    const copyCsv = () => {
        const cb = clipboard();
        if (!cb) return;
        const header = "id,name,total,inInventory,inBank,perHour,bots";
        const body = rows
            .map((it) =>
                [it.id, csvField(it.name), it.total, it.inInventory, it.inBank, Math.round(it.perHour), it.bots].join(
                    ",",
                ),
            )
            .join("\n");
        cb.writeText(`${header}\n${body}\n`).then(() => {
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1200);
        }, noop);
    };

    return (
        <div id="items-view">
            <div className="items-bar">
                <input
                    className="items-filter"
                    placeholder="filter items…"
                    value={filter}
                    onChange={(e) => setFilter(e.target.value)}
                />
                {marks && (
                    <label className="items-meta items-marked-toggle">
                        <input type="checkbox" checked={markedOnly} onChange={(e) => setMarkedOnly(e.target.checked)} />
                        {marks.label} ({marks.byId.size})
                    </label>
                )}
                <span className="items-meta">
                    {distinct} distinct item{distinct === 1 ? "" : "s"}
                </span>
                <button type="button" className="items-refresh" onClick={onRefresh}>
                    refresh
                </button>
                <button
                    type="button"
                    className="items-refresh"
                    onClick={copyCsv}
                    disabled={rows.length === 0}
                    title="copy the shown rows as CSV"
                >
                    {copied ? "copied" : "copy"}
                </button>
            </div>

            {error && <div className="items-error">{error}</div>}
            {!items && !error && <div className="insp-dim">loading…</div>}

            {items && (
                <div className="items-table">
                    <div className="items-head">
                        <SortHead label="item" k="name" sort={sort} setSort={setSort} grow />
                        <SortHead label="total" k="total" sort={sort} setSort={setSort} />
                        <SortHead label="inv" k="inInventory" sort={sort} setSort={setSort} />
                        <SortHead label="bank" k="inBank" sort={sort} setSort={setSort} />
                        <SortHead label="/hr" k="perHour" sort={sort} setSort={setSort} />
                        <span className="items-col-holders">bots</span>
                    </div>
                    <VirtualRows
                        className="items-rows"
                        count={rows.length}
                        rowHeight={ROW_H}
                        rowKey={(i) => at(rows, i)?.id ?? i}
                        renderRow={(i) => {
                            const it = at(rows, i);
                            if (!it) return null;
                            const note = markById?.get(it.id)?.note;
                            return (
                                <button
                                    type="button"
                                    className={"items-row" + (it.id === selectedId ? " selected" : "")}
                                    onClick={() => onSelectItem(it.id)}
                                >
                                    <span className="items-col-name">
                                        {it.name}
                                        <span className="items-id">#{it.id}</span>
                                        {note && <span className="items-mark">{note}</span>}
                                    </span>
                                    <span className="items-col-num">{fmt(it.total)}</span>
                                    <span className="items-col-num dim">{fmt(it.inInventory)}</span>
                                    <span className="items-col-num dim">{fmt(it.inBank)}</span>
                                    <span
                                        className={
                                            "items-col-rate" +
                                            (Math.abs(it.perHour) < 0.5 ? "" : it.perHour > 0 ? " pos" : " neg")
                                        }
                                    >
                                        {Math.abs(it.perHour) < 0.5 ? "—" : ratePerHour(it.perHour)}
                                    </span>
                                    <span className="items-col-holders">{it.bots}</span>
                                </button>
                            );
                        }}
                    >
                        {rows.length === 0 && <div className="insp-dim">no matching items</div>}
                    </VirtualRows>
                </div>
            )}
        </div>
    );
});

function markedRows(items: ItemRow[], byId: ReadonlyMap<number, ItemMark>): ItemRow[] {
    const out = items.filter((it) => byId.has(it.id));
    const held = new Set(out.map((it) => it.id));
    for (const [id, m] of byId) {
        if (!held.has(id)) out.push({id, name: m.name, total: 0, inInventory: 0, inBank: 0, perHour: 0, bots: 0});
    }
    return out;
}

function csvField(s: string): string {
    return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function SortHead({
    label,
    k,
    sort,
    setSort,
    grow,
}: {
    label: string;
    k: SortKey;
    sort: SortKey;
    setSort: (k: SortKey) => void;
    grow?: boolean;
}) {
    return (
        <button
            type="button"
            className={"items-sort" + (grow ? " grow" : "") + (sort === k ? " active" : "")}
            onClick={() => setSort(k)}
        >
            {label}
            {sort === k ? " ▾" : ""}
        </button>
    );
}
