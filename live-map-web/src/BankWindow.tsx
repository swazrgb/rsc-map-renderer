import {type MouseEvent, useMemo, useState} from "react";
import type {ItemAtlasIndex} from "@world3d";
import type {BankMove, BankPreset, BankScreen, BankSlot, InventoryItem, ItemDefFull, ItemDefs} from "./api";
import {compact, thousands} from "./format";
import {GameMenu, type GameMenuAt, type GameMenuEntry} from "./GameMenu";
import {
    AmountPicker,
    type CarriedStack,
    ItemSlot,
    type SlotDrag,
    useCarried,
    useItemAtlas,
    useItemDefsFull,
    useSlotAmount,
} from "./ItemSlots";

// The counts the game's bank menu offers, then the one typed below when it is none of them.
const MENU_AMOUNTS = [1, 5, 10, 50];

const SORTS = {bank: "bank order", name: "name", value: "value", amount: "amount"} as const;
type Sort = keyof typeof SORTS;
type Rearrange = "none" | "swap" | "insert";

interface Kept {
    sort: Sort;
    rearrange: Rearrange;
    noted: boolean;
}

// A dragged stack's bank slot and item, under a type of the window's own, so nothing else dropped on a slot is taken
// for one.
const DRAGGED_STACK = "application/x-bank-stack";

// What the next bank opened on the page starts with: what the last one was left at.
const kept: Kept = {sort: "bank", rearrange: "none", noted: false};

function useKept<K extends keyof Kept>(key: K): [Kept[K], (value: Kept[K]) => void] {
    const [value, setValue] = useState(kept[key]);
    return [
        value,
        (next) => {
            kept[key] = next;
            setValue(next);
        },
    ];
}

type FullDefs = Record<string, ItemDefFull> | undefined;

const nameOf = (defs: ItemDefs, id: number) => defs[String(id)] ?? `item ${id}`;
const priceOf = (defs: FullDefs, id: number) => defs?.[String(id)]?.basePrice ?? 0;

export interface BankWindowProps {
    screen: BankScreen;
    bank: readonly BankSlot[];
    /** The bank's slots; null when not known. */
    capacity: number | null;
    pack: readonly InventoryItem[];
    defs: ItemDefs;
    onMove: (move: BankMove) => void;
}

/**
 * The bot's open bank. A click on a banked stack withdraws, one on a carried stack deposits, as many as the amount
 * chosen below, and a right click offers the game's own counts; a worn item goes straight in where the world takes it
 * so. The bank is searched and sorted here, and where the world lets it, rearranged by dragging a stack onto another.
 */
