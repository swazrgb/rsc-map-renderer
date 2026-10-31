/** What the in-page server runs on; `width` by `height` tiles, z with floors folded in. */
export interface ServerWorld {
    width: number;
    height: number;
    /** `[definition id, x, z, min x, min z, max x, max z]` per spawn, in server index order. */
    spawns: number[][];
    /** By NPC definition id; a spawn may name an id with no entry. */
    defs: Partial<Record<string, {level: number; aggressive: boolean}>>;
}

/** One packet the route bot sends, as the planner's simulated walker words it. */
export type BotPacket =
    | {t: "walk"; tiles: number[]}
    | {t: "attack"; npc: number}
    | {t: "atObject"; x: number; z: number; cmd: number}
    | {t: "atWall"; x: number; z: number; dir: number; cmd: number}
    | {t: "useOnObject"; x: number; z: number; item: number}
    | {t: "useOnWall"; x: number; z: number; dir: number; item: number}
    | {t: "castOnObject"; x: number; z: number; spell: number}
    | {t: "castSelf"; spell: number}
    | {t: "itemCommand"; item: number}
    | {t: "talk"; npc: number}
    | {t: "npcCommand"; npc: number; cmd: number}
    | {t: string};

/**
 * One tick of the route bot: what it is doing and the packets it sends for it. `hop` names the
 * {@link ServerTransport} a hop crosses. `pack` is `[item id, amount, worn]` per item, `bank`
 * `[item id, amount]`, and `worn` `[wield position, appearance id]` per worn piece.
 */
export interface BotTick {
    move: "walk" | "hold" | "backOff" | "wait" | "attack" | "hop" | "bank" | "arrived" | "stuck";
    packets: BotPacket[];
    why?: string;
    unsent?: string;
    hop?: string;
    path?: number[];
    pack?: [number, number, number][];
    bank?: [number, number][];
    worn?: [number, number][];
}

/**
 * A transport as the in-page server executes it: clicked on an object, a wall, an NPC or the player
 * itself. `e` is flattened `[entry x, entry z, landing x, landing z]` quadruples; `free` a landing
 * reached from anywhere.
 */
export interface ServerTransport {
    id: string;
    k: "obj" | "wall" | "npc" | "self";
    x?: number;
    z?: number;
    dir?: number;
    npcs?: number[];
    act: "click" | "use" | "cast" | "cmd" | "npcCommand";
    cmd?: number;
    item?: number;
    spell?: number;
    e?: number[];
    free?: [number, number];
}

/**
 * What the bot sees at the start of a tick. `npcs` holds five ints per NPC: server index, definition id, x, z, 1 while
 * fighting.
 */
export interface BotView {
    x: number;
    z: number;
    inCombat: boolean;
    npcs: Int32Array;
}

/** One NPC or player as the view draws it. `dir` is 0-7, 8 or 9 for a combat stance, null before it has moved. */
export interface SimEntity {
    serverIndex: number;
    id: number;
    x: number;
    z: number;
    inCombat: boolean;
    dir: number | null;
    appearance?: string;
}

const NEVER = -1_000_000;
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/uranium.conf#L59
const AGGRO_RADIUS = 1;
// An NPC attacking a player swings every other two-tick round; when the player attacked, the NPC
// swings 3 ticks after each of the player's swings and the player 1 tick after it.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/combat/CombatEvent.java#L151
const ROUND_TICKS = 2;
const ATTACKED_FIRST_SWING = 3;
const ATTACKED_ROUND = 4;
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L38
const RETREAT_AFTER = 3;
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/Npc.java#L1125
const ROAM_RADIUS = 8;
// How far outside its box an NPC may step at all.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/WalkingQueue.java#L101
const BOX_LEASH = 12;
// How far outside its box a player may be and still be chased.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L388
const CHASE_LEASH = 4;

const ROAM = 0;
const AGGRO = 1;
const COMBAT = 2;

interface Mover {
    x: number;
    z: number;
    path: number[];
    dir: number | null;
}

interface Npc extends Mover {
    si: number;
    id: number;
    minX: number;
    minZ: number;
    maxX: number;
    maxZ: number;
    aggressive: boolean;
    level: number;
    state: number;
    lastMovement: number;
    combatTimer: number;
    /** Whether the route bot is the one it last fought. */
    foughtBot: boolean;
    /** The tick its current fight started; null when not fighting. */
    fightFrom: number | null;
    /** Whether the bot started the current fight. */
    attacked: boolean;
}

