import {type ReactNode, useState} from "react";
import type {ItemAggregate} from "./api";
import type {PanelTab} from "./BotPanel";
import {ratePerHour} from "./format";
import {useTopicObject} from "./topics";

function fmt(n: number): string {
    return n.toLocaleString();
}

export interface ItemPanelProps {
    itemId: number;
    /** Which bots' holdings are listed; every bot's when absent. */
    isUsernameVisible?: (username: string) => boolean;
    onPickBot: (username: string) => void;
    /** Between the totals and the holders. */
    trend?: ReactNode;
    /** After the holders tab. */
    tabs?: PanelTab[];
    onClose: () => void;
}

const NO_TABS: PanelTab[] = [];

/** An item's side panel (the `items/item` topic): how many the bots hold, and which bots, most first. */
export function ItemPanel({itemId, isUsernameVisible, onPickBot, trend, tabs = NO_TABS, onClose}: ItemPanelProps) {
    const {data: item, error} = useTopicObject<ItemAggregate>("items/item", {id: itemId});
    const [picked, setPicked] = useState("holders");
    const extra = tabs.find((t) => t.key === picked);

    const holders = item
        ? [...item.holders]
              .filter((h) => isUsernameVisible?.(h.username) ?? true)
              .sort((a, b) => b.inventory + b.bank - (a.inventory + a.bank))
        : [];

    return (
        <div id="inspector">
            <div className="insp-head">
                <span className="insp-name">{item?.name ?? `item ${itemId}`}</span>
                <span className="insp-dim">#{itemId}</span>
                <button type="button" className="insp-close" title="close" onClick={onClose}>
                    ×
                </button>
            </div>

            {tabs.length > 0 && (
                <div className="insp-tabs">
                    {[{key: "holders", label: "holders"}, ...tabs].map((t) => (
                        <button
                            type="button"
                            key={t.key}
                            className={"insp-tab" + ((extra?.key ?? "holders") === t.key ? " active" : "")}
                            onClick={() => setPicked(t.key)}
                        >
                            {t.label}
                        </button>
                    ))}
                </div>
            )}

            <div className="insp-body">
                {extra?.render()}
                {!extra && error && <div className="insp-error">{error}</div>}
                {!extra && !item && !error && <div className="insp-dim">loading…</div>}
                {!extra && item && (
                    <>
                        <div className="insp-stats-summary">
                            <span>
                                total <b>{fmt(item.total)}</b>
                            </span>
                            <span>
                                inv <b>{fmt(item.inInventory)}</b>
                            </span>
                            <span>
                                bank <b>{fmt(item.inBank)}</b>
                            </span>
                        </div>
                        <div className="insp-count">
                            {item.perHour !== 0 && (
                                <span className={item.perHour > 0 ? "rate-pos" : "rate-neg"}>
                                    {ratePerHour(item.perHour)}
                                </span>
                            )}
                            {item.perHour !== 0 ? " · " : ""}
                            held by {holders.length} bot{holders.length === 1 ? "" : "s"}
                        </div>
                        {trend}
                        <div className="ih-list">
                            {holders.map((h) => {
                                const total = h.inventory + h.bank;
                                const parts: string[] = [];
                                if (h.inventory > 0)
                                    parts.push(`inv ${fmt(h.inventory)}${h.equipped ? " (worn)" : ""}`);
                                if (h.bank > 0) parts.push(`bank ${fmt(h.bank)}`);
                                return (
                                    <button
                                        type="button"
                                        key={h.username}
                                        className="ih-row"
                                        onClick={() => onPickBot(h.username)}
                                        title="select this bot on the map"
                                    >
                                        <span className="ih-name">{h.username}</span>
                                        <span className="ih-qty">{fmt(total)}</span>
                                        {Math.abs(h.perHour) >= 0.5 && (
                                            <span className={"ih-rate " + (h.perHour > 0 ? "rate-pos" : "rate-neg")}>
                                                {ratePerHour(h.perHour)}
                                            </span>
                                        )}
                                        <span className="ih-detail">{parts.join(" · ")}</span>
                                    </button>
                                );
                            })}
                            {holders.length === 0 && <div className="insp-dim">held by no bots</div>}
                        </div>
                    </>
                )}
            </div>
        </div>
    );
}
