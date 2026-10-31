/** The chat history tab labels, in tab order: rsc-c's compact set (src/ui/message-tabs.c). */
export const RSC_TAB_LABELS: readonly string[] = ["All", "Chat", "Quest", "Private"];

/**
 * The colour tag the client puts before a message without an override, by message-type id.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/enumerations/MessageType.java#L4 MessageType.GAME
 */
export const RSC_TYPE_COLOUR: Readonly<Record<number, string>> = {
    0: "@whi@",
    1: "@cya@",
    2: "@cya@",
    3: "@whi@",
    4: "@yel@",
    5: "@cya@",
    6: "@whi@",
    7: "@whi@",
    8: "@yel@",
    9: "@yel@",
};

/** Indices into {@link RSC_TAB_LABELS}. */
export const RSC_TAB_ALL = 0;
const RSC_TAB_CHAT = 1;
const RSC_TAB_QUEST = 2;
export const RSC_TAB_PRIVATE = 3;

/**
 * The history tab a message type also files into besides All, or null for All only. Chat, quest,
 * private and global chat go where the client files them; friend status also goes to Private here.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L15622 mudclient.showMessage
 */
export function rscTabForType(type: number): number | null {
    switch (type) {
        case 4:
            return RSC_TAB_CHAT;
        case 3:
            return RSC_TAB_QUEST;
        case 1:
        case 2:
        case 5:
        case 8:
            return RSC_TAB_PRIVATE;
        default:
            return null;
    }
}