interface Player extends Mover {
    si: number;
    appearance: string;
    minX: number;
    minZ: number;
    maxX: number;
    maxZ: number;
    lastMovement: number;
}

interface Bot extends Mover {
    level: number;
    opponent: Npc | null;
    combatTimer: number;
    /** Whether it last left a fight by walking away. */
    running: boolean;
    /** Packets sent, taken on the next tick. */
    packets: {p: BotPacket; hop?: string}[];
}

// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/Point.java#L373
function wildernessLevel(x: number, z: number): number {
    if (x >= 336) return 0;
    const wild = 2203 - (z + (1776 - 944 * Math.floor(z / 944)));
    return wild > 0 ? 1 + Math.floor(wild / 6) : 0;
}

// The server's sprite direction for a step: 0 north, 2 west, 4 south, 6 east.
// https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L134
function facing(dx: number, dz: number): number | null {
    if (dx === 0 && dz === 0) return null;
    const ang = Math.atan2(dx, -dz);
    return Math.round(((ang + 2 * Math.PI) % (2 * Math.PI)) / (Math.PI / 4)) & 7;
}

const random = (lo: number, hi: number) => lo + Math.floor(Math.random() * (hi - lo + 1));

/**
 * A stand-in for the game server in the page: every NPC spawn, a few roaming players and the route
 * bot, moved tick by tick by the server's rules under the Uranium config. Random, as the server is.
 * NPCs roam, aggressive ones chase and fight the bot (every hit a 0), the bot's packets are taken on
 * the tick after they are sent, and a click on a transport from one of its entries lands the bot.
 * Only an aggressive NPC blocks a step, and an NPC's step is also blocked by a player.
 *
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L85 NpcBehavior.handleRoam
 * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L672 PathValidation.isMobBlocking
 */
export class DemoServer {
    tick = 0;
    readonly npcs: Npc[];
    private npcBySpawn(i: number): Npc | undefined {
        return this.npcs[i];
    }
    readonly players: Player[] = [];
    bot: Bot | null = null;
    private readonly npcAt = new Map<number, Npc[]>();
    private readonly world: ServerWorld;
    private readonly steps: Int8Array;

    constructor(world: ServerWorld, steps: Int8Array, players: {x: number; z: number; appearance: string}[]) {
        this.world = world;
        this.steps = steps;
        this.npcs = world.spawns.map(([id, x, z, minX, minZ, maxX, maxZ], si) => {
            const def = world.defs[String(id)];
            return {
                si,
                id,
                x,
                z,
                minX,
                minZ,
                maxX,
                maxZ,
                path: [],
                dir: null,
                aggressive: def?.aggressive ?? false,
                level: def?.level ?? 0,
                state: ROAM,
                lastMovement: NEVER,
                combatTimer: NEVER,
                foughtBot: false,
                fightFrom: null,
                attacked: false,
            };
        });
        for (const n of this.npcs) this.place(n, n.x, n.z);
        players.forEach((p, i) => {
            const [x, z] = this.walkableNear(p.x, p.z);
            this.players.push({
                si: 30000 + i,
                appearance: p.appearance,
                x,
                z,
                path: [],
                dir: null,
                minX: x - 6,
                minZ: z - 6,
                maxX: x + 6,
                maxZ: z + 6,
                lastMovement: NEVER,
            });
        });
    }

    private walkableNear(x: number, z: number): [number, number] {
        for (let r = 0; r <= 8; r++) {
            for (let dz = -r; dz <= r; dz++) {
                for (let dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
                    const tx = x + dx;
                    const tz = z + dz;
                    if (
                        tx >= 0 &&
                        tz >= 0 &&
                        tx < this.world.width &&
                        tz < this.world.height &&
                        this.steps[tz * this.world.width + tx] !== 0
                    ) {
                        return [tx, tz];
                    }
                }
            }
        }
        return [x, z];
    }

    /** Puts the route bot on (x, z), replacing any, with combat level `level`. */
    spawnBot(x: number, z: number, level: number) {
        this.removeBot();
        this.bot = {x, z, path: [], dir: null, level, opponent: null, combatTimer: NEVER, running: false, packets: []};
    }

    removeBot() {
        const bot = this.bot;
        if (bot?.opponent) this.endFight(bot.opponent, bot);
        this.bot = null;
    }