export function BankWindow({screen, bank, capacity, pack, defs, onMove}: BankWindowProps) {
    const atlas = useItemAtlas();
    const defsFull = useItemDefsFull();
    const carried = useCarried(pack);
    const {amount, setAmount, take} = useSlotAmount();
    const [noted, setNoted] = useKept("noted");
    const [sort, setSort] = useKept("sort");
    const [rearrange, setRearrange] = useKept("rearrange");
    const [filter, setFilter] = useState("");
    const [menu, setMenu] = useState<GameMenuAt | null>(null);
    const [dragged, setDragged] = useState<number | null>(null);
    const [over, setOver] = useState<number | null>(null);

    const name = (id: number) => nameOf(defs, id);
    // The game's own total: every banked stack at its item's base price, but for those that cannot be traded.
    const wealth = useMemo(
        () =>
            bank.reduce(
                (sum, s) => (defsFull?.[String(s.id)]?.untradeable ? sum : sum + priceOf(defsFull, s.id) * s.amount),
                0,
            ),
        [bank, defsFull],
    );
    const shown = useMemo(() => {
        const q = filter.trim().toLowerCase();
        const hits = q ? bank.filter((s) => nameOf(defs, s.id).toLowerCase().includes(q)) : [...bank];
        const value = (s: BankSlot) => priceOf(defsFull, s.id) * s.amount;
        const order: Record<Sort, (a: BankSlot, b: BankSlot) => number> = {
            bank: () => 0,
            name: (a, b) => nameOf(defs, a.id).localeCompare(nameOf(defs, b.id)),
            value: (a, b) => value(b) - value(a),
            amount: (a, b) => b.amount - a.amount,
        };
        return hits.sort((a, b) => order[sort](a, b) || a.slot - b.slot);
    }, [bank, defs, defsFull, filter, sort]);

    const amountLabel = amount === "all" ? "all" : String(amount);
    const counts =
        typeof amount === "number" && !MENU_AMOUNTS.includes(amount) ? [...MENU_AMOUNTS, amount] : MENU_AMOUNTS;
    const asNotes = screen.notes && noted;
    const withdraw = (id: number, n: number) => {
        if (n > 0) onMove({action: "withdraw", item: id, amount: n, noted: asNotes});
    };
    const deposit = (id: number, n: number) => {
        if (n > 0) onMove({action: "deposit", item: id, amount: n});
    };
    const unequip = (id: number) => onMove({action: "unequip", item: id});

    const loose = carried.filter((it) => !it.worn);
    const worn = carried.filter((it) => it.worn);
    const depositPack = () => {
        if (screen.depositAll) {
            onMove({action: "deposit_inventory"});
            return;
        }
        // A deposit counts the item's noted and unnoted stacks alike and takes from its last one, worn or not, so each
        // item is deposited once, all of it, and none that is also worn:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/Bank.java#L539-L549
        const wornIds = new Set(worn.map((it) => it.id));
        const byId = new Map<number, number>();
        for (const it of loose) {
            if (!wornIds.has(it.id)) byId.set(it.id, (byId.get(it.id) ?? 0) + it.count);
        }
        for (const [id, n] of byId) deposit(id, n);
    };

    const openMenu = (e: MouseEvent, entries: GameMenuEntry[]) => {
        e.preventDefault();
        setMenu({x: e.clientX, y: e.clientY, entries});
    };
    const withdrawMenu = (s: BankSlot): GameMenuEntry[] => {
        const item = name(s.id);
        const out: GameMenuEntry[] = counts.map((n) => ({
            label: `Withdraw-${n}`,
            name: item,
            run: () => withdraw(s.id, Math.min(n, s.amount)),
        }));
        out.push({label: "Withdraw-All", name: item, run: () => withdraw(s.id, s.amount)});
        if (s.amount > 1) {
            out.push({label: "Withdraw-All-But-1", name: item, run: () => withdraw(s.id, s.amount - 1)});
        }
        return out;
    };
    const depositMenu = (it: CarriedStack): GameMenuEntry[] => {
        const item = name(it.id);
        return [
            ...counts.map((n) => ({
                label: `Deposit-${n}`,
                name: item,
                run: () => deposit(it.id, Math.min(n, it.count)),
            })),
            {label: "Deposit-All", name: item, run: () => deposit(it.id, it.count)},
        ];
    };

    // As the game's client, only in the bank's own order and without a search, so a stack lands where it is seen to.
    const searching = filter.trim() !== "";
    const dragMode = screen.rearrange && sort === "bank" && !searching && rearrange !== "none" ? rearrange : null;
    const endDrag = () => {
        setDragged(null);
        setOver(null);
    };
    const drag = (s: BankSlot): SlotDrag | undefined =>
        dragMode === null
            ? undefined
            : {
                  draggable: true,
                  onDragStart: (e) => {
                      e.dataTransfer.effectAllowed = "move";
                      e.dataTransfer.setData(DRAGGED_STACK, `${s.slot}:${s.id}`);
                      setDragged(s.slot);
                  },
                  onDragOver: (e) => {
                      if (!e.dataTransfer.types.includes(DRAGGED_STACK)) return;
                      e.preventDefault();
                      setOver(s.slot);
                  },
                  onDragLeave: () => setOver((o) => (o === s.slot ? null : o)),
                  onDrop: (e) => {
                      e.preventDefault();
                      const [from, id] = e.dataTransfer.getData(DRAGGED_STACK).split(":").map(Number);
                      // The stack picked up, only while it is still where it was: the bank may have changed meanwhile.
                      if (from !== s.slot && bank.some((b) => b.slot === from && b.id === id)) {
                          onMove({action: dragMode, slot: from, to: s.slot});
                      }
                      endDrag();
                  },
                  onDragEnd: endDrag,
              };

    const packSlot = (it: CarriedStack) => {
        const count = it.count > 1 ? it.count : undefined;
        if (it.worn && screen.unequip) {
            return (
                <ItemSlot
                    key={it.key}
                    atlas={atlas}
                    id={it.id}
                    count={count}
                    worn
                    title={`Unequip ${name(it.id)} to the bank`}
                    onClick={() => unequip(it.id)}
                    onContextMenu={(e) =>
                        openMenu(e, [{label: "Unequip to bank", name: name(it.id), run: () => unequip(it.id)}])
                    }
                />
            );
        }
        if (it.worn && !screen.wornDeposits) {
            return (
                <ItemSlot
                    key={it.key}
                    atlas={atlas}
                    id={it.id}
                    worn
                    title={`${name(it.id)} (worn: take it off first)`}
                />
            );
        }
        return (
            <ItemSlot
                key={it.key}
                atlas={atlas}
                id={it.id}
                count={count}
                worn={it.worn}
                noted={it.noted}
                title={`Deposit ${amountLabel} ${name(it.id)} (${thousands(it.count)} ${it.worn ? "worn" : it.noted ? "noted" : "carried"})`}
                onClick={() => deposit(it.id, take(it.count))}
                onContextMenu={(e) => openMenu(e, depositMenu(it))}
            />
        );
    };

    return (
        <div className="item-window bank-window" role="dialog" aria-label="bank">
            <div className="item-window-head">
                <span className="item-window-title">Bank</span>
                <span className="item-window-meta">
                    {bank.length}
                    {capacity ? `/${capacity}` : ""}
                </span>
                {wealth > 0 && (
                    <span className="item-window-meta" title={`${thousands(wealth)} gp at base prices`}>
                        {compact(wealth)} gp
                    </span>
                )}
                <button
                    type="button"
                    className="item-window-close"
                    title="close the bank"
                    onClick={() => onMove({action: "close"})}
                >
                    ×
                </button>
            </div>
            <div className="bank-tools">
                <input
                    type="search"
                    className="bank-filter"
                    placeholder="search"
                    value={filter}
                    onChange={(e) => setFilter(e.target.value)}
                />
                {filter.trim() && <span className="item-window-meta">{shown.length} found</span>}
                <select value={sort} title="order" onChange={(e) => setSort(e.target.value as Sort)}>
                    {Object.entries(SORTS).map(([key, label]) => (
                        <option key={key} value={key}>
                            {label}
                        </option>
                    ))}
                </select>
                {screen.rearrange && (
                    <select
                        value={rearrange}
                        disabled={sort !== "bank" || searching}
                        title={
                            sort === "bank" && !searching
                                ? "what dragging a stack onto another does"
                                : "drag in bank order, without a search"
                        }
                        onChange={(e) => setRearrange(e.target.value as Rearrange)}
                    >
                        <option value="none">drag: off</option>
                        <option value="swap">drag: swap</option>
                        <option value="insert">drag: insert</option>
                    </select>
                )}
            </div>
            <div className="slot-grid bank-grid-bank">
                {shown.map((s) => (
                    <ItemSlot
                        key={s.slot}
                        atlas={atlas}
                        id={s.id}
                        count={s.amount}
                        className={s.slot === dragged ? "slot-dragged" : s.slot === over ? "slot-drop" : undefined}
                        title={`Withdraw ${amountLabel} ${name(s.id)}${asNotes ? " as notes" : ""} (${thousands(s.amount)} banked, ${thousands(priceOf(defsFull, s.id) * s.amount)} gp)`}
                        onClick={() => withdraw(s.id, take(s.amount))}
                        onContextMenu={(e) => openMenu(e, withdrawMenu(s))}
                        drag={drag(s)}
                    />
                ))}
                {shown.length === 0 && <div className="slot-empty">{bank.length === 0 ? "empty" : "no match"}</div>}
            </div>
            <div className="bank-tools bank-pack-tools">
                <button
                    type="button"
                    title="deposit everything carried but what is also worn"
                    disabled={loose.length === 0}
                    onClick={depositPack}
                >
                    deposit pack
                </button>
                {screen.depositWorn && (
                    <button
                        type="button"
                        title="deposit everything worn"
                        disabled={worn.length === 0}
                        onClick={() => onMove({action: "deposit_worn"})}
                    >
                        deposit worn
                    </button>
                )}
                {screen.presets && <Presets presets={screen.presets} atlas={atlas} name={name} onMove={onMove} />}
            </div>
            <div className="slot-grid slot-grid-pack">{carried.map(packSlot)}</div>
            <div className="item-window-foot">
                <AmountPicker amount={amount} onChange={setAmount} />
                {screen.notes && (
                    <span className="bank-as" title="withdraw as items or as bank notes">
                        as
                        <button type="button" className={noted ? "" : "active"} onClick={() => setNoted(false)}>
                            items
                        </button>
                        <button type="button" className={noted ? "active" : ""} onClick={() => setNoted(true)}>
                            notes
                        </button>
                    </span>
                )}
            </div>
            {menu && <GameMenu menu={menu} onClose={() => setMenu(null)} />}
        </div>
    );
}

