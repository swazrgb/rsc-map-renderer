const STAGE_NOT_STARTED = 0;

export const QUEST_SHIELD_OF_ARRAV = 12;

export function questCompleted(stage: number): boolean {
    return stage < 0;
}

export function questStageLabel(stage: number, serverLabel?: string | null): string {
    if (serverLabel) return serverLabel;
    if (questCompleted(stage)) return "done";
    if (stage === STAGE_NOT_STARTED) return "not started";
    return `stage ${stage}`;
}

export function questStageClass(stage: number): string {
    if (questCompleted(stage)) return "q-done";
    if (stage === STAGE_NOT_STARTED) return "q-none";
    return "q-prog";
}

export type ArravGang = "phoenix" | "black arm";

export function arravGang(questId: number, stage: number): ArravGang | null {
    if (questId !== QUEST_SHIELD_OF_ARRAV) return null;
    if (stage === -1) return "phoenix";
    if (stage === -2) return "black arm";
    return null;
}

export function arravGangClass(gang: ArravGang): string {
    return gang === "phoenix" ? "q-gang-phoenix" : "q-gang-blackarm";
}