    /**
     * Queues the bot's packets for the next tick. `hop` is the transport they are sent for, which stands
     * in for the dialogue answer the bot never sends when one click offers several journeys.
     */
    send(packets: BotPacket[], hop?: string) {
        if (this.bot) this.bot.packets.push(...packets.map((p) => ({p, hop})));
    }

    private transports = new Map<string, ServerTransport[]>();

    setTransports(list: ServerTransport[]) {
        this.transports.clear();
        for (const t of list) {
            const keys =
                t.k === "npc"
                    ? (t.npcs ?? []).map((n) => `npc:${n}`)
                    : t.k === "self"
                      ? ["self"]
                      : t.k === "wall"
                        ? [`wall:${t.x},${t.z}`]
                        : [`obj:${t.x},${t.z}`];
            for (const k of keys) {
                const at = this.transports.get(k);
                if (at) at.push(t);
                else this.transports.set(k, [t]);
            }
        }
    }

    /** What the bot sees: itself and the NPCs within `radius` tiles; null without a bot. */
    view(radius: number): BotView | null {
        const bot = this.bot;
        if (!bot) return null;
        const seen: number[] = [];
        for (let dz = -radius; dz <= radius; dz++) {
            for (let dx = -radius; dx <= radius; dx++) {
                if (Math.trunc(Math.sqrt(dx * dx + dz * dz)) > radius) continue;
                const here = this.npcAt.get(this.key(bot.x + dx, bot.z + dz));
                if (!here) continue;
                for (const n of here) {
                    seen.push(n.si, n.id, n.x, n.z, n.fightFrom != null ? 1 : 0);
                }
            }
        }
        return {x: bot.x, z: bot.z, inCombat: bot.opponent != null, npcs: Int32Array.from(seen)};
    }

    /** Runs one server tick: every NPC, then the players, then the bot's packets and its step. */
    step() {
        this.tick++;
        for (const n of this.npcs) {
            this.behave(n);
            if (n.fightFrom == null) this.walkStep(n, true);
        }
        for (const p of this.players) {
            this.roam(p);
            this.walkStep(p, false);
        }
        const bot = this.bot;
        if (bot) {
            const packets = bot.packets;
            bot.packets = [];
            for (const {p, hop} of packets) {
                if (this.bot !== bot) break;
                this.take(bot, p, hop);
            }
            if (!bot.opponent) this.walkStep(bot, false);
        }
    }

    entities(): {npcs: SimEntity[]; players: SimEntity[]} {
        return {
            npcs: this.npcs.map((n) => ({
                serverIndex: n.si,
                id: n.id,
                x: n.x,
                z: n.z,
                inCombat: n.fightFrom != null,
                dir: n.fightFrom != null ? 8 : n.dir,
            })),
            players: this.players.map((p) => ({
                serverIndex: p.si,
                id: 0,
                x: p.x,
                z: p.z,
                inCombat: false,
                dir: p.dir,
                appearance: p.appearance,
            })),
        };
    }

    /** The bot's facing: its last step's direction, or 9 while fighting. */
    botDir(): number | null {
        const bot = this.bot;
        return bot ? (bot.opponent ? 9 : bot.dir) : null;
    }

    private behave(n: Npc) {
        const bot = this.bot;
        if (n.fightFrom != null) {
            n.state = COMBAT;
            return;
        }
        if (n.state === AGGRO) {
            this.chase(n);
            return;
        }
        // After a fight it stands 5 ticks, then chases again if it still may.
        if (n.state === COMBAT) {
            if (this.tick - n.combatTimer < 5) return;
            if (bot && n.foughtBot && this.canAggro(n, bot)) {
                n.state = AGGRO;
            } else {
                n.state = ROAM;
            }
            return;
        }
        if (
            this.tick - n.combatTimer >= 5 &&
            n.aggressive &&
            bot &&
            Math.abs(bot.x - n.x) <= AGGRO_RADIUS &&
            Math.abs(bot.z - n.z) <= AGGRO_RADIUS &&
            this.canAggro(n, bot)
        ) {
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L119
            if (n.foughtBot && this.tick - bot.combatTimer >= 15) {
                n.foughtBot = false;
            } else {
                n.state = AGGRO;
                this.chase(n);
            }
            return;
        }
        this.roam(n);
    }

