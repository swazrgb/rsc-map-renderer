import {type MouseEvent, type ReactNode, useEffect, useState} from "react";
import {getItemAtlas, ItemIcon, type ItemAtlasIndex, type UsedItem} from "@world3d";
import {
    type BotPanelState,
    getItemDefsFull,
    type InventoryItem,
    type ItemDefFull,
    type ItemDefs,
    type SkillState,
} from "./api";
import {thousands} from "./format";
import {entry} from "./lookup";
import {arravGang, arravGangClass, questCompleted, questStageClass, questStageLabel} from "./quests";
import {GameMenu, type GameMenuAt, type GameMenuEntry} from "./GameMenu";

const SKILL_ORDER = [
    "ATTACK",
    "DEFENSE",
    "STRENGTH",
    "HITS",
    "RANGED",
    "PRAYER",
    "MAGIC",
    "COOKING",
    "WOODCUTTING",
    "FLETCHING",
    "FISHING",
    "FIREMAKING",
    "CRAFTING",
    "SMITHING",
    "MINING",
    "HERBLAW",
    "AGILITY",
    "THIEVING",
    "RUNECRAFT",
    "HARVESTING",
];

const SKILL_LABEL: Record<string, string> = {
    DEFENSE: "Defense",
    HITS: "Hits",
    HERBLAW: "Herblaw",
    RUNECRAFT: "Runecraft",
};

/** A skill's name as the game writes it: "Woodcutting" for WOODCUTTING. */
export function skillLabel(name: string): string {
    return SKILL_LABEL[name] ?? name.charAt(0) + name.slice(1).toLowerCase();
}

function itemName(defs: ItemDefs, id: number): string {
    return defs[String(id)] ?? `item ${id}`;
}

const fmt = thousands;

function combatLevel(skills: Record<string, SkillState>): number {
    const lvl = (name: string) => entry(skills, name)?.maxLevel ?? 1;
    const attack = lvl("ATTACK") + lvl("STRENGTH");
    const defense = lvl("DEFENSE") + lvl("HITS");
    const ranged = lvl("RANGED");
    const mage = (lvl("PRAYER") + lvl("MAGIC")) / 8;
    const level = attack < ranged * 1.5 ? (2 * defense + 3 * ranged) / 8 + mage : (attack + defense) / 4 + mage;
    return Math.floor(level);
}

/** A tab of a side panel, drawn only while it is open. */
export interface PanelTab {
    key: string;
    label: string;
    render: () => ReactNode;
}

/** What an inventory item's menu asks the bot to do with it. */
export type ItemAction = "item_command" | "item_wield" | "item_unwield" | "item_drop" | "use_item_on_item";

/** Lets a viewer act with the bot's items through each one's right-click menu; a left click does nothing. */
export interface InventoryControl {
    /** The item a "Use" waits to be used with, or null. */
    useItem: UsedItem | null;
    onUseItemChange: (u: UsedItem | null) => void;
    /** `item` is the stack acted on, its bank notes when `noted`; `targetId` is the other item of a `use_item_on_item`. */
    act: (action: ItemAction, item: {id: number; noted: boolean}, targetId?: number) => void;
}

export interface BotPanelProps {
    username: string;
    /** Undefined until it arrives. */
    state: BotPanelState | undefined;
    /** Shown over the tabs' bodies instead of the "loading…" line, while `state` is undefined. */
    status?: ReactNode;
    error?: string | null;
    /** After the name in the head. */
    badges?: ReactNode;
    /** The bot's script, as a class name; shown without its package. */
    script?: string | null;
    /** What the bot is doing, in a box of its own; no box when undefined. */
    description?: string | null;
    itemDefs: ItemDefs;
    /** Under the skills. */
    statsExtra?: ReactNode;
    /** Over the quests. */
    questsExtra?: ReactNode;
    /** In place of the bank's slots. */
    bankNote?: ReactNode;
    inventoryControl?: InventoryControl;
    /** After the stats, inventory, bank and quests tabs. */
    tabs?: PanelTab[];
    /** Under the body. */
    dock?: ReactNode;
    onClose: () => void;
}

