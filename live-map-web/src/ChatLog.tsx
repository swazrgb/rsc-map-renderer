import {memo, useCallback, useMemo, useState, type ReactNode} from "react";
import {RSC_TAB_ALL, RSC_TAB_LABELS, RSC_TAB_PRIVATE, RSC_TYPE_COLOUR, rscTabForType, stripRscMarkup} from "@world3d";
import type {ChatMessage, PrivateMessage, ServerMessage} from "./api";
import {useToday} from "./clock";
import {errorMessage} from "./errors";
import {dayKey, hhmmss} from "./format";
import {entry} from "./lookup";
import {RscText} from "./RscText";
import {RowMenu} from "./RowMenu";

const chatTime = hhmmss;

const EMPTY: string[] = [];

interface Row {
    key: string;
    at: number;
    sender?: string;
    body: string;
    chat?: ChatMessage;
    pm?: PrivateMessage;
    jumpBot: string | null;
    jumpLabel: string;
}

function caption(text: string): string {
    const plain = stripRscMarkup(text).trim();
    return plain.length > 40 ? plain.slice(0, 39) + "…" : plain;
}

interface ChatLogProps {
    chat: ChatMessage[];
    privateMessages: PrivateMessage[];
    serverMessages: ServerMessage[];
    selectedBot: string | null;
    canControl: boolean;
    /** Says `message` as `bot`, in public chat or, from `::`, as a command. */
    onSay: (bot: string, message: string) => Promise<void>;
    onTell: (bot: string, to: string, message: string) => Promise<void>;
    onPanToPlayer: (username: string, senderIndex: number) => void;
    onSelectBot: (username: string) => void;
    /** Given, a line's time scrubs the map to it. */
    onJumpToMoment?: (tsMs: number, anchorBot: string | null, label: string) => void;
    hidden?: boolean;
}

function serverLine(m: ServerMessage): string {
    const colour = m.colour !== null && m.colour !== "" ? m.colour : (entry(RSC_TYPE_COLOUR, m.type) ?? "@whi@");
    return colour + m.text;
}

function chatSender(m: ChatMessage): string {
    if (m.kill !== null) return `@red@${m.sender}`;
    return m.global ? `@cya@Global$@ora@[@gre@${m.sender}@ora@]` : `@yel@${m.sender}`;
}

function chatBody(m: ChatMessage): string {
    if (m.kill !== null) return `@whi@ killed @red@${m.text} @whi@(${m.kill})`;
    return m.global ? `@ora@: @cya@${m.text}` : `@yel@: @yel@${m.text}`;
}

function pmEnds(pm: PrivateMessage): {from: string; to: string} {
    return pm.sent ? {from: pm.bot, to: pm.peer} : {from: pm.peer, to: pm.bot};
}

function plainRow(row: Row): string {
    if (row.pm) {
        const {from, to} = pmEnds(row.pm);
        return `${chatTime(row.at)} ${from} tells ${to}${stripRscMarkup(row.body)}`;
    }
    return `${chatTime(row.at)} ${stripRscMarkup(row.sender ?? "")}` + stripRscMarkup(row.body);
}

function mergeByTime(a: Row[], b: Row[]): Row[] {
    if (a.length === 0) return b;
    if (b.length === 0) return a;
    const out: Row[] = [];
    let i = 0;
    let j = 0;
    while (i < a.length && j < b.length) out.push(a[i].at <= b[j].at ? a[i++] : b[j++]);
    while (i < a.length) out.push(a[i++]);
    while (j < b.length) out.push(b[j++]);
    return out;
}

function PrivateHeader({
    pm,
    onReply,
    onSelectBot,
}: {
    pm: PrivateMessage;
    onReply: (pm: PrivateMessage) => void;
    onSelectBot: (username: string) => void;
}) {
    const peer = (
        <button
            type="button"
            key="peer"
            className="rsc-sender"
            title={`reply to ${pm.peer} as ${pm.bot}`}
            onClick={() => onReply(pm)}
        >
            <RscText text={`@cya@${pm.peer}`} />
        </button>
    );
    const bot = (
        <button
            type="button"
            key="bot"
            className="rsc-sender rsc-pm-bot"
            title={`${pm.bot} is ours — click to focus it`}
            onClick={() => onSelectBot(pm.bot)}
        >
            <RscText text={`@whi@${pm.bot}`} />
        </button>
    );
    return (
        <>
            {pm.sent ? bot : peer}
            <RscText text="@cya@ tells " />
            {pm.sent ? peer : bot}
        </>
    );
}

interface ChatRowProps {
    row: Row;
    onJumpToMoment?: (tsMs: number, anchorBot: string | null, label: string) => void;
    onPanToPlayer: (username: string, senderIndex: number) => void;
    onSelectBot: (username: string) => void;
    onReply: (pm: PrivateMessage) => void;
}

