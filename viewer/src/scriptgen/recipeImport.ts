import type {NpcSpawnInfo} from "../api";
import {byId, DEFAULT_SETTINGS, isSleep, type CampItem, type CampSettings} from "./campApi";
import type {CampState} from "./campUrl";

/**
 * The window state a recipe was generated from, read from the recipe itself or from a Plutonium
 * account file holding one; an error when `text` holds no recipe, or one whose targets are not
 * this map's spawns. Routes are left out: they are derived again from the targets. A setting the
 * recipe leaves out takes its default, and `client` is kept.
 */
export function importRecipe(
    text: string,
    spawns: readonly NpcSpawnInfo[],
    client: CampSettings["client"],
): CampState | {error: string} {
    const toml = /recipe\s*=\s*'''\n?([\s\S]*?)'''/.exec(text);
    const lines = (toml ? toml[1] : text)
        .split("\n")
        .map((l) => l.replace(/#.*/, "").trim().split(/\s+/))
        .filter((t) => t[0] !== "");
    const targets: number[] = [];
    const items: CampItem[] = [];
    const s: CampSettings = {...DEFAULT_SETTINGS, food: [], loot: [], bury: [], sleep: null, client};
    const n = (v: string | undefined) => (v != null && /^\d+$/.test(v) ? Number(v) : NaN);
    for (const [key, ...args] of lines) {
        const v = args.map(n);
        if (v.some(Number.isNaN)) {
            if (key === "item" && args.length === 4 && args[3] === "wear" && !v.slice(0, 3).some(Number.isNaN)) {
                items.push({id: v[0], min: v[1], target: v[2], wear: true});
            }
            continue;
        }
        switch (key) {
            case "target": {
                const [index, npcId] = v;
                const spawn = index < spawns.length ? spawns.at(index) : undefined;
                if (spawn?.id !== npcId) {
                    return {error: `target #${index} is not NPC ${npcId} on this map: the recipe is for another world`};
                }
                if (!targets.includes(index)) targets.push(index);
                break;
            }
            case "item":
                if (v.length >= 3) items.push({id: v[0], min: v[1], target: v[2], wear: false});
                break;
            case "style":
                if (v.length === 1) s.style = v[0];
                break;
            case "eat":
                if (v.length === 1) s.eat = v[0];
                break;
            case "sleep":
                if (v.length === 1 && isSleep(v[0])) s.sleep = v[0];
                break;
            case "food":
                s.food = v;
                break;
            case "loot":
                s.loot = v;
                break;
            case "bury":
                s.bury = v;
                break;
        }
    }
    if (targets.length === 0) return {error: "no recipe on the clipboard"};
    return {targets, settings: byId({...s, items})};
}