/**
 * The bank's presets: a click loads one; "save" first, and the next click saves what is carried and worn as that one.
 * Pointing at one shows what it holds.
 */
function Presets(props: {
    presets: readonly BankPreset[];
    atlas: ItemAtlasIndex | null;
    name: (id: number) => string;
    onMove: (move: BankMove) => void;
}) {
    const {presets, atlas, name, onMove} = props;
    const [saving, setSaving] = useState(false);
    const [peek, setPeek] = useState<number | null>(null);
    const peeked = presets.find((p) => p.slot === peek);
    return (
        <div className="bank-presets">
            <span className="item-window-meta">{saving ? "save as" : "preset"}</span>
            {presets.map((p) => (
                <button
                    key={p.slot}
                    type="button"
                    className={saving ? "bank-preset-saving" : ""}
                    title={`${saving ? "save" : "load"} preset ${p.slot + 1}`}
                    onMouseEnter={() => setPeek(p.slot)}
                    onMouseLeave={() => setPeek(null)}
                    onFocus={() => setPeek(p.slot)}
                    onBlur={() => setPeek(null)}
                    onClick={() => {
                        onMove({action: saving ? "save_preset" : "load_preset", slot: p.slot});
                        setSaving(false);
                    }}
                >
                    {p.slot + 1}
                </button>
            ))}
            <button
                type="button"
                className={saving ? "active" : ""}
                title={saving ? "keep the presets" : "save what is carried and worn as a preset"}
                onClick={() => setSaving(!saving)}
            >
                {saving ? "cancel" : "save"}
            </button>
            {peeked && <PresetPeek preset={peeked} atlas={atlas} name={name} />}
        </div>
    );
}

// What a preset holds, worn first. Loading one never saved banks everything carried and worn:
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/container/BankPreset.java#L97-L175
function PresetPeek({
    preset,
    atlas,
    name,
}: {
    preset: BankPreset;
    atlas: ItemAtlasIndex | null;
    name: (id: number) => string;
}) {
    const stacks = [...preset.worn.map((s) => ({...s, worn: true})), ...preset.pack.map((s) => ({...s, worn: false}))];
    return (
        <div className="bank-preset-peek">
            <div className="item-window-meta">
                preset {preset.slot + 1}
                {!preset.known ? ": not seen yet" : stacks.length === 0 ? ": empty, loading it banks everything" : ""}
            </div>
            {stacks.length > 0 && (
                <div className="slot-grid">
                    {stacks.map((s, i) => (
                        <ItemSlot
                            key={i}
                            atlas={atlas}
                            id={s.id}
                            count={s.amount > 1 ? s.amount : undefined}
                            worn={s.worn}
                            noted={s.noted}
                            title={name(s.id)}
                        />
                    ))}
                </div>
            )}
        </div>
    );
}
