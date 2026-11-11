import {type ButtonHTMLAttributes, type MouseEvent, useEffect, useMemo, useState} from "react";
import {getItemAtlas, ItemIcon, type ItemAtlasIndex} from "@world3d";
import {getItemDefsFull, type InventoryItem, type ItemDefFull} from "./api";

const ICON = 32;
const AMOUNTS = [1, 5, 10, 50] as const;

/** How many of a stack a click moves: a count, or all of it. */
export type SlotAmount = number | "all";

/** The item sprites, once they have loaded. */
export function useItemAtlas(): ItemAtlasIndex | null {
    const [atlas, setAtlas] = useState<ItemAtlasIndex | null>(null);
    useEffect(() => {
        let alive = true;
        void getItemAtlas().then((a) => alive && setAtlas(a));
        return () => {
            alive = false;
        };
    }, []);
    return atlas;
}

/** Every item's definition, once they have loaded. */
export function useItemDefsFull(): Record<string, ItemDefFull> | undefined {
    const [defs, setDefs] = useState<Record<string, ItemDefFull>>();
    useEffect(() => {
        let alive = true;
        void getItemDefsFull().then((d) => alive && setDefs(d));
        return () => {
            alive = false;
        };
    }, []);
    return defs;
}

/**
 * A stack of the pack: one per item and whether it is worn or noted, its count summed over the pack's slots of it.
 */
export interface CarriedStack {
    key: string;
    id: number;
    worn: boolean;
    noted: boolean;
    count: number;
}

export function useCarried(pack: readonly InventoryItem[]): CarriedStack[] {
    return useMemo(() => {
        const out = new Map<string, CarriedStack>();
        for (const it of pack) {
            const noted = it.noted ?? false;
            const key = `${it.id}:${it.equipped ? "w" : noted ? "n" : ""}`;
            const cell = out.get(key) ?? {key, id: it.id, worn: it.equipped, noted, count: 0};
            cell.count += it.amount;
            out.set(key, cell);
        }
        return [...out.values()];
    }, [pack]);
}

/** What makes a slot a drag's source or its target. */
export type SlotDrag = Pick<
    ButtonHTMLAttributes<HTMLButtonElement>,
    "draggable" | "onDragStart" | "onDragOver" | "onDragLeave" | "onDrop" | "onDragEnd"
>;

/**
 * An item's cell: its sprite, with its count in the game's stack label unless `count` is left out. `className` is added
 * to the cell's own.
 */
export function ItemSlot(props: {
    atlas: ItemAtlasIndex | null;
    id: number;
    count?: number;
    worn?: boolean;
    noted?: boolean;
    className?: string;
    title: string;
    onClick?: () => void;
    onContextMenu?: (e: MouseEvent) => void;
    drag?: SlotDrag;
}) {
    return (
        <button
            type="button"
            className={
                "slot" +
                (props.worn ? " slot-worn" : "") +
                (props.noted ? " slot-noted" : "") +
                (props.className ? " " + props.className : "")
            }
            title={props.title}
            disabled={!props.onClick}
            onClick={props.onClick}
            onContextMenu={props.onContextMenu}
            {...props.drag}
        >
            <ItemIcon atlas={props.atlas} id={props.id} box={ICON} />
            {props.count !== undefined && <StackCount n={props.count} />}
        </button>
    );
}

// The game's stack label: whole up to 100K, then thousands, then millions.
function StackCount({n}: {n: number}) {
    const [text, tone] =
        n >= 10_000_000
            ? [`${Math.floor(n / 1_000_000)}M`, "m"]
            : n >= 100_000
              ? [`${Math.floor(n / 1000)}K`, "k"]
              : [String(n), ""];
    return <span className={"slot-count " + tone}>{text}</span>;
}

/** The amount a click moves, and the count of it {@link take} gives out of `held`. */
export function useSlotAmount(): {
    amount: SlotAmount;
    setAmount: (a: SlotAmount) => void;
    take: (held: number) => number;
} {
    const [amount, setAmount] = useState<SlotAmount>(1);
    return {amount, setAmount, take: (held) => (amount === "all" ? held : Math.min(amount, held))};
}

/** 1, 5, 10, 50, all, or a typed count. */
export function AmountPicker({amount, onChange}: {amount: SlotAmount; onChange: (a: SlotAmount) => void}) {
    const [typed, setTyped] = useState("");
    const own = typeof amount === "number" && !AMOUNTS.some((n) => n === amount);
    return (
        <>
            {AMOUNTS.map((n) => (
                <button key={n} type="button" className={amount === n ? "active" : ""} onClick={() => onChange(n)}>
                    {n}
                </button>
            ))}
            <button type="button" className={amount === "all" ? "active" : ""} onClick={() => onChange("all")}>
                all
            </button>
            <input
                className={"amount-x" + (own ? " active" : "")}
                inputMode="numeric"
                placeholder="x"
                title="a count of your own"
                value={typed}
                onChange={(e) => {
                    const text = e.target.value.replace(/\D/g, "");
                    setTyped(text);
                    const n = Number(text);
                    if (n > 0) onChange(n);
                }}
            />
        </>
    );
}
