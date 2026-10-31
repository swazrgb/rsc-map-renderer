import {useEffect, useRef, useState} from "react";
import {fetchWearables} from "./api";
import {wornItems} from "./World3DView";

/** The kinds of pick the view itself reports. */
type PickKind = "player" | "bot" | "npc" | "object" | "boundary";

/** What a click in the world landed on, as the view reports it. */
export interface PickedEntity {
    /** Any string, so an overlay can report picks of its own kinds. */
    kind: PickKind | (string & {});
    /** Null when it has no name yet or carries only a definition id. */
    name: string | null;
    /** The definition id of an NPC, scenery or boundary object; null for a player. */
    npcId: number | null;
    /** An NPC's server index; absent or null for anything else. */
    serverIndex?: number | null;
    inCombat: boolean;
    /** A player's appearance token. */
    appearance?: string | null;
}

/** One labelled line of a card; a click copies `value`. */
export interface CardRow {
    label: string;
    value: string;
}

/**
 * A minimal card for a pick: its name, then `details` rows or else its kind, and what a player is
 * wearing. `wearables` maps sprite to item names; when undefined the card fetches it.
 */
export function EntityCard({
    pick,
    onClose,
    wearables,
    details,
}: {
    pick: PickedEntity;
    onClose: () => void;
    details?: readonly CardRow[];
    wearables?: Record<number, string[]> | null;
}) {
    const [fetched, setFetched] = useState<Record<number, string[]> | null>(null);
    useEffect(() => {
        if (wearables !== undefined) return;
        let live = true;
        fetchWearables()
            .then((w) => live && setFetched(w))
            .catch(() => {
                // No wearables list: the card shows without it.
            });
        return () => {
            live = false;
        };
    }, [wearables]);
    const [flash, setFlash] = useState<{pick: PickedEntity; label: string; text: string} | null>(null);
    const flashTimerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
    const mountedRef = useRef(true);
    useEffect(() => {
        mountedRef.current = true;
        return () => {
            mountedRef.current = false;
            clearTimeout(flashTimerRef.current);
        };
    }, []);
    const copy = (row: CardRow) => {
        const say = (text: string) => {
            if (!mountedRef.current) return;
            setFlash({pick, label: row.label, text});
            clearTimeout(flashTimerRef.current);
            flashTimerRef.current = setTimeout(() => setFlash(null), 900);
        };
        // Typed as always present; it is absent outside a secure context.
        const clipboard = navigator.clipboard as Clipboard | undefined;
        if (!clipboard) {
            say("can't copy here");
            return;
        }
        clipboard.writeText(row.value).then(
            () => say("copied"),
            () => say("copy refused"),
        );
    };
    const flashed = flash?.pick === pick ? flash : null;
    const worn = pick.appearance ? wornItems(pick.appearance, wearables !== undefined ? wearables : fetched) : null;
    return (
        <div
            style={{
                minWidth: 180,
                pointerEvents: "auto",
                background: "rgba(16,16,26,.92)",
                padding: "8px 12px",
                borderRadius: 8,
                fontSize: 13,
                fontFamily: "monospace",
                border: "1px solid #3a4664",
            }}
        >
            <div style={{display: "flex", justifyContent: "space-between", gap: 12}}>
                <b>{pick.name ?? `${pick.kind} #${pick.npcId ?? "?"}`}</b>
                <span style={{cursor: "pointer", opacity: 0.7}} onClick={onClose}>
                    ✕
                </span>
            </div>
            {details ? (
                <div style={{marginTop: 4, maxWidth: 300}}>
                    {details.map((row) => (
                        <div
                            key={row.label}
                            title="click to copy"
                            onClick={() => copy(row)}
                            style={{display: "flex", gap: 8, lineHeight: "18px", cursor: "copy"}}
                        >
                            <span style={{opacity: 0.55, width: 64, flexShrink: 0}}>{row.label}</span>
                            <span style={{overflowWrap: "anywhere"}}>
                                {flashed?.label === row.label ? (
                                    <span style={{color: "#ffd76a"}}>{flashed.text}</span>
                                ) : (
                                    row.value
                                )}
                            </span>
                        </div>
                    ))}
                </div>
            ) : (
                <div style={{opacity: 0.85, marginTop: 4}}>
                    {pick.kind}
                    {pick.npcId != null ? ` · id ${pick.npcId}` : ""}
                    {pick.inCombat ? " · in combat" : ""}
                </div>
            )}
            {worn && (
                <div style={{marginTop: 6, borderTop: "1px solid #3a4664", paddingTop: 6, maxWidth: 260}}>
                    {worn.map((w) => (
                        <div key={w.slot} style={{display: "flex", gap: 8, lineHeight: "18px"}}>
                            <span style={{opacity: 0.55, width: 52, flexShrink: 0}}>{w.slot}</span>
                            <span>{w.names}</span>
                        </div>
                    ))}
                    {worn.length === 0 && <div style={{opacity: 0.55}}>nothing equipped</div>}
                </div>
            )}
        </div>
    );
}