    private roam(m: Npc | Player) {
        if (this.tick - m.lastMovement < 5 || m.path.length > 0) return;
        if ("combatTimer" in m && this.tick - m.combatTimer < 5) return;
        m.lastMovement = this.tick;
        if (Math.random() < 0.5) {
            const x = random(Math.max(m.minX, m.x - ROAM_RADIUS), Math.min(m.maxX, m.x + ROAM_RADIUS));
            const z = random(Math.max(m.minZ, m.z - ROAM_RADIUS), Math.min(m.maxZ, m.z + ROAM_RADIUS));
            m.path = this.addStep(m, m.x, m.z, x, z, "aggressive" in m);
        }
        if ("foughtBot" in m) m.foughtBot = false;
    }

    private chase(n: Npc) {
        const bot = this.bot;
        if (!bot || bot.opponent) {
            n.state = ROAM;
            return;
        }
        if (
            bot.x < n.minX - CHASE_LEASH ||
            bot.x > n.maxX + CHASE_LEASH ||
            bot.z < n.minZ - CHASE_LEASH ||
            bot.z > n.maxZ + CHASE_LEASH
        ) {
            n.state = ROAM;
            return;
        }
        n.lastMovement = this.tick;
        if (this.tick - bot.combatTimer < (bot.running ? 5 : 1)) return;
        n.path = this.addStep(n, n.x, n.z, bot.x, bot.z, true);
        let cx = n.x;
        let cz = n.z;
        // It fights from where its next step would put it.
        if (n.path.length > 0 && this.canWalk(n.x, n.z, n.path[0], n.path[1], true)) {
            cx = n.path[0];
            cz = n.path[1];
        }
        const dx = bot.x - cx;
        const dz = bot.z - cz;
        if (
            Math.trunc(Math.sqrt(dx * dx + dz * dz)) <= 1 &&
            ((dx === 0 && dz === 0) || this.stepOpen(cx, cz, bot.x, bot.z))
        ) {
            this.startFight(n, bot);
        }
    }

    private canAggro(n: Npc, bot: Bot): boolean {
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L381
        const levelOk =
            bot.level < n.level * 2 + 1 || (wildernessLevel(bot.x, bot.z) > 0 && wildernessLevel(n.x, n.z) > 0);
        return (
            n.aggressive &&
            levelOk &&
            bot.x >= n.minX - CHASE_LEASH &&
            bot.x <= n.maxX + CHASE_LEASH &&
            bot.z >= n.minZ - CHASE_LEASH &&
            bot.z <= n.maxZ + CHASE_LEASH &&
            !bot.opponent &&
            this.tick - bot.combatTimer >= (bot.running ? 5 : 0)
        );
    }

    private startFight(n: Npc, bot: Bot) {
        this.place(n, bot.x, bot.z);
        this.fight(n, bot, false);
    }

    private attack(bot: Bot, n: Npc) {
        bot.dir = facing(n.x - bot.x, n.z - bot.z) ?? bot.dir;
        bot.x = n.x;
        bot.z = n.z;
        this.fight(n, bot, true);
    }

    private fight(n: Npc, bot: Bot, attacked: boolean) {
        n.path = [];
        bot.path = [];
        n.state = COMBAT;
        n.attacked = attacked;
        n.fightFrom = this.tick;
        n.foughtBot = true;
        n.combatTimer = this.tick;
        bot.opponent = n;
        bot.combatTimer = this.tick;
        bot.running = false;
    }

    private endFight(n: Npc, bot: Bot) {
        n.fightFrom = null;
        n.combatTimer = this.tick;
        bot.opponent = null;
        bot.combatTimer = this.tick;
    }

    // How many times `n` has swung in its current fight.
    private swings(n: Npc): number {
        if (n.fightFrom == null) return 0;
        const since = this.tick - n.fightFrom;
        if (n.attacked) {
            return since < ATTACKED_FIRST_SWING ? 0 : Math.floor((since - ATTACKED_FIRST_SWING) / ATTACKED_ROUND) + 1;
        }
        return Math.floor(since / (2 * ROUND_TICKS)) + 1;
    }

    private take(bot: Bot, p: BotPacket, hop?: string) {
        if ("tiles" in p) {
            this.walkRequest(bot, p.tiles);
            return;
        }
        if (p.t === "attack") {
            const n = this.npcBySpawn((p as {npc: number}).npc);
            if (
                bot.opponent ||
                !n ||
                n.fightFrom != null ||
                Math.abs(n.x - bot.x) > 1 ||
                Math.abs(n.z - bot.z) > 1 ||
                !this.stepOpen(bot.x, bot.z, n.x, n.z)
            ) {
                return;
            }
            this.attack(bot, n);
            return;
        }
        // Every other handler refuses while fighting.
        if (bot.opponent) return;
        const landing = this.crossing(bot, p, hop);
        if (landing) {
            bot.dir = facing(landing[0] - bot.x, landing[1] - bot.z) ?? bot.dir;
            bot.x = landing[0];
            bot.z = landing[1];
            bot.path = [];
        }
    }

