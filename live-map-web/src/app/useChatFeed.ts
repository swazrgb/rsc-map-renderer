import {useCallback, useState} from "react";
import type {ChatMessage, PrivateMessage, ServerMessage} from "../api";

const CHAT_CAP = 500;
const PRIVATE_CAP = 500;
const SERVER_CAP = 400;

/** What the proxy's sessions heard, kept from the map's stream: its seed, then each tick's new lines. */
export interface ChatFeed {
    chat: ChatMessage[];
    privateMessages: PrivateMessage[];
    /** Each account's server lines, oldest first. */
    serverMessages: Readonly<Record<string, ServerMessage[]>>;
    /** Takes the stream's chat events; any other is ignored. */
    onEvent: (event: string, data: unknown) => void;
    /** Forgets everything: the stream's next seed has it all again, numbered afresh if the proxy restarted. */
    onDisconnect: () => void;
}

export function useChatFeed(): ChatFeed {
    const [chat, setChat] = useState<ChatMessage[]>([]);
    const [privateMessages, setPrivateMessages] = useState<PrivateMessage[]>([]);
    const [serverMessages, setServerMessages] = useState<Record<string, ServerMessage[]>>({});
    const onEvent = useCallback((event: string, data: unknown) => {
        switch (event) {
            case "chat-history":
                setChat((data as ChatMessage[]).slice(-CHAT_CAP));
                break;
            case "chat":
                setChat((prev) => prev.concat(data as ChatMessage[]).slice(-CHAT_CAP));
                break;
            case "private-history":
                setPrivateMessages((data as PrivateMessage[]).slice(-PRIVATE_CAP));
                break;
            case "private":
                setPrivateMessages((prev) => newer(prev, data as PrivateMessage[]).slice(-PRIVATE_CAP));
                break;
            case "msgs": {
                const {bot, list} = data as {bot: string; list: ServerMessage[]};
                setServerMessages((prev) => ({...prev, [bot]: newer(prev[bot] ?? [], list).slice(-SERVER_CAP)}));
                break;
            }
        }
    }, []);
    const onDisconnect = useCallback(() => {
        setChat([]);
        setPrivateMessages([]);
        setServerMessages({});
    }, []);
    return {chat, privateMessages, serverMessages, onEvent, onDisconnect};
}

// A seed can repeat lines a reconnect already had: only those after the last one kept are new.
function newer<T extends {seq: number}>(kept: T[], fresh: T[]): T[] {
    const last = kept.length === 0 ? -1 : kept[kept.length - 1].seq;
    const add = fresh.filter((m) => m.seq > last);
    return add.length === 0 ? kept : kept.concat(add);
}