function sameRow(a: ChatRowProps, b: ChatRowProps): boolean {
    return (
        a.row.key === b.row.key &&
        a.row.at === b.row.at &&
        a.row.body === b.row.body &&
        a.row.sender === b.row.sender &&
        a.row.chat === b.row.chat &&
        a.row.pm === b.row.pm &&
        a.row.jumpBot === b.row.jumpBot &&
        a.row.jumpLabel === b.row.jumpLabel &&
        a.onJumpToMoment === b.onJumpToMoment &&
        a.onPanToPlayer === b.onPanToPlayer &&
        a.onSelectBot === b.onSelectBot &&
        a.onReply === b.onReply
    );
}

const ChatRow = memo(function ChatRow({row, onJumpToMoment, onPanToPlayer, onSelectBot, onReply}: ChatRowProps) {
    const stamp = chatTime(row.at);
    const clipboardText = useCallback(() => plainRow(row), [row]);
    const chat = row.chat;

    return (
        <div className="rsc-row">
            <span className="rsc-line">
                {onJumpToMoment ? (
                    <button
                        type="button"
                        className="rsc-time"
                        title={
                            row.jumpBot
                                ? `scrub the map to ${stamp} and pan to ${row.jumpBot}`
                                : `scrub the map to ${stamp}`
                        }
                        onClick={() => onJumpToMoment(row.at, row.jumpBot, row.jumpLabel)}
                    >
                        {stamp}
                    </button>
                ) : (
                    <span className="rsc-time rsc-time-still">{stamp}</span>
                )}{" "}
                {row.pm && <PrivateHeader pm={row.pm} onReply={onReply} onSelectBot={onSelectBot} />}
                {row.sender && chat && (
                    <button
                        type="button"
                        className="rsc-sender"
                        title={
                            chat.outgoing
                                ? `you sent this as ${chat.sender} — click to focus it`
                                : chat.kill !== null
                                  ? "the killer — click to pan the map to this player, if one of ours can see them"
                                  : chat.global
                                    ? "global chat — click to pan the map to this player, if one of ours can see them"
                                    : "click to pan the map to this player"
                        }
                        onClick={() => {
                            if (chat.outgoing) onSelectBot(chat.sender);
                            else onPanToPlayer(chat.sender, chat.senderIndex);
                        }}
                    >
                        <RscText text={row.sender} />
                    </button>
                )}
                <RscText text={row.body} />
            </span>
            <RowMenu
                text={clipboardText}
                heardBy={row.chat && !row.chat.outgoing ? row.chat.heardBy : EMPTY}
                onSelectBot={onSelectBot}
            />
        </div>
    );
}, sameRow);

function DaySeparator({day}: {day: string}) {
    return (
        <div className="rsc-day" role="separator" aria-label={day}>
            {day}
        </div>
    );
}

