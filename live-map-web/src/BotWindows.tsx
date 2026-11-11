import type {BankMove, BankSlot, DuelMove, InventoryItem, ItemDefs, OpenWindows, TradeMove} from "./api";
import {BankWindow} from "./BankWindow";
import {ExchangeWindow} from "./ExchangeWindow";

// The most stacks a trade offer, and a duel's stake, may hold:
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerTradeHandler.java#L248
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/PlayerDuelHandler.java#L340
const TRADE_STACKS = 12;
const DUEL_STACKS = 8;

export interface BotWindowsProps {
    windows: OpenWindows;
    pack: readonly InventoryItem[];
    /** The bank's slots and capacity, as last seen; null when not known. */
    bank: readonly BankSlot[] | null;
    bankCapacity: number | null;
    defs: ItemDefs;
    /** Each makes a move; it rejects when the move was not made. */
    onBank: (move: BankMove) => Promise<void>;
    onTrade: (move: TradeMove) => Promise<void>;
    onDuel: (move: DuelMove) => Promise<void>;
}

// A move whose failure the page shows by itself.
function fire(move: Promise<void>): void {
    move.catch(() => undefined);
}

/** The bot's open bank, trade or duel screen, with the moves its buttons make. */
export function BotWindows(props: BotWindowsProps) {
    const {windows, pack, defs} = props;
    if (windows.bank) {
        return (
            <BankWindow
                bank={props.bank ?? []}
                capacity={props.bankCapacity}
                pack={pack}
                defs={defs}
                screen={windows.bank}
                onMove={(move) => fire(props.onBank(move))}
            />
        );
    }
    if (windows.trade) {
        const t = windows.trade;
        return (
            <ExchangeWindow
                kind="trade"
                partner={t.partner}
                mine={t.mine}
                theirs={t.theirs}
                accepted={t.accepted}
                theyAccepted={t.theyAccepted}
                confirm={t.confirm}
                pack={pack}
                defs={defs}
                maxStacks={TRADE_STACKS}
                onOffer={(items) => props.onTrade({action: "offer", items})}
                onAccept={() => fire(props.onTrade({action: "accept"}))}
                onConfirm={() => props.onTrade({action: "confirm"})}
                onDecline={() => fire(props.onTrade({action: "decline"}))}
            />
        );
    }
    if (windows.duel) {
        const d = windows.duel;
        return (
            <ExchangeWindow
                kind="duel"
                partner={d.opponent}
                mine={d.mine}
                theirs={d.theirs}
                accepted={d.accepted}
                theyAccepted={d.theyAccepted}
                confirm={d.confirm}
                rules={{noRetreat: d.noRetreat, noMagic: d.noMagic, noPrayer: d.noPrayer, noWeapons: d.noWeapons}}
                pack={pack}
                defs={defs}
                maxStacks={DUEL_STACKS}
                onOffer={(items) => props.onDuel({action: "offer", items})}
                onRules={(rules) => props.onDuel({action: "rules", rules})}
                onAccept={() => fire(props.onDuel({action: "accept"}))}
                onConfirm={() => props.onDuel({action: "confirm"})}
                onDecline={() => fire(props.onDuel({action: "decline"}))}
            />
        );
    }
    return null;
}
