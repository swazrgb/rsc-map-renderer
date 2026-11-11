import {type MouseEvent, useMemo, useState} from "react";
import {ItemIcon, type ItemAtlasIndex} from "@world3d";
import type {DuelRules, ExchangeStack, InventoryItem, ItemDefs} from "./api";
import {thousands} from "./format";
import {GameMenu, type GameMenuAt, type GameMenuEntry} from "./GameMenu";
import {
    AmountPicker,
    type CarriedStack,
    ItemSlot,
    useCarried,
    useItemAtlas,
    useItemDefsFull,
    useSlotAmount,
} from "./ItemSlots";

export interface ExchangeWindowProps {
    kind: "trade" | "duel";
    partner: string;
    mine: readonly ExchangeStack[];
    theirs: readonly ExchangeStack[];
    accepted: boolean;
    theyAccepted: boolean;
    /** The confirm screen, with the offers about to change hands. */
    confirm: boolean;
    /** A duel's rules; unset for a trade. */
    rules?: DuelRules;
    pack: readonly InventoryItem[];
    defs: ItemDefs;
    /** The most stacks a side may hold. */
    maxStacks: number;
    /**
     * Replaces the player's whole side: one stack per item and form, however many of it are offered. Rejects when the
     * move was not made.
     */
    onOffer: (mine: ExchangeStack[]) => Promise<void>;
    /** Sets the duel's rules; rejects when the move was not made. */
    onRules?: (rules: DuelRules) => Promise<void>;
    onAccept: () => void;
    /** Takes the confirm screen's accept; rejects when it was not made. */
    onConfirm: () => Promise<void>;
    onDecline: () => void;
}

const RULES: {key: keyof DuelRules; label: string; forbidden: string; allowed: string}[] = [
    {key: "noRetreat", label: "no retreating", forbidden: "No retreat is possible!", allowed: "Retreat is allowed"},
    {key: "noMagic", label: "no magic", forbidden: "Magic cannot be used", allowed: "Magic may be used"},
    {key: "noPrayer", label: "no prayer", forbidden: "Prayer cannot be used", allowed: "Prayer may be used"},
    {key: "noWeapons", label: "no weapons", forbidden: "Weapons cannot be used", allowed: "Weapons may be used"},
];

// What the game's client says of an item it will not offer:
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L17313-L17317
const UNTRADEABLE = {
    trade: "This object cannot be traded with other players",
    duel: "This object cannot be added to a duel offer",
};

// The counts the game's offer menus give, then the one typed below when it is none of them.
const MENU_AMOUNTS = [1, 5, 10];

/**
 * The bot's open trade or duel. On the first screen a click on a carried stack offers it, one on an offered stack takes
 * it back, as many as the amount chosen below; a right click offers the game's own counts. On the confirm screen the
 * offers are only shown.
 */
export function ExchangeWindow({confirm, ...props}: ExchangeWindowProps) {
    const atlas = useItemAtlas();
    return confirm ? <ConfirmScreen {...props} atlas={atlas} /> : <OfferScreen {...props} atlas={atlas} />;
}

type ScreenProps = Omit<ExchangeWindowProps, "confirm"> & {atlas: ItemAtlasIndex | null};

