import {useEffect, useState} from "react";
import {FloatingWindow} from "../FloatingWindow";
import {getItemAtlas, ItemIcon, type ItemAtlasIndex} from "../ItemIcon";
import "./pack.css";

/** One item the route bot holds or has banked; `held` and `banked` are amounts. */
export interface PackItem {
    id: number;
    held: number;
    banked: number;
    worn: boolean;
}

/**
 * The route bot's inventory and bank as the client's bank window draws them: one slot per item, the
 * banked amount in green and the held amount in blue, worn items first on a red slot, then what only
 * the bank holds. `names` maps item id to display name, for the tooltips.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/com/openrsc/interfaces/misc/BankInterface.java#L252 BankInterface.drawBankComponents
 */
export function PackWindow({items, names}: {items: PackItem[]; names: Record<string, string> | null}) {
    const [atlas, setAtlas] = useState<ItemAtlasIndex | null>(null);
    const carried = items.filter((i) => i.held > 0).sort((a, b) => Number(b.worn) - Number(a.worn));
    const banked = items.filter((i) => i.held === 0);
    const slot = (item: PackItem) => (
        <div
            key={item.id}
            className={item.worn ? "pack-slot worn" : "pack-slot"}
            title={names?.[String(item.id)] ?? `item ${item.id}`}
        >
            <ItemIcon atlas={atlas} id={item.id} box={30} />
            {item.banked > 0 && <span className="pack-in-bank">{amount(item.banked)}</span>}
            {item.held > 0 && <span className="pack-held">{amount(item.held)}</span>}
        </div>
    );
    useEffect(() => {
        let alive = true;
        void getItemAtlas().then((a) => alive && setAtlas(a));
        return () => {
            alive = false;
        };
    }, []);
    return (
        <FloatingWindow
            id="pack"
            className="pack"
            resizable
            anchor={{top: 8, right: 96}}
            title="bank & inventory"
            actions={
                <span className="pack-legend">
                    <em className="pack-in-bank">bank</em> <em className="pack-held">held</em>
                </span>
            }
        >
            <div className="pack-grid">
                {carried.map(slot)}
                {carried.length > 0 && banked.length > 0 && <hr className="pack-rule" />}
                {banked.map(slot)}
                {items.length === 0 && <span className="pack-empty">nothing carried or banked</span>}
            </div>
        </FloatingWindow>
    );
}

// The client's stack amount shortening.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L816
function amount(n: number): string {
    if (n >= 10_000_000) return `${Math.floor(n / 1_000_000)}M`;
    if (n >= 100_000) return `${Math.floor(n / 1000)}K`;
    return String(n);
}
