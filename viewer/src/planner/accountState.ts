import {type PlannerRow, rowKey} from "./LoadoutTable";
import type {PlannerRequestState} from "./PlannerPanel";
import type {PlanCacheValues, PlannerInputs} from "./plannerApi";
import {combatLevelOf, freshLevels, type Levels} from "./plannerModel";

/** What the planner's editor reads of an account: its levels by skill, pack, bank, quest stages and cache values. */
export interface PlannerAccountView {
    skills: Record<string, {level: number}>;
    inventory: readonly {id: number; amount: number; equipped: boolean}[];
    bank: readonly {id: number; amount: number}[];
    quests: readonly {id: number; stage: number}[];
    cache?: PlanCacheValues;
}

/**
 * `base` with the editor filled from `account`: its levels, its pack (worn items marked), its bank and its quests,
 * with the cache values `inputs` names; the pack is also what the account arrives with.
 */
export function stateFromAccount(
    inputs: PlannerInputs | null,
    account: PlannerAccountView,
    base: PlannerRequestState,
): PlannerRequestState {
    const levels: Levels = {...freshLevels(inputs)};
    for (const [name, skill] of Object.entries(account.skills)) levels[name] = skill.level;

    const rows: PlannerRow[] = [...base.rows];
    const inventory: Record<string, number> = {};
    const bank: Record<string, number> = {};
    const equip: Record<string, boolean | undefined> = {};
    const hold = (id: number, amount: number, into: Record<string, number>, worn = false) => {
        const row: PlannerRow = {kind: "item", id};
        const key = rowKey(row);
        into[key] = (into[key] ?? 0) + amount;
        if (worn) equip[key] = true;
        if (!rows.some((r) => rowKey(r) === key)) rows.push(row);
    };
    for (const slot of account.inventory) hold(slot.id, slot.amount, inventory, slot.equipped);
    for (const slot of account.bank) hold(slot.id, slot.amount, bank);

    const quests: Record<number, number> = {};
    for (const q of account.quests) {
        if (q.stage !== 0) quests[q.id] = q.stage;
    }
    const cache: Record<string, boolean | number> = {};
    for (const entry of inputs?.cache ?? []) {
        const known = entry.kind === "int" ? account.cache?.ints[entry.key] : account.cache?.bools[entry.key];
        if (known !== undefined) cache[entry.key] = known;
    }
    return {
        ...base,
        levels,
        combatLevel: combatLevelOf(levels),
        rows,
        inventory,
        bank,
        arrive: {...inventory},
        equip,
        quests,
        cache,
    };
}