function OfferScreen(props: ScreenProps) {
    const {kind, partner, theirs, atlas, defs, maxStacks} = props;
    const carried = useCarried(props.pack);
    const {amount, setAmount, take} = useSlotAmount();
    const name = (id: number) => defs[String(id)] ?? `item ${id}`;
    const defsFull = useItemDefsFull();
    // Until the definitions load, every item counts as one to a stack, which never overfills the side.
    const oneStack = (id: number, noted: boolean) => noted || defsFull?.[String(id)]?.stackable === true;
    // Each move replaces the whole side, so one made before the last has come back builds on that one.
    const [mine, sendMine] = usePending(props.mine, sameStacks);
    const [rules, sendRules] = usePending(props.rules, sameRules);
    const totals = useMemo(() => summed(mine), [mine]);
    const offered = (id: number, noted: boolean) => totals.find((t) => t.id === id && t.noted === noted)?.amount ?? 0;
    const offeredAny = (id: number) => offered(id, false) + offered(id, true);
    const stacks = totals.reduce((n, t) => n + (oneStack(t.id, t.noted) ? 1 : t.amount), 0);
    const free = maxStacks - stacks;

    const change = (id: number, noted: boolean, by: number) => {
        const known = totals.some((t) => t.id === id && t.noted === noted);
        const next = (
            known
                ? totals.map((t) => (t.id === id && t.noted === noted ? {...t, amount: t.amount + by} : t))
                : [...totals, {id, amount: by, noted}]
        ).filter((t) => t.amount > 0);
        sendMine(next, props.onOffer(next));
    };
    // The server takes an offer of an item from what is carried of its form less what is offered of it in either form,
    // so a form offered after the other is held to what that one leaves:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L294-L301
    const offerable = (it: CarriedStack) => {
        const left = Math.max(0, it.count - offeredAny(it.id));
        if (oneStack(it.id, it.noted)) return offered(it.id, it.noted) > 0 || free > 0 ? left : 0;
        return Math.min(left, free);
    };
    const offer = (it: CarriedStack) => {
        const n = Math.min(take(it.count - offered(it.id, it.noted)), offerable(it));
        if (n > 0) change(it.id, it.noted, n);
    };
    const takeBack = (stack: ExchangeStack) => change(stack.id, stack.noted, -take(offered(stack.id, stack.noted)));
    const [menu, setMenu] = useState<GameMenuAt | null>(null);
    const counts =
        typeof amount === "number" && !MENU_AMOUNTS.includes(amount) ? [...MENU_AMOUNTS, amount] : MENU_AMOUNTS;
    const openMenu = (e: MouseEvent, entries: GameMenuEntry[]) => {
        e.preventDefault();
        if (entries.length > 0) setMenu({x: e.clientX, y: e.clientY, entries});
    };
    const verb = kind === "trade" ? "Offer" : "Stake";
    const offerMenu = (it: CarriedStack): GameMenuEntry[] => {
        const most = offerable(it);
        if (most <= 0) return [];
        const run = (n: number) => () => change(it.id, it.noted, Math.min(n, most));
        return [
            ...counts.map((n) => ({label: `${verb} ${n}`, name: name(it.id), run: run(n)})),
            {label: `${verb} All`, name: name(it.id), run: run(most)},
        ];
    };
    const removeMenu = (stack: ExchangeStack): GameMenuEntry[] => {
        const most = offered(stack.id, stack.noted);
        const run = (n: number) => () => change(stack.id, stack.noted, -Math.min(n, most));
        return [
            ...counts.map((n) => ({label: `Remove ${n}`, name: name(stack.id), run: run(n)})),
            {label: "Remove All", name: name(stack.id), run: run(most)},
        ];
    };
    let status = "";
    if (props.accepted) status = "waiting for the other player";
    else if (props.theyAccepted) status = "the other player has accepted";

    return (
        <div className={`item-window exchange-window exchange-${kind}`} role="dialog" aria-label={kind}>
            <div className="item-window-head">
                <span className="item-window-title">
                    {kind === "trade" ? "Trade with" : "Duel with"} <span className="exchange-partner">{partner}</span>
                </span>
                <span className="exchange-status">{status}</span>
                <button type="button" className="item-window-close" title="decline" onClick={props.onDecline}>
                    ×
                </button>
            </div>
            <div className="exchange-body">
                <div className="exchange-sides">
                    <div className="exchange-label">{kind === "trade" ? "your offer" : "your stake"}</div>
                    <Side
                        stacks={mine}
                        slots={maxStacks}
                        atlas={atlas}
                        name={name}
                        onClick={takeBack}
                        onContextMenu={(e, stack) => openMenu(e, removeMenu(stack))}
                        verb="Take back"
                    />
                    <div className="exchange-label">{kind === "trade" ? "their offer" : "their stake"}</div>
                    <Side stacks={theirs} slots={maxStacks} atlas={atlas} name={name} />
                    {rules && (
                        <div className="exchange-rules">
                            {RULES.map((r) => (
                                <label key={r.key} className="row">
                                    <input
                                        type="checkbox"
                                        checked={rules[r.key]}
                                        onChange={(e) => {
                                            const next = {...rules, [r.key]: e.target.checked};
                                            if (props.onRules) sendRules(next, props.onRules(next));
                                        }}
                                    />
                                    {r.label}
                                </label>
                            ))}
                        </div>
                    )}
                </div>
                <div className="slot-grid exchange-pack">
                    {carried.map((it) =>
                        it.worn ? (
                            <ItemSlot
                                key={it.key}
                                atlas={atlas}
                                id={it.id}
                                worn
                                title={`${name(it.id)} (worn: take it off to offer it)`}
                            />
                        ) : defsFull?.[String(it.id)]?.untradeable ? (
                            <ItemSlot
                                key={it.key}
                                atlas={atlas}
                                id={it.id}
                                count={it.count > 1 ? it.count : undefined}
                                noted={it.noted}
                                title={UNTRADEABLE[kind]}
                            />
                        ) : (
                            <ItemSlot
                                key={it.key}
                                atlas={atlas}
                                id={it.id}
                                count={it.count > 1 ? it.count : undefined}
                                noted={it.noted}
                                title={`${verb} ${amount === "all" ? "all" : amount} ${name(it.id)} (${thousands(it.count)} ${it.noted ? "noted" : "carried"})`}
                                onClick={offerable(it) > 0 ? () => offer(it) : undefined}
                                onContextMenu={(e) => openMenu(e, offerMenu(it))}
                            />
                        ),
                    )}
                </div>
            </div>
            <div className="item-window-foot">
                <AmountPicker amount={amount} onChange={setAmount} />
                <button type="button" className="exchange-accept" disabled={props.accepted} onClick={props.onAccept}>
                    accept
                </button>
                <button type="button" className="exchange-decline" onClick={props.onDecline}>
                    decline
                </button>
            </div>
            {menu && <GameMenu menu={menu} onClose={() => setMenu(null)} />}
        </div>
    );
}