const NO_TABS: PanelTab[] = [];

type CoreTab = "stats" | "inventory" | "bank" | "quests";

const CORE_TABS: {key: CoreTab; label: string}[] = [
    {key: "stats", label: "stats"},
    {key: "inventory", label: "inv"},
    {key: "bank", label: "bank"},
    {key: "quests", label: "quests"},
];

/** A bot's side panel: its skills, inventory, bank and quests, each a tab, and any tabs a page adds. */
export function BotPanel({
    username,
    state,
    status,
    error,
    badges,
    script,
    description,
    itemDefs,
    statsExtra,
    questsExtra,
    bankNote,
    inventoryControl,
    tabs = NO_TABS,
    dock,
    onClose,
}: BotPanelProps) {
    const [picked, setPicked] = useState<string>("stats");
    const extra = tabs.find((t) => t.key === picked);
    const tab = extra || CORE_TABS.some((t) => t.key === picked) ? picked : "stats";

    return (
        <div id="inspector">
            <div className="insp-head">
                <span className="insp-name">{username}</span>
                {badges}
                <button type="button" className="insp-close" title="close" onClick={onClose}>
                    ×
                </button>
            </div>

            {script && (
                <div className="insp-script" title={script}>
                    {script.replace(/^.*\./, "")}
                </div>
            )}
            {state && description !== undefined && <div className="insp-desc">{description ?? ""}</div>}

            <div className="insp-tabs">
                {[...CORE_TABS, ...tabs].map((t) => (
                    <button
                        type="button"
                        key={t.key}
                        className={"insp-tab" + (tab === t.key ? " active" : "")}
                        onClick={() => setPicked(t.key)}
                    >
                        {t.label}
                    </button>
                ))}
            </div>

            <div className="insp-body">
                {error && <div className="insp-error">{error}</div>}
                {!state && !error && (status ?? <div className="insp-dim">loading…</div>)}
                {state && tab === "stats" && <StatsTab state={state}>{statsExtra}</StatsTab>}
                {state && tab === "inventory" && (
                    <InventoryTab state={state} defs={itemDefs} control={inventoryControl} />
                )}
                {state && tab === "bank" && (bankNote ?? <BankTab state={state} defs={itemDefs} />)}
                {state && tab === "quests" && <QuestsTab state={state}>{questsExtra}</QuestsTab>}
                {extra?.render()}
            </div>

            {dock}
        </div>
    );
}

function StatsTab({state, children}: {state: BotPanelState; children?: ReactNode}) {
    const skills = state.skills;
    let totalXp = 0;
    for (const name of SKILL_ORDER) {
        const s = entry(skills, name);
        if (s) {
            totalXp += s.xp;
        }
    }
    return (
        <div className="insp-stats">
            <div className="insp-stats-summary">
                <span>
                    combat level <b>{combatLevel(skills)}</b>
                </span>
                <span>
                    total xp <b>{fmt(totalXp)}</b>
                </span>
                <span>
                    quest points <b>{state.questPoints}</b>
                </span>
            </div>
            <div className="insp-skill-grid">
                {SKILL_ORDER.map((name) => {
                    const s = entry(skills, name);
                    if (!s) return null;
                    const boosted = s.level !== s.maxLevel;
                    return (
                        <div key={name} className="insp-skill" title={`${fmt(s.xp)} xp`}>
                            <span className="insp-skill-name">{skillLabel(name)}</span>
                            <span className={"insp-skill-lvl" + (boosted ? " boosted" : "")}>
                                {boosted ? `${s.level}/${s.maxLevel}` : s.maxLevel}
                            </span>
                        </div>
                    );
                })}
            </div>
            {children}
        </div>
    );
}

const ROW_ICON = 22;

// The stack an item verb acts on: a bank note carries its item's id.
function stackOf(it: InventoryItem): {id: number; noted: boolean} {
    return {id: it.id, noted: it.noted === true};
}

function sameStack(a: {id: number; noted: boolean}, it: InventoryItem): boolean {
    return a.id === it.id && a.noted === (it.noted === true);
}