    // Mid-fight a walk is a retreat, refused until the NPC has swung 3 times.
    private walkRequest(bot: Bot, tiles: number[]) {
        if (bot.opponent) {
            if (this.swings(bot.opponent) < RETREAT_AFTER) return;
            bot.opponent.foughtBot = true;
            this.endFight(bot.opponent, bot);
            bot.running = true;
        }
        const path: number[] = [];
        let fx = bot.x;
        let fz = bot.z;
        for (let i = 0; i + 1 < tiles.length; i += 2) {
            path.push(...this.addStep(bot, fx, fz, tiles[i], tiles[i + 1], false));
            fx = tiles[i];
            fz = tiles[i + 1];
        }
        bot.path = path;
    }

    private crossing(bot: Bot, p: BotPacket, hop?: string): [number, number] | null {
        const q = p as {
            t: string;
            x?: number;
            z?: number;
            dir?: number;
            cmd?: number;
            item?: number;
            spell?: number;
            npc?: number;
        };
        let key: string;
        let fits: (t: ServerTransport) => boolean;
        switch (q.t) {
            case "atObject":
                key = `obj:${q.x},${q.z}`;
                fits = (t) => t.act === "click" && t.cmd === q.cmd;
                break;
            case "atWall":
                key = `wall:${q.x},${q.z}`;
                fits = (t) => t.act === "click" && t.cmd === q.cmd && (t.dir == null || t.dir < 0 || t.dir === q.dir);
                break;
            case "useOnObject":
                key = `obj:${q.x},${q.z}`;
                fits = (t) => t.act === "use" && (t.item === -1 || t.item === q.item);
                break;
            case "useOnWall":
                key = `wall:${q.x},${q.z}`;
                fits = (t) =>
                    t.act === "use" &&
                    (t.item === -1 || t.item === q.item) &&
                    (t.dir == null || t.dir < 0 || t.dir === q.dir);
                break;
            case "castOnObject":
                key = `obj:${q.x},${q.z}`;
                fits = (t) => t.act === "cast" && t.spell === q.spell;
                break;
            case "castSelf":
                key = "self";
                fits = (t) => t.act === "cast" && t.spell === q.spell;
                break;
            case "itemCommand":
                key = "self";
                fits = (t) => t.act === "cmd" && t.item === q.item;
                break;
            case "npcCommand":
            case "talk": {
                const n = this.npcBySpawn(q.npc ?? -1);
                if (!n || Math.abs(n.x - bot.x) > 1 || Math.abs(n.z - bot.z) > 1) return null;
                key = `npc:${q.npc}`;
                fits = (t) => (q.t === "talk" ? t.act === "click" : t.act === "npcCommand" && t.cmd === q.cmd);
                break;
            }
            default:
                return null;
        }
        let first: [number, number] | null = null;
        for (const t of this.transports.get(key) ?? []) {
            if (!fits(t)) continue;
            const landing = this.landing(t, bot);
            if (!landing) continue;
            // One click can open several journeys; the one the bot means is the one it takes.
            if (hop == null || t.id === hop) return landing;
            first ??= landing;
        }
        return first;
    }

    private landing(t: ServerTransport, bot: Bot): [number, number] | null {
        if (t.free) return t.free;
        const e = t.e ?? [];
        for (let i = 0; i + 3 < e.length; i += 4) {
            if (e[i] === bot.x && e[i + 1] === bot.z) return [e[i + 2], e[i + 3]];
        }
        return null;
    }