function Side(props: {
    stacks: readonly ExchangeStack[];
    slots: number;
    atlas: ItemAtlasIndex | null;
    name: (id: number) => string;
    onClick?: (stack: ExchangeStack) => void;
    onContextMenu?: (e: MouseEvent, stack: ExchangeStack) => void;
    verb?: string;
}) {
    const {stacks, slots, atlas, name, onClick, onContextMenu, verb} = props;
    return (
        <div className="slot-grid exchange-side">
            {stacks.map((s, i) => (
                <ItemSlot
                    key={i}
                    atlas={atlas}
                    id={s.id}
                    count={s.amount}
                    noted={s.noted}
                    title={`${verb ? verb + " " : ""}${name(s.id)} (${thousands(s.amount)}${s.noted ? " noted" : ""})`}
                    onClick={onClick && (() => onClick(s))}
                    onContextMenu={onContextMenu && ((e) => onContextMenu(e, s))}
                />
            ))}
            {Array.from({length: Math.max(0, slots - stacks.length)}, (_, i) => (
                <div key={`empty${i}`} className="slot slot-blank" />
            ))}
        </div>
    );
}

function ConfirmScreen(props: ScreenProps) {
    const {kind, partner, mine, theirs, atlas, defs, rules} = props;
    const [confirmed, setConfirmed] = useState(false);
    const name = (id: number) => defs[String(id)] ?? `item ${id}`;
    return (
        <div className={`item-window exchange-window exchange-${kind}`} role="dialog" aria-label={`confirm ${kind}`}>
            <div className="item-window-head">
                <span className="item-window-title">
                    {kind === "trade" ? "Confirm trade with" : "Confirm duel with"}{" "}
                    <span className="exchange-partner">{partner}</span>
                </span>
                <span className="exchange-status">{confirmed ? "waiting for the other player" : ""}</span>
                <button type="button" className="item-window-close" title="decline" onClick={props.onDecline}>
                    ×
                </button>
            </div>
            <div className="exchange-confirm">
                <List title={kind === "trade" ? "you give" : "your stake"} stacks={mine} atlas={atlas} name={name} />
                <List title={kind === "trade" ? "you get" : "their stake"} stacks={theirs} atlas={atlas} name={name} />
            </div>
            {rules && (
                <div className="exchange-rule-lines">
                    {RULES.map((r) => (
                        <div key={r.key} className={rules[r.key] ? "forbidden" : "allowed"}>
                            {rules[r.key] ? r.forbidden : r.allowed}
                        </div>
                    ))}
                </div>
            )}
            <div className="item-window-foot">
                <button
                    type="button"
                    className="exchange-accept"
                    disabled={confirmed}
                    onClick={() => {
                        setConfirmed(true);
                        void props.onConfirm().catch(() => setConfirmed(false));
                    }}
                >
                    accept
                </button>
                <button type="button" className="exchange-decline" onClick={props.onDecline}>
                    decline
                </button>
            </div>
        </div>
    );
}