export const ChatLog = memo(function ChatLog({
    chat,
    privateMessages,
    serverMessages,
    selectedBot,
    canControl,
    onSay,
    onTell,
    onPanToPlayer,
    onSelectBot,
    onJumpToMoment,
    hidden,
}: ChatLogProps) {
    const [collapsed, setCollapsed] = useState(false);
    const [tab, setTab] = useState<number>(RSC_TAB_ALL);
    const [draft, setDraft] = useState("");
    const [pmTo, setPmTo] = useState("");
    const [sendError, setSendError] = useState<string | null>(null);

    const tabs = useMemo<Row[][]>(() => {
        const chatRows: Row[] = chat.map((m, i) => ({
            key: `c${m.timestamp}-${i}`,
            at: m.timestamp,
            sender: chatSender(m),
            body: chatBody(m),
            chat: m,
            jumpBot: m.outgoing
                ? m.sender
                : m.global || m.kill !== null
                  ? null
                  : selectedBot && m.heardBy.includes(selectedBot)
                    ? selectedBot
                    : (m.heardBy[0] ?? null),
            jumpLabel: m.kill !== null ? `${m.sender} killed ${m.text}` : `${m.sender}: ${caption(m.text)}`,
        }));
        const serverRows: Row[] = selectedBot
            ? serverMessages.map((m) => ({
                  key: `s${m.seq}`,
                  at: m.timestamp,
                  body: serverLine(m),
                  jumpBot: selectedBot,
                  jumpLabel: `${selectedBot}: ${caption(m.text)}`,
              }))
            : [];
        const pmRows: Row[] = privateMessages.map((m) => ({
            key: `p${m.seq}`,
            at: m.timestamp,
            body: `@cya@: ${m.text}`,
            pm: m,
            jumpBot: m.bot,
            jumpLabel: `${m.peer} ⇄ ${m.bot}: ${caption(m.text)}`,
        }));

        const byTab: Row[][] = [[], [...chatRows], [], []];
        for (let i = 0; i < serverRows.length; i++) {
            const t = rscTabForType(serverMessages[i].type);
            if (t != null) byTab[t].push(serverRows[i]);
        }
        byTab[RSC_TAB_ALL] = mergeByTime(mergeByTime(chatRows, serverRows), pmRows);
        byTab[RSC_TAB_PRIVATE] = mergeByTime(byTab[RSC_TAB_PRIVATE], pmRows);
        return byTab;
    }, [chat, privateMessages, serverMessages, selectedBot]);

    const editDraft = (text: string) => {
        setDraft(text);
        setSendError(null);
    };
    const editPmTo = (name: string) => {
        setPmTo(name);
        setSendError(null);
    };

    const composingPrivate = tab === RSC_TAB_PRIVATE;
    const recipient = pmTo.trim();
    const command = /^::\S/.test(draft.trim());
    const canSend = !!selectedBot && !!draft.trim() && (command || !composingPrivate || !!recipient);

    const submit = () => {
        const text = draft.trim();
        if (!text || !selectedBot) return;
        if (composingPrivate && !command) {
            if (!recipient) return;
            editDraft("");
            onTell(selectedBot, recipient, text).catch((e: unknown) => setSendError(errorMessage(e)));
            return;
        }
        editDraft("");
        onSay(selectedBot, text).catch((e: unknown) => setSendError(errorMessage(e)));
    };

    const replyTo = useCallback(
        (pm: PrivateMessage) => {
            setPmTo(pm.peer);
            setSendError(null);
            onSelectBot(pm.bot);
            setTab(RSC_TAB_PRIVATE);
        },
        [onSelectBot],
    );

    const rows = tabs[tab];
    const today = useToday();
    const lines: ReactNode[] = [];
    let day = "";
    let latest = "";
    for (const row of rows) {
        const rowDay = dayKey(row.at);
        if (rowDay !== day) {
            day = rowDay;
            if (day > latest) latest = day;
            lines.push(<DaySeparator key={`d${row.key}`} day={day} />);
        }
        lines.push(
            <ChatRow
                key={row.key}
                row={row}
                onJumpToMoment={onJumpToMoment}
                onPanToPlayer={onPanToPlayer}
                onSelectBot={onSelectBot}
                onReply={replyTo}
            />,
        );
    }
    if (latest < today) lines.push(<DaySeparator key="today" day={today} />);

    return (
        <div id="chatlog" className={(collapsed ? "collapsed" : "") + (hidden ? " hidden" : "")}>
            {!collapsed && (
                <div className="rsc-chat-msgs">
                    <div className="rsc-chat-rows">
                        {rows.length === 0 ? <div className="chat-empty">no messages…</div> : lines}
                    </div>
                </div>
            )}
            <div className="rsc-chat-bar">
                {!collapsed && canControl && (
                    <div className="rsc-chat-compose">
                        {composingPrivate && (
                            <input
                                type="text"
                                className="rsc-pm-to"
                                value={pmTo}
                                disabled={!selectedBot}
                                placeholder="to…"
                                title="who this private message goes to — click a name in the log to fill it"
                                onChange={(e) => editPmTo(e.target.value)}
                                onKeyDown={(e) => {
                                    if (e.key === "Enter") submit();
                                }}
                            />
                        )}
                        <input
                            type="text"
                            value={draft}
                            disabled={!selectedBot}
                            placeholder={
                                !selectedBot
                                    ? "select a bot to chat"
                                    : composingPrivate
                                      ? recipient
                                          ? `tell ${recipient}, as ${selectedBot}…`
                                          : `reply as ${selectedBot} — name who to…`
                                      : `say as ${selectedBot}…`
                            }
                            onChange={(e) => editDraft(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === "Enter") submit();
                            }}
                        />
                        <button type="button" disabled={!canSend} onClick={submit}>
                            {composingPrivate ? "tell" : "send"}
                        </button>
                    </div>
                )}
                {!collapsed && canControl && sendError && <div className="chat-error">{sendError}</div>}
                <div className="rsc-chat-tabs">
                    {collapsed ? (
                        <button
                            type="button"
                            className="rsc-tab rsc-tab-open"
                            title={`open the chat panel (${RSC_TAB_LABELS[tab]})`}
                            onClick={() => setCollapsed(false)}
                        >
                            chat ▶
                        </button>
                    ) : (
                        RSC_TAB_LABELS.map((label, i) => (
                            <button
                                type="button"
                                key={label}
                                className={"rsc-tab" + (i === tab ? " sel" : "")}
                                title={i === tab ? "click again to collapse" : undefined}
                                onClick={() => {
                                    if (i === tab) setCollapsed(true);
                                    else setTab(i);
                                }}
                            >
                                {label}
                            </button>
                        ))
                    )}
                </div>
            </div>
        </div>
    );
});
