import {importRecipe} from "./recipeImport";
import {itemEntries, searchItems} from "../search/itemSearch";
import {useEffect, useMemo, useState} from "react";
import {FloatingWindow} from "../FloatingWindow";
import {getItemAtlas, ItemIcon, type ItemAtlasIndex} from "../ItemIcon";
import type {NpcDefInfo, NpcSpawnInfo} from "../api";
import type {ItemNames} from "../planner/plannerApi";
import type {WasmPlannerHost} from "../planner/wasmPlannerHost";
import {
    byId,
    DEFAULT_SETTINGS,
    proposedEat,
    type CampItem,
    type CampRequest,
    type CampResponse,
    type CampSettings,
} from "./campApi";
import {CLIENTS, generate, type Client} from "./generate";
import {fetchNpcDrops, possibleDrops, type NpcDrops} from "./drops";
import "./scriptgen.css";

const SLEEPING_BAG = 1263;

const STYLES = ["controlled", "aggressive", "accurate", "defensive"];

export interface GeneratePanelProps {
    host: WasmPlannerHost | null;
    spawns: readonly NpcSpawnInfo[];
    npcDefs: ReadonlyMap<number, NpcDefInfo> | null;
    itemNames: ItemNames | null;
    /** The items that may be worn and the items that may be eaten; null while unknown. */
    wearables: ReadonlySet<number> | null;
    edibles: ReadonlySet<number> | null;
    /** The spawns to camp, by server index. */
    targets: number[];
    onTargets: (targets: number[]) => void;
    /** Whether a left drag on the map sweeps a rectangle whose spawns join the targets. */
    sweeping: boolean;
    onSweeping: (on: boolean) => void;
    settings: CampSettings;
    onSettings: (update: (settings: CampSettings) => CampSettings) => void;
    onResult: (result: CampResponse | null) => void;
    onFocus: (tile: {x: number; z: number}) => void;
    onClose: () => void;
}