function collapseInventory(inv: InventoryItem[]): (InventoryItem & {count: number})[] {
    const rows = new Map<string, InventoryItem & {count: number}>();
    for (const it of inv) {
        const key = `${it.id}:${it.equipped ? 1 : 0}:${it.noted ? 1 : 0}`;
        const row = rows.get(key);
        if (row) row.count += it.amount;
        else rows.set(key, {...it, count: it.amount});
    }
    return [...rows.values()];
}

function InventoryTab({
    state,
    defs,
    control,
}: {
    state: BotPanelState;
    defs: ItemDefs;
    control: InventoryControl | undefined;
}) {
    const inv = state.inventory;
    const [atlas, setAtlas] = useState<ItemAtlasIndex | null>(null);
    const [full, setFull] = useState<Record<string, ItemDefFull>>({});
    const [menu, setMenu] = useState<GameMenuAt | null>(null);
    useEffect(() => {
        let alive = true;
        void getItemAtlas().then((a) => alive && setAtlas(a));
        void getItemDefsFull().then((d) => alive && setFull(d));
        return () => {
            alive = false;
        };
    }, []);
    if (inv.length === 0) return <div className="insp-dim">empty inventory</div>;
    const packUsed = state.packSlotsUsed ?? inv.length;
    const offPack = inv.length - packUsed;
    const useItem = control?.useItem ?? null;

    const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

    const openMenu = (e: MouseEvent, it: InventoryItem, def: ItemDefFull | undefined) => {
        e.preventDefault();
        if (!control) return;
        const name = itemName(defs, it.id);
        const entries: GameMenuEntry[] = [];
        if (useItem && !sameStack(useItem, it) && !it.equipped) {
            entries.push({
                label: `Use ${useItem.name} with`,
                name,
                run: () => {
                    control.act("use_item_on_item", useItem, it.id);
                    control.onUseItemChange(null);
                },
            });
        }
        if (it.equipped) {
            entries.push({label: "Remove", name, run: () => control.act("item_unwield", stackOf(it))});
        } else if (def?.wearable && !it.noted) {
            entries.push({
                label: def.wield ? "Wield" : "Wear",
                name,
                run: () => control.act("item_wield", stackOf(it)),
            });
        }
        if (def?.command && !it.noted) {
            entries.push({label: cap(def.command), name, run: () => control.act("item_command", stackOf(it))});
        }
        entries.push({label: "Use", name, run: () => control.onUseItemChange({...stackOf(it), name})});
        entries.push({label: "Drop", name, run: () => control.act("item_drop", stackOf(it))});
        entries.push({label: "Cancel", name: ""});
        setMenu({x: e.clientX, y: e.clientY, entries});
    };

    return (
        <div>
            <div className="insp-count">
                {packUsed}/30 slots{offPack > 0 ? ` · ${offPack} worn` : ""}
            </div>
            <div style={{display: "flex", flexDirection: "column", gap: 1}}>
                {collapseInventory(inv).map((it) => {
                    const def = full[String(it.id)];
                    const armed = useItem != null && sameStack(useItem, it);
                    const name = itemName(defs, it.id);
                    return (
                        <div
                            key={`${it.id}:${it.equipped ? 1 : 0}:${it.noted ? 1 : 0}`}
                            title={name + (it.equipped ? " (equipped)" : it.noted ? " (noted)" : "")}
                            onContextMenu={(e) => openMenu(e, it, def)}
                            style={{
                                display: "flex",
                                alignItems: "center",
                                gap: 6,
                                padding: "1px 4px",
                                borderRadius: 3,
                                userSelect: "none",
                                background: armed
                                    ? "rgba(255,255,255,0.18)"
                                    : it.equipped
                                      ? "rgba(255,60,60,0.16)"
                                      : "rgba(208,208,208,0.08)",
                                border: armed ? "1px solid #ff9040" : "1px solid rgba(58,70,100,0.7)",
                                borderLeft: it.equipped
                                    ? "3px solid #ff3c3c"
                                    : armed
                                      ? "1px solid #ff9040"
                                      : "1px solid rgba(58,70,100,0.7)",
                            }}
                        >
                            <div
                                style={{
                                    width: ROW_ICON,
                                    height: ROW_ICON,
                                    flex: "none",
                                    display: "flex",
                                    alignItems: "center",
                                    justifyContent: "center",
                                }}
                            >
                                <ItemIcon atlas={atlas} id={it.id} box={ROW_ICON} />
                            </div>
                            <span
                                style={{
                                    flex: 1,
                                    minWidth: 0,
                                    fontSize: 11,
                                    overflow: "hidden",
                                    textOverflow: "ellipsis",
                                    whiteSpace: "nowrap",
                                }}
                            >
                                {name}
                                {it.equipped && (
                                    <span style={{color: "#ff6b6b", opacity: 0.8, fontSize: 10}}> (worn)</span>
                                )}
                            </span>
                            {it.count > 1 && (
                                <span
                                    style={{
                                        flex: "none",
                                        fontSize: 11,
                                        color: "#8fd98f",
                                        fontVariantNumeric: "tabular-nums",
                                    }}
                                >
                                    ×{fmt(it.count)}
                                </span>
                            )}
                        </div>
                    );
                })}
            </div>
            {menu && <GameMenu menu={menu} onClose={() => setMenu(null)} />}
        </div>
    );
}