function List(props: {
    title: string;
    stacks: readonly ExchangeStack[];
    atlas: ItemAtlasIndex | null;
    name: (id: number) => string;
}) {
    return (
        <div className="exchange-list">
            <div className="exchange-label">{props.title}</div>
            {props.stacks.length === 0 && <div className="exchange-nothing">nothing</div>}
            {summed(props.stacks).map((s) => (
                <div key={`${s.id}:${s.noted ? 1 : 0}`} className="exchange-line">
                    <ItemIcon atlas={props.atlas} id={s.id} box={24} />
                    <span className="exchange-line-name">
                        {props.name(s.id)}
                        {s.noted ? " (noted)" : ""}
                    </span>
                    {s.amount > 1 && <span className="exchange-line-count">{thousands(s.amount)}</span>}
                </div>
            ))}
        </div>
    );
}

/**
 * The server's value, or the one last sent while the server's has not moved since and its move has not failed: a move
 * sent before the last has come back then builds on the last.
 */
function usePending<T>(server: T, same: (a: T, b: T) => boolean): [T, (sent: T, move: Promise<void>) => void] {
    const [sent, setSent] = useState<{value: T; base: T} | null>(null);
    const moved = sent !== null && !same(server, sent.base);
    if (moved) setSent(null);
    const send = (value: T, move: Promise<void>) => {
        const pending = {value, base: server};
        setSent(pending);
        move.catch(() => setSent((s) => (s === pending ? null : s)));
    };
    return [sent && !moved ? sent.value : server, send];
}

function sameStacks(a: readonly ExchangeStack[], b: readonly ExchangeStack[]): boolean {
    const key = (s: readonly ExchangeStack[]) =>
        summed(s)
            .map((t) => `${t.id}:${t.noted ? 1 : 0}:${t.amount}`)
            .sort()
            .join(",");
    return key(a) === key(b);
}

function sameRules(a: DuelRules | undefined, b: DuelRules | undefined): boolean {
    return RULES.every((r) => a?.[r.key] === b?.[r.key]);
}

// The offer one stack per item and form, its one-to-a-stack items counted together.
function summed(stacks: readonly ExchangeStack[]): ExchangeStack[] {
    const out: ExchangeStack[] = [];
    for (const s of stacks) {
        const total = out.find((t) => t.id === s.id && t.noted === s.noted);
        if (total) total.amount += s.amount;
        else out.push({...s});
    }
    return out;
}