export function GeneratePanel(props: GeneratePanelProps) {
    const {host, spawns, npcDefs, itemNames, targets, settings, onResult} = props;
    const set = (patch: Partial<CampSettings>) => props.onSettings((s) => byId({...s, ...patch}));
    const [answer, setAnswer] = useState<{request: CampRequest; result: CampResponse} | null>(null);
    const [atlas, setAtlas] = useState<ItemAtlasIndex | null>(null);
    const [importError, setImportError] = useState<string | null>(null);
    const importFromClipboard = () =>
        void navigator.clipboard.readText().then(
            (text) => {
                const state = importRecipe(text, spawns, settings.client);
                if ("error" in state) {
                    setImportError(state.error);
                    return;
                }
                setImportError(null);
                props.onTargets(state.targets);
                props.onSettings(() => state.settings);
            },
            () => setImportError("the browser did not allow reading the clipboard"),
        );
    const [drops, setDrops] = useState<NpcDrops | null>(null);
    useEffect(() => {
        void getItemAtlas().then(setAtlas);
        void fetchNpcDrops().then(setDrops);
    }, []);
    const [lootOver, setLootOver] = useState(1000);
    // What the targets can drop; null, suggesting everything, with no targets or no drop tables baked.
    const targetDrops = useMemo(
        () =>
            drops && targets.length > 0
                ? possibleDrops(
                      drops,
                      targets.flatMap((i) => (i < spawns.length ? [spawns[i].id] : [])),
                  )
                : null,
        [drops, targets, spawns],
    );
    const valueOf = (id: number) => drops?.values[String(id)] ?? 0;
    // The last count stays up until the next arrives, so the header keeps its width while it is asked.
    const [slots, setSlots] = useState<{used: number; of: number} | null>(null);
    // Whether the world has fatigue, from the last answer that says; a failed one does not.
    const [fatigue, setFatigue] = useState<boolean | null>(null);
    // What the recipe carries: with the sleeping bag the generator adds when it sleeps.
    const carried = useMemo(
        () =>
            settings.sleep === null || fatigue === false || settings.items.some((i) => i.id === SLEEPING_BAG)
                ? settings.items
                : [...settings.items, {id: SLEEPING_BAG, min: 1, target: 1, wear: false}],
        [settings.items, settings.sleep, fatigue],
    );
    useEffect(() => {
        if (!host) return;
        let alive = true;
        host.campSlots(carried).then(
            (s) => alive && setSlots(s),
            () => alive && setSlots(null),
        );
        return () => {
            alive = false;
        };
    }, [host, carried]);

    const maxHitOf = (index: number) => {
        const s = index < spawns.length ? spawns[index] : undefined;
        return (s && npcDefs?.get(s.id)?.maxHit) ?? 0;
    };
    const targetMaxHit = targets.reduce((m, i) => Math.max(m, maxHitOf(i)), 0);
    // The route only comes with the answer, which does not depend on the eat level.
    const stepHits = answer?.result.stepHits ?? 0;
    const proposed = proposedEat(targetMaxHit, stepHits);
    const eat = settings.eat ?? proposed;

    const request = useMemo<CampRequest>(
        () => ({
            targets,
            items: settings.items,
            food: settings.food,
            eat,
            sleep: settings.sleep,
            style: settings.style,
            loot: settings.loot,
            bury: settings.bury,
            title: null,
        }),
        [targets, settings, eat],
    );
    useEffect(() => {
        if (!host || request.targets.length === 0) return;
        let alive = true;
        const timer = setTimeout(() => {
            host.campRecipe(request).then(
                (result) => {
                    if (!alive) return;
                    setAnswer({request, result});
                    if (result.error === undefined && result.fatigue !== undefined) setFatigue(result.fatigue);
                },
                (e: unknown) =>
                    alive && setAnswer({request, result: {toBank: [], toCamp: [], warnings: [], error: String(e)}}),
            );
        }, 150);
        return () => {
            alive = false;
            clearTimeout(timer);
        };
    }, [host, request]);
    // The last answer stays up while a newer request compiles; with no targets there is none.
    const result = request.targets.length > 0 ? (answer?.result ?? null) : null;
    useEffect(() => onResult(result), [result, onResult]);

    const nameOf = (id: number) => itemNames?.[String(id)] ?? `item ${id}`;
    const world = worldOf(result);
    // A client stored from another world falls back to the first that plays this one.
    const client = CLIENTS.find((c) => c.id === settings.client && playable(c, world))?.id ?? "apos";
    const generated = useMemo(() => generatedFor(result, settings.client), [result, settings.client]);

    const setItem = (i: number, patch: Partial<CampItem>) =>
        set({items: settings.items.map((it, j) => (j === i ? {...it, ...patch} : it))});

    return (
        <FloatingWindow
            id="scriptgen"
            title="generate script"
            className="planner scriptgen"
            resizable
            anchor={{top: 8, right: 8}}
            onClose={props.onClose}
            actions={
                <button
                    type="button"
                    className="fw-btn scriptgen-import"
                    title="import: set everything from a recipe on the clipboard"
                    onClick={importFromClipboard}
                >
                    import
                </button>
            }
        >
            <div className="scriptgen-body">
                {importError && <div className="scriptgen-error">{importError}</div>}
                <section>
                    <h4 title="click NPCs on the map to add or remove them">
                        targets
                        <button
                            type="button"
                            className={`scriptgen-sweep${props.sweeping ? " active" : ""}`}
                            title="drag a rectangle on the map: every attackable NPC spawning in it joins the targets"
                            onClick={() => props.onSweeping(!props.sweeping)}
                        >
                            <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
                                <rect
                                    x="0.5"
                                    y="0.5"
                                    width="9"
                                    height="9"
                                    fill="none"
                                    stroke="currentColor"
                                    strokeDasharray="2 1.5"
                                />
                            </svg>
                        </button>
                        {targets.length > 0 && (
                            <button
                                type="button"
                                className="scriptgen-x scriptgen-clear"
                                title="remove every target"
                                onClick={() => props.onTargets([])}
                            >
                                <Cross />
                            </button>
                        )}
                    </h4>
                    {targets.length === 0 && <div className="scriptgen-empty">click NPCs on the map</div>}
                    <ul className="scriptgen-targets">
                        {targets.map((index) => {
                            const s = index < spawns.length ? spawns.at(index) : undefined;
                            const def = s ? npcDefs?.get(s.id) : undefined;
                            return (
                                <li key={index}>
                                    <button
                                        type="button"
                                        className="scriptgen-link"
                                        title={s ? `${s.x}, ${s.z}` : undefined}
                                        onClick={() => s && props.onFocus({x: s.x, z: s.z})}
                                    >
                                        {def?.name ?? s?.name ?? "?"}
                                    </button>
                                    <span className="scriptgen-dim">
                                        {def?.attackable ? `lvl ${def.combatLevel} · maxHit ${def.maxHit}` : ""} #
                                        {index}
                                        {def && def.respawnSeconds > 0 ? ` · ${def.respawnSeconds}s` : ""}
                                    </span>
                                    <button
                                        type="button"
                                        className="scriptgen-x"
                                        title="remove"
                                        onClick={() => props.onTargets(targets.filter((t) => t !== index))}
                                    >
                                        <Cross />
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                </section>

                <section className="scriptgen-row">
                    <span>camp</span>
                    <span className="scriptgen-dim">
                        {result?.camp && result.campBox
                            ? `${result.camp.x}, ${result.camp.z} · ${result.campBox.maxX - result.campBox.minX + 1}×${result.campBox.maxZ - result.campBox.minZ + 1}`
                            : "—"}
                    </span>
                </section>
                <section className="scriptgen-row">
                    <span>bank</span>
                    {result?.bankTile ? (
                        <button
                            type="button"
                            className="scriptgen-link"
                            onClick={() => result.bankTile && props.onFocus(result.bankTile)}
                        >
                            {result.bank}
                        </button>
                    ) : (
                        <span className="scriptgen-dim">—</span>
                    )}
                </section>

                <section>
                    <table className="scriptgen-items">
                        <thead>
                            <tr>
                                <th
                                    colSpan={2}
                                    className="scriptgen-carry"
                                    title="bank when an item is below its min; at the bank, withdraw it up to its target and deposit the rest"
                                >
                                    carry
                                    {slots && (
                                        <span
                                            className={slots.used > slots.of ? "scriptgen-over" : undefined}
                                            title="inventory slots taken at the targets"
                                        >
                                            {" "}
                                            ({slots.used}/{slots.of})
                                        </span>
                                    )}
                                </th>
                                <th title="bank when fewer than this are carried">min</th>
                                <th title="withdraw up to this many at the bank">target</th>
                                <th title="wear it">wear</th>
                                <th title="eat it when hits fall to the eat-at level, in this order">eat</th>
                                <th>
                                    {settings.items.length > 0 && (
                                        <button
                                            type="button"
                                            className="scriptgen-x scriptgen-clear"
                                            title="carry nothing"
                                            onClick={() => set({items: [], food: []})}
                                        >
                                            <Cross />
                                        </button>
                                    )}
                                </th>
                            </tr>
                        </thead>
                        <tbody>
                            {settings.items.map((it, i) => (
                                <tr key={it.id}>
                                    <td>
                                        <ItemIcon atlas={atlas} id={it.id} box={24} />
                                    </td>
                                    <td className="scriptgen-name">{nameOf(it.id)}</td>
                                    <td>
                                        <Count
                                            value={it.min}
                                            onChange={(n) => setItem(i, {min: n, target: Math.max(n, it.target)})}
                                        />
                                    </td>
                                    <td>
                                        <Count
                                            value={it.target}
                                            onChange={(n) => setItem(i, {target: n, min: Math.min(n, it.min)})}
                                        />
                                    </td>
                                    <td>
                                        {(it.wear || props.wearables?.has(it.id)) && (
                                            <input
                                                type="checkbox"
                                                checked={it.wear}
                                                onChange={(e) => setItem(i, {wear: e.target.checked})}
                                            />
                                        )}
                                    </td>
                                    <td>
                                        {(settings.food.includes(it.id) || props.edibles?.has(it.id)) && (
                                            <input
                                                type="checkbox"
                                                checked={settings.food.includes(it.id)}
                                                onChange={(e) =>
                                                    set({
                                                        food: e.target.checked
                                                            ? [...settings.food, it.id]
                                                            : settings.food.filter((f) => f !== it.id),
                                                    })
                                                }
                                            />
                                        )}
                                    </td>
                                    <td>
                                        <button
                                            type="button"
                                            className="scriptgen-x"
                                            title="remove"
                                            onClick={() =>
                                                set({
                                                    items: settings.items.filter((_, j) => j !== i),
                                                    food: settings.food.filter((f) => f !== it.id),
                                                })
                                            }
                                        >
                                            <Cross />
                                        </button>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                    <ItemPicker
                        names={itemNames}
                        placeholder="+ carry an item…"
                        exclude={settings.items.map((i) => i.id)}
                        onPick={(id) => set({items: [...settings.items, {id, min: 1, target: 1, wear: false}]})}
                    />
                </section>

                <section className="scriptgen-row">
                    <label
                        title={`eat at or below this many hits; proposed ${proposed}: three of the hardest target's max hit plus 5, and 5 more than a route step can hit for`}
                    >
                        eat at <Count value={eat} onChange={(n) => set({eat: n})} /> hits
                    </label>
                    {settings.eat !== null && settings.eat !== proposed && (
                        <button
                            type="button"
                            className="scriptgen-link scriptgen-dim"
                            title="use the proposed level"
                            onClick={() => set({eat: null})}
                        >
                            {proposed}
                        </button>
                    )}
                    {fatigue !== false &&
                        (settings.sleep === null ? (
                            <button
                                type="button"
                                className="scriptgen-link scriptgen-dim"
                                title="sleep, with a sleeping bag the recipe carries"
                                onClick={() => set({sleep: DEFAULT_SETTINGS.sleep})}
                            >
                                sleep
                            </button>
                        ) : (
                            <span>
                                <label title="sleep, with a sleeping bag the recipe carries, before an attack once fatigue reaches this; no experience at 100%">
                                    sleep at{" "}
                                    <Count value={settings.sleep} min={1} max={100} onChange={(n) => set({sleep: n})} />{" "}
                                    %
                                </label>
                                <button
                                    type="button"
                                    className="scriptgen-x"
                                    title="never sleep"
                                    onClick={() => set({sleep: null})}
                                >
                                    <Cross />
                                </button>
                            </span>
                        ))}
                    <select value={settings.style} onChange={(e) => set({style: Number(e.target.value)})}>
                        {STYLES.map((s, i) => (
                            <option key={s} value={i}>
                                {s}
                            </option>
                        ))}
                    </select>
                </section>

                <section>
                    <h4>
                        loot
                        {settings.loot.length > 0 && (
                            <button
                                type="button"
                                className="scriptgen-x scriptgen-clear scriptgen-clear-near"
                                title="loot nothing"
                                onClick={() => set({loot: []})}
                            >
                                <Cross />
                            </button>
                        )}
                        <span className="scriptgen-hint scriptgen-bury">
                            bury
                            {BONES.map((id) => {
                                const on = settings.bury.includes(id);
                                return (
                                    <button
                                        key={id}
                                        type="button"
                                        className={`scriptgen-toggle${on ? " active" : ""}`}
                                        aria-pressed={on}
                                        title={nameOf(id)}
                                        onClick={() =>
                                            set({
                                                bury: on
                                                    ? settings.bury.filter((b) => b !== id)
                                                    : [...settings.bury, id],
                                            })
                                        }
                                    >
                                        <ItemIcon atlas={atlas} id={id} box={20} />
                                    </button>
                                );
                            })}
                        </span>
                    </h4>
                    <div className="scriptgen-chips">
                        {settings.loot.map((id) => (
                            <span
                                key={id}
                                className="scriptgen-chip"
                                title={drops ? `${nameOf(id)} · ${valueOf(id)} gp` : nameOf(id)}
                            >
                                <ItemIcon atlas={atlas} id={id} box={20} />
                                <button
                                    type="button"
                                    className="scriptgen-x"
                                    title={`stop looting ${nameOf(id)}`}
                                    onClick={() => set({loot: settings.loot.filter((l) => l !== id)})}
                                >
                                    <Cross />
                                </button>
                            </span>
                        ))}
                    </div>
                    <ItemPicker
                        names={itemNames}
                        placeholder="+ loot an item…"
                        exclude={settings.loot}
                        only={targetDrops}
                        onPick={(id) => set({loot: [...settings.loot, id]})}
                    />
                    {targetDrops && (
                        <div
                            className="scriptgen-row scriptgen-addall"
                            title="add every item the targets can drop that is worth more than this, by its base price"
                        >
                            <span>add all &gt;</span>
                            <Count value={lootOver} onChange={setLootOver} wide />
                            <span>gp</span>
                            <button
                                type="button"
                                onClick={() =>
                                    set({
                                        loot: [
                                            ...settings.loot,
                                            ...[...targetDrops]
                                                .filter((id) => valueOf(id) > lootOver && !settings.loot.includes(id))
                                                .sort((a, b) => valueOf(b) - valueOf(a)),
                                        ],
                                    })
                                }
                            >
                                add
                            </button>
                        </div>
                    )}
                </section>

                <section>
                    {result?.error && <div className="scriptgen-error">{result.error}</div>}
                    {result?.warnings.map((w) => (
                        <div key={w} className="scriptgen-warning">
                            {w}
                        </div>
                    ))}
                    {result?.recipe && (
                        <>
                            <div className="scriptgen-file">
                                <span>recipe</span>
                                <button type="button" onClick={() => void copy(result.recipe)}>
                                    copy
                                </button>
                            </div>
                            <pre className="scriptgen-recipe">{result.recipe}</pre>
                        </>
                    )}
                    <div className="scriptgen-tabs">
                        {CLIENTS.map((c) => {
                            const off = !playable(c, world);
                            return (
                                <button
                                    key={c.id}
                                    type="button"
                                    className={client === c.id ? "active" : undefined}
                                    disabled={off}
                                    title={off ? `${c.label} plays ${c.worlds?.join(", ")} only` : undefined}
                                    onClick={() => set({client: c.id})}
                                >
                                    {c.label}
                                </button>
                            );
                        })}
                    </div>
                    {generated && "error" in generated && <div className="scriptgen-error">{generated.error}</div>}
                    {generated && "paste" in generated && (
                        <>
                            <div className="scriptgen-file">
                                <span title="the same for every recipe: install it once">{generated.camper.name}</span>
                                <button
                                    type="button"
                                    onClick={() => download(generated.camper.name, generated.camper.text)}
                                >
                                    download
                                </button>
                            </div>
                            <details>
                                <summary>install</summary>
                                <ol className="scriptgen-steps">
                                    {generated.steps.map((s) => (
                                        <li key={s}>
                                            {s
                                                .split("`")
                                                .map((part, i) => (i % 2 === 1 ? <code key={i}>{part}</code> : part))}
                                        </li>
                                    ))}
                                </ol>
                                {generated.paste !== result?.recipe && (
                                    <>
                                        <div className="scriptgen-file">
                                            <span>account file section</span>
                                            <button type="button" onClick={() => void copy(generated.paste)}>
                                                copy
                                            </button>
                                        </div>
                                        <pre className="scriptgen-recipe">{generated.paste}</pre>
                                    </>
                                )}
                            </details>
                        </>
                    )}
                </section>
            </div>
        </FloatingWindow>
    );
}

function worldOf(result: CampResponse | null): string | null {
    return result?.recipe?.match(/^world (\S+)/m)?.[1] ?? null;
}

// The files for `stored`, or for the first client playing the recipe's world when it does not.
function generatedFor(result: CampResponse | null, stored: Client) {
    if (!result?.recipe) return null;
    const world = worldOf(result);
    const client = CLIENTS.find((c) => c.id === stored && playable(c, world))?.id ?? "apos";
    try {
        return generate(client, result.recipe);
    } catch (e) {
        return {error: e instanceof Error ? e.message : String(e)};
    }
}

function copy(text: string | undefined) {
    return text == null ? Promise.resolve() : navigator.clipboard.writeText(text);
}

function Cross() {
    return (
        <svg width="8" height="8" viewBox="0 0 8 8" aria-hidden="true">
            <path d="M1 1L7 7M7 1L1 7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
    );
}

// Bones, big bones, bat bones and dragon bones, the bones the server lists for burying.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/Bones.java#L163
const BONES = [20, 413, 604, 814];

function playable(c: (typeof CLIENTS)[number], world: string | null): boolean {
    return c.worlds == null || world == null || c.worlds.includes(world);
}

function Count(props: {value: number; onChange: (n: number) => void; wide?: boolean; min?: number; max?: number}) {
    const min = props.min ?? 0;
    const max = props.max ?? Infinity;
    // What is typed while it is not a count yet, such as an emptied field; the value again on leaving it.
    const [text, setText] = useState<string | null>(null);
    return (
        <input
            className={props.wide ? "scriptgen-count wide" : "scriptgen-count"}
            type="number"
            min={min}
            max={props.max}
            // A password manager reads six small inputs without a maxlength as a one-time code's digits:
            // https://github.com/keepassxreboot/keepassxc-browser/blob/develop/keepassxc-browser/content/fields.js#L141
            maxLength={9}
            autoComplete="off"
            value={text ?? props.value}
            onChange={(e) => {
                const typed = e.target.value;
                setText(typed);
                const n = Math.floor(Number(typed));
                if (typed.trim() !== "" && Number.isFinite(n) && n >= min && n <= max) props.onChange(n);
            }}
            onBlur={() => setText(null)}
        />
    );
}

function ItemPicker(props: {
    names: ItemNames | null;
    placeholder: string;
    exclude: number[];
    /** The only items to suggest; null or absent for every item. */
    only?: ReadonlySet<number> | null;
    onPick: (id: number) => void;
}) {
    const [text, setText] = useState("");
    const entries = useMemo(() => (props.names ? itemEntries(props.names) : []), [props.names]);
    const matches = useMemo(
        () => searchItems(entries, text, 8, (id) => !props.exclude.includes(id) && (!props.only || props.only.has(id))),
        [text, entries, props.exclude, props.only],
    );
    return (
        <div className="scriptgen-picker">
            <input
                type="search"
                value={text}
                placeholder={props.placeholder}
                onChange={(e) => setText(e.target.value)}
            />
            {matches.length > 0 && (
                <ul>
                    {matches.map((m) => (
                        <li key={m.id}>
                            <button
                                type="button"
                                onClick={() => {
                                    props.onPick(m.id);
                                    setText("");
                                }}
                            >
                                {m.name} <span className="scriptgen-dim">#{m.id}</span>
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

function download(name: string, text: string): void {
    const url = URL.createObjectURL(new Blob([text], {type: "text/plain"}));
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    URL.revokeObjectURL(url);
}