function QuestsTab({state, children}: {state: BotPanelState; children?: ReactNode}) {
    const quests = state.quests;
    if (quests.length === 0) return <div className="insp-dim">no quest data</div>;
    const done = quests.filter((q) => questCompleted(q.stage)).length;
    const inProgress = quests.filter((q) => q.stage > 0).length;
    const rank = (s: number) => (questCompleted(s) ? Number.MAX_SAFE_INTEGER : s);
    const rows = [...quests].sort((a, b) => rank(b.stage) - rank(a.stage) || a.name.localeCompare(b.name));
    return (
        <div>
            {children}
            <div className="insp-count">
                {done}/{quests.length} complete{inProgress > 0 ? ` · ${inProgress} in progress` : ""}
                {` · ${state.questPoints} quest point${state.questPoints === 1 ? "" : "s"}`}
            </div>
            <ul className="insp-itemlist">
                {rows.map((q) => {
                    const gang = arravGang(q.id, q.stage);
                    return (
                        <li key={q.id} className="insp-item">
                            <span className="insp-item-name">{q.name}</span>
                            {q.miniquest && (
                                <span
                                    className="q-badge q-mini"
                                    title="miniquest (server minigame): no quest points, and its progress is derived from what the bot has been seen to do"
                                >
                                    mini
                                </span>
                            )}
                            {gang && (
                                <span
                                    className={"q-badge " + arravGangClass(gang)}
                                    title={`gang: ${gang} (stage ${q.stage})`}
                                >
                                    {gang}
                                </span>
                            )}
                            <span className={"q-badge " + questStageClass(q.stage)}>
                                {questStageLabel(q.stage, q.stageLabel)}
                            </span>
                        </li>
                    );
                })}
            </ul>
        </div>
    );
}

function BankTab({state, defs}: {state: BotPanelState; defs: ItemDefs}) {
    const bank = state.bank;
    if (bank.length === 0) {
        let note = state.status === "ONLINE" ? "bank empty or never opened" : "no cached bank";
        if (state.bankSeen !== undefined) note = state.bankSeen ? "bank empty" : "bank contents unknown: open a bank";
        return <div className="insp-dim">{note}</div>;
    }
    return (
        <div>
            <div className="insp-count">
                {bank.length}
                {state.bankMaxSize ? `/${state.bankMaxSize}` : ""} slots
            </div>
            <ul className="insp-itemlist">
                {bank.map((it) => (
                    <li key={it.slot} className="insp-item">
                        <span className="insp-item-name">{itemName(defs, it.id)}</span>
                        <span className="insp-item-qty">{fmt(it.amount)}</span>
                    </li>
                ))}
            </ul>
        </div>
    );
}