    // The server's greedy path: diagonal while both flanks are open, else the open axis, else straight
    // on regardless, to be refused when walked.
    private addStep(mover: Mover, fx: number, fz: number, tx: number, tz: number, npc: boolean): number[] {
        const out: number[] = [];
        let lx = fx;
        let lz = fz;
        let diffX = tx - fx;
        let diffZ = tz - fz;
        for (let guard = 0; (diffX !== 0 || diffZ !== 0) && guard < 64; guard++) {
            const mx = Math.max(-1, Math.min(1, diffX));
            const mz = Math.max(-1, Math.min(1, diffZ));
            const canX = this.canWalk(lx, lz, tx - (diffX - mx), tz - diffZ, npc, mover);
            const canZ = this.canWalk(lx, lz, tx - diffX, tz - (diffZ - mz), npc, mover);
            if (diffX !== 0 && diffZ !== 0 && canX && canZ) {
                if (this.canWalk(lx, lz, tx - (diffX - mx), tz - (diffZ - mz), npc, mover)) {
                    diffX -= mx;
                    diffZ -= mz;
                } else if (
                    this.canWalk(tx - (diffX - mx), tz - diffZ, tx - (diffX - mx), tz - (diffZ - mz), npc, mover)
                ) {
                    diffX -= mx;
                } else if (
                    this.canWalk(tx - diffX, tz - (diffZ - mz), tx - (diffX - mx), tz - (diffZ - mz), npc, mover)
                ) {
                    diffZ -= mz;
                } else {
                    return out;
                }
            } else if (diffX !== 0 && canX) {
                diffX -= mx;
            } else if (diffZ !== 0 && canZ) {
                diffZ -= mz;
            } else {
                diffX -= mx;
                diffZ -= mz;
            }
            lx = tx - diffX;
            lz = tz - diffZ;
            out.push(lx, lz);
        }
        return out;
    }

    // A refused step drops the rest of the path.
    private walkStep(m: Mover, npc: boolean) {
        if (m.path.length === 0) return;
        const x = m.path[0];
        const z = m.path[1];
        m.path = m.path.slice(2);
        if (!this.canWalk(m.x, m.z, x, z, npc, m)) {
            m.path = [];
            return;
        }
        if (npc) {
            const n = m as Npc;
            if (x < n.minX - BOX_LEASH || x > n.maxX + BOX_LEASH || z < n.minZ - BOX_LEASH || z > n.maxZ + BOX_LEASH) {
                return;
            }
        }
        m.dir = facing(x - m.x, z - m.z) ?? m.dir;
        if (npc) this.place(m as Npc, x, z);
        else {
            m.x = x;
            m.z = z;
        }
    }

    private canWalk(fx: number, fz: number, tx: number, tz: number, npc: boolean, mover?: Mover): boolean {
        if (fx === tx && fz === tz) return true;
        if (!this.stepOpen(fx, fz, tx, tz)) return false;
        if (this.blocked(tx, tz, npc, mover)) return false;
        if (fx !== tx && fz !== tz) {
            // A diagonal between two blocked flanks is closed too.
            return !(this.blocked(tx, fz, npc, mover) && this.blocked(fx, tz, npc, mover));
        }
        return true;
    }

    private stepOpen(fx: number, fz: number, tx: number, tz: number): boolean {
        const dx = tx - fx;
        const dz = tz - fz;
        if (dx < -1 || dx > 1 || dz < -1 || dz > 1 || (dx === 0 && dz === 0)) return false;
        if (fx < 0 || fz < 0 || fx >= this.world.width || fz >= this.world.height) return false;
        const d = (dz + 1) * 3 + dx + 1;
        const bit = d < 4 ? d : d - 1;
        return (this.steps[fz * this.world.width + fx] & (1 << bit)) !== 0;
    }

    // An aggressive NPC blocks anyone; with none there, a player blocks an NPC.
    private blocked(x: number, z: number, npc: boolean, mover?: Mover): boolean {
        const here = this.npcAt.get(this.key(x, z));
        if (here?.some((n) => n !== mover)) {
            return here.some((n) => n !== mover && n.aggressive);
        }
        if (!npc) return false;
        const bot = this.bot;
        if (bot?.x === x && bot.z === z) return true;
        return this.players.some((p) => p.x === x && p.z === z);
    }

    private key(x: number, z: number): number {
        return z * this.world.width + x;
    }

    private place(n: Npc, x: number, z: number) {
        const from = this.npcAt.get(this.key(n.x, n.z));
        if (from) {
            const i = from.indexOf(n);
            if (i >= 0) from.splice(i, 1);
            if (from.length === 0) this.npcAt.delete(this.key(n.x, n.z));
        }
        n.x = x;
        n.z = z;
        const k = this.key(x, z);
        const to = this.npcAt.get(k);
        if (to) to.push(n);
        else this.npcAt.set(k, [n]);
    }
}
