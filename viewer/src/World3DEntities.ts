import * as THREE from "three";
import type {GameFont} from "./World3DChatFont";

/**
 * Live-entity layer for the 3D world view (piece 1: rings + nameplates).
 *
 * Every entity is a colour-coded ground ring, drawn twice: a normal
 * depth-tested pass, and an "X-ray" pass with the depth test inverted that
 * only paints where the ring is OCCLUDED (behind walls/roofs/trees), as a
 * dimmer ghost — so the swarm never disappears into a forest. Bots get HTML
 * nameplates projected onto the overlay each frame.
 *
 * Positions arrive in bot tiles once per server tick (~640ms) and are
 * interpolated toward smoothly.
 */

export type EntityKind = "bot" | "npc" | "player";

export interface Entity3D {
    key: string;
    kind: EntityKind;
    /** Bot tile coords (floor-local z). Fractional allowed. */
    x: number;
    z: number;
    name?: string | null;
    selected?: boolean;
    inCombat?: boolean;
    /** NPC def id (npc kind only) — selects the sprite. */
    npcId?: number;
    /** Appearance token (bot/player kinds) — selects the equipped sprite strip. */
    appearance?: string | null;
    /** Player combat level (player kind) — shown in the nameplate as "(lvl)". */
    combatLvl?: number | null;
    /** Player shows a PK skull — nameplate gets a skull prefix. */
    skulled?: boolean;
    /** Server sprite direction: 0-7 facing, 8/9 = combat stance A/B. */
    dir?: number | null;
    /** Current/max hitpoints when known (health bar). */
    hp?: number | null;
    maxHp?: number | null;
    /** Most recent damage splat (0 = miss) + the server tick it landed. */
    dmg?: number | null;
    dmgTick?: number | null;
    /** Latest overhead chat line + the server tick it was said (bubble). */
    msg?: string | null;
    msgTick?: number | null;
    /** Action-bubble item id + emit tick (small icon in the nameplate
     *  while gathering, held for the stock 3.0s per emit). */
    bubble?: number | null;
    bubbleTick?: number | null;
    /** Bot is fatigue-sleeping — nameplate shows a sleeping-bag icon. */
    sleeping?: boolean | null;
    /** Ghost npc: a spawn whose npc is dead (respawn pending) — translucent
     *  sprite, dim ring, countdown in the nameplate. */
    ghost?: boolean;
    /** Latest predicted tick until the ghost's npc respawns (0 = overdue) —
     *  the pop window's upper bound. */
    respawnTicks?: number | null;
    /** The pop window's lower bound (== respawnTicks for a witnessed,
     *  tick-exact timer; the gap is the stretch of time nobody was looking). */
    respawnTicksMin?: number | null;
    /** True when nobody saw the death — the window is inferred from
     *  proven-absence looks ("?" suffix in the nameplate). */
    respawnAssumed?: boolean | null;
    /** Server ticks since any bot last looked at the spawn (patrol
     *  staleness) — surfaced in the nameplate tooltip. */
    respawnCheckedAgo?: number | null;
}

const COLORS: Record<EntityKind, THREE.Color> = {
    bot: new THREE.Color(0x49a6ff),
    npc: new THREE.Color(0xff6a4d),
    player: new THREE.Color(0xffb1e0),
};
const SELECTED = new THREE.Color(0xffffff);
const COMBAT = new THREE.Color(0xff2222);
const HOVERED = new THREE.Color(0xffe45c);

const RADIUS: Record<EntityKind, number> = {
    bot: 0.62, npc: 0.45, player: 0.52,
};

// Sized for a FULL server: uranium max_players 2000 + the server's npc list
// cap 4000 (World.java: new EntityList<>(4000)); ghosts draw no rings.
// Overflow degrades gracefully (ring skipped, overlays unaffected).
const CAP = 6144;
const LERP_MS = 640;

/** Nameplate zoom tiers (view height in tiles): bots show first while
 *  zooming in, then other players, npcs only close up. */
const BOT_PLATE_ZOOM = 250;
const PLAYER_PLATE_ZOOM = 120;
const NPC_PLATE_ZOOM = 60;

/** Sleeping Bag (ItemId 1263) — the nameplate icon for a sleeping bot. */
const SLEEPING_BAG_ITEM = 1263;

interface Tracked extends Entity3D {
    fromX: number;
    fromZ: number;
    toX: number;
    toZ: number;
    t0: number;
    curX: number;
    curZ: number;
}

export class EntityLayer {
    private readonly rings: THREE.InstancedMesh;
    private readonly ghosts: THREE.InstancedMesh;
    private readonly tracked = new Map<string, Tracked>();
    private readonly plates = new Map<string, HTMLDivElement>();
    private readonly bars = new Map<string, HTMLDivElement>();
    private readonly splatDivs = new Map<string, HTMLDivElement>();
    /** Active splats: entity key -> damage + wall-clock expiry + z-order seq. */
    private readonly splats = new Map<string, {dmg: number; until: number; seq: number}>();
    private splatSeq = 0;
    /** Last dmgTick shown per entity, so one hit animates exactly once. */
    private readonly splatSeen = new Map<string, number>();
    /** Overhead chat bubbles: entity key -> text + expiry + rendered canvas. */
    private readonly bubbleDivs = new Map<string, HTMLDivElement>();
    private readonly bubbles = new Map<string, {
        msg: string; until: number;
        canvas: HTMLCanvasElement | null; baseline: number; midX: number;
        scale: number; totalWidth: number; extraLinesHeight: number;
    }>();
    /** Last msgTick shown per entity, so one line bubbles exactly once. */
    private readonly bubbleSeen = new Map<string, number>();
    /** Action-bubble icons: entity key -> item + wall-clock expiry (stock
     *  bubbleTimeout = 150 frames = 3.0s, reset on every re-emit). */
    private readonly actionBubbles = new Map<string, {id: number; until: number}>();
    private readonly actionBubbleSeen = new Map<string, number>();
    /** The baked game font (h12b); bubbles render once it has loaded. */
    chatFont: GameFont | null = null;
    private readonly plateHost: HTMLElement;
    /** Ring highlighted on hover (yellow) / selection (white). */
    hoverKey: string | null = null;
    selectedEntityKey: string | null = null;
    /** Master nameplate toggle (the "tags" checkbox). */
    showPlates = true;
    /** Per-npc sprite dims (engine units) — anchors overlays at each
     *  entity's OWN sprite top/centre, like the stock client. */
    npcDims: ((npcId: number) => {w: number; h: number} | null) | null = null;
    /** Item-atlas frame lookup for the nameplate action-bubble icon. */
    itemFrame: ((itemId: number) => {x: number; y: number; w: number;
        h: number; atlasW: number; atlasH: number} | null) | null = null;

    constructor(scene: THREE.Scene, plateHost: HTMLElement) {
        this.plateHost = plateHost;
        const geo = new THREE.RingGeometry(46, 62, 24);
        geo.rotateX(-Math.PI / 2);

        const solid = new THREE.MeshBasicMaterial({
            transparent: true,
            opacity: 0.95,
            polygonOffset: true,
            polygonOffsetFactor: -2,
            polygonOffsetUnits: -2,
            depthWrite: false,
        });
        this.rings = new THREE.InstancedMesh(geo, solid, CAP);
        this.rings.renderOrder = 10;

        // X-ray pass: GreaterDepth = draw only where something is IN FRONT.
        // depthWrite TRUE so stacked co-located rings (a pile of bots on one
        // tile) don't accumulate 0.33 alpha into an opaque ring through walls —
        // only the first at a given depth draws (see World3DPlayerSprites).
        const ghost = new THREE.MeshBasicMaterial({
            transparent: true,
            opacity: 0.33,
            depthFunc: THREE.GreaterDepth,
            depthWrite: true,
        });
        this.ghosts = new THREE.InstancedMesh(geo, ghost, CAP);
        this.ghosts.renderOrder = 11;

        for (const m of [this.rings, this.ghosts]) {
            m.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
            m.count = 0;
            m.frustumCulled = false;
            m.userData.noPick = true; // exclude from the GPU depth pick
            scene.add(m);
        }
    }

    /** Replace the entity set (call when fresh data arrives). */
    update(list: Entity3D[], now: number) {
        const seen = new Set<string>();
        for (const e of list) {
            seen.add(e.key);
            // Latch damage splats: each (entity, server tick) hit animates
            // once for ~1s — the stock splat duration (combatTimer>150 @50fps).
            if (e.dmg != null && e.dmgTick != null
                && this.splatSeen.get(e.key) !== e.dmgTick) {
                this.splatSeen.set(e.key, e.dmgTick);
                this.splats.set(e.key,
                    {dmg: e.dmg, until: now + 1000, seq: this.splatSeq++});
            }
            // Latch chat bubbles: each (entity, server tick) line shows once
            // for 3.0s — the stock messageTimeout (150 frames @ 50fps).
            if (e.msg != null && e.msgTick != null
                && this.bubbleSeen.get(e.key) !== e.msgTick) {
                this.bubbleSeen.set(e.key, e.msgTick);
                this.bubbles.set(e.key, {msg: e.msg, until: now + 3000,
                    canvas: null, baseline: 0, midX: 0, scale: 0,
                    totalWidth: 0, extraLinesHeight: 0});
            }
            // Latch the action-bubble icon: each (re-)emit holds it for the
            // stock 3.0s, so continuous gathering keeps it lit and it drops
            // 3s after the last swing — exactly the stock bubble lifetime.
            if (e.bubble != null && e.bubbleTick != null
                && this.actionBubbleSeen.get(e.key) !== e.bubbleTick) {
                this.actionBubbleSeen.set(e.key, e.bubbleTick);
                this.actionBubbles.set(e.key, {id: e.bubble, until: now + 3000});
            }
            const t = this.tracked.get(e.key);
            if (!t) {
                this.tracked.set(e.key, {
                    ...e,
                    fromX: e.x, fromZ: e.z, toX: e.x, toZ: e.z,
                    curX: e.x, curZ: e.z, t0: now,
                });
            } else {
                if ((t.ghost ?? false) !== (e.ghost ?? false)) {
                    // Ghost↔live swap (npc appeared in a bot's view, or
                    // dropped back to its spawn marker): a REPRESENTATION
                    // change, not movement — snap, never lerp the sprite
                    // sliding between spawn tile and live position.
                    t.fromX = t.toX = t.curX = e.x;
                    t.fromZ = t.toZ = t.curZ = e.z;
                    t.t0 = now - LERP_MS;
                } else if (t.toX !== e.x || t.toZ !== e.z) {
                    t.fromX = t.curX;
                    t.fromZ = t.curZ;
                    t.toX = e.x;
                    t.toZ = e.z;
                    t.t0 = now;
                }
                t.name = e.name;
                t.selected = e.selected;
                t.inCombat = e.inCombat;
                t.kind = e.kind;
                t.npcId = e.npcId;
                t.appearance = e.appearance;
                t.combatLvl = e.combatLvl;
                t.skulled = e.skulled;
                t.dir = e.dir;
                t.hp = e.hp;
                t.maxHp = e.maxHp;
                t.bubble = e.bubble;
                t.sleeping = e.sleeping;
                t.ghost = e.ghost;
                t.respawnTicks = e.respawnTicks;
                t.respawnTicksMin = e.respawnTicksMin;
                t.respawnAssumed = e.respawnAssumed;
                t.respawnCheckedAgo = e.respawnCheckedAgo;
            }
        }
        for (const key of [...this.tracked.keys()]) {
            if (!seen.has(key)) {
                this.tracked.delete(key);
                for (const pool of [this.plates, this.bars, this.splatDivs,
                                    this.bubbleDivs]) {
                    const d = pool.get(key);
                    if (d) {
                        d.remove();
                        pool.delete(key);
                    }
                }
                this.splats.delete(key);
                this.splatSeen.delete(key);
                this.bubbles.delete(key);
                this.bubbleSeen.delete(key);
                this.actionBubbles.delete(key);
                this.actionBubbleSeen.delete(key);
            }
        }
    }

    /**
     * Per-frame: lerp, write instance transforms, project nameplates.
     *
     * @param toWorld  bot tile (x,z) -> three.js world position (y = ground)
     * @param camera   for nameplate projection
     * @param canvasW,canvasH  canvas CSS size
     * @param zoomTiles  view height in tiles — plates appear in three zoom
     *     tiers (bots first, then other players, npcs only close up)
     */
    frame(now: number,
          toWorld: (x: number, z: number) => THREE.Vector3,
          groundNormal: (x: number, z: number) => THREE.Vector3,
          camera: THREE.Camera, canvasW: number, canvasH: number,
          zoomTiles: number) {
        // The camera's world matrix updates lazily during render; projecting
        // nameplates against last frame's matrix makes them jitter a frame
        // behind the canvas while orbiting. Force it current first.
        camera.updateMatrixWorld();
        // Screen px per world unit (ortho) — used to shift combat-pair
        // overlays with their sprites' ±30-unit separation.
        const oc = camera as THREE.OrthographicCamera;
        const pxPerUnit = oc.isOrthographicCamera
            ? canvasW / Math.max(1, oc.right - oc.left) : 0;
        const m = new THREE.Matrix4();
        const q = new THREE.Quaternion();
        const sc = new THREE.Vector3();
        const UP = new THREE.Vector3(0, 1, 0);
        let i = 0;
        const platesUsed = new Set<string>();
        // Nameplates are positioned AFTER the entity loop: tags sharing a
        // tile (and combat-shift side) stack as ONE tight group anchored at
        // the tallest member's sprite top — per-entity anchoring left a big
        // vertical gap between a short npc's tag and a player's on the same
        // tile. Bots stack nearest the heads, then players, then npcs.
        const platePlacements: {div: HTMLDivElement; x: number; top: number;
            kind: EntityKind; key: string; group: string}[] = [];
        // Chat bubbles are placed AFTER the entity loop: the stock client
        // resolves bubble-vs-bubble overlap in a second pass (each new bubble
        // pushed above any earlier one it collides with).
        const bubblePlacements: {div: HTMLDivElement; x: number; y: number;
            halfWidth: number; height: number; baseline: number;
            midX: number}[] = [];
        for (const t of this.tracked.values()) {
            const u = Math.min(1, (now - t.t0) / LERP_MS);
            t.curX = t.fromX + (t.toX - t.fromX) * u;
            t.curZ = t.fromZ + (t.toZ - t.fromZ) * u;
            const p = toWorld(t.curX, t.curZ);
            p.y += 6;
            // Ghost spawn markers get no ring (thousands of static spawns
            // would swamp the instance cap and the map); their translucent
            // sprite + tag carry the signal.
            if (!t.ghost && i < CAP) {
                // Tilt the ring onto the terrain so it hugs slopes instead
                // of slicing into them.
                q.setFromUnitVectors(UP, groundNormal(t.curX, t.curZ));
                const r = RADIUS[t.kind] * (t.selected ? 1.35 : 1);
                sc.set(r, 1, r);
                m.compose(p, q, sc);
                this.rings.setMatrixAt(i, m);
                this.ghosts.setMatrixAt(i, m);
                const c = t.key === this.hoverKey ? HOVERED
                    : (t.selected || t.key === this.selectedEntityKey) ? SELECTED
                    : t.inCombat ? COMBAT : COLORS[t.kind];
                this.rings.setColorAt(i, c);
                this.ghosts.setColorAt(i, c);
                i++;
            }

            // Overlays (plates/bars/splats) anchor off the projected FEET
            // plus SCREEN-px offsets: sprites are billboards extending along
            // the screen-vertical axis, so world-Y offsets undershoot by
            // cos(pitch) and sink overlays to chest height as the camera
            // tilts up. Heights use each entity's OWN sprite size (stock).
            const dims = t.kind === "npc" && t.npcId != null
                ? this.npcDims?.(t.npcId) : null;
            const spriteH = dims ? dims.h : (t.appearance ? 220 : 130);
            const vFeet = p.clone().project(camera);
            const feet = vFeet;
            const feetX = (vFeet.x * 0.5 + 0.5) * canvasW;
            const feetY = (-vFeet.y * 0.5 + 0.5) * canvasH;

            // Nameplates, three tiers: bots (bright, clickable), other
            // players (dimmer, behind bots), npcs ("Name #id,serverIndex",
            // dimmest, behind both).
            const plateText = t.kind === "player"
                // Foreign players: "☠ Name (lvl)" — skull prefix when the
                // server flags a PK skull, combat level in parens once known.
                ? (t.skulled ? "☠ " : "") + (t.name ?? "player")
                    + (t.combatLvl ? ` (${t.combatLvl})` : "")
                : t.kind === "bot"
                ? t.name
                : t.npcId != null
                    ? `${t.name ?? "npc"} #${t.npcId},${t.key.slice(4)}`
                        // Pop window: "· 42t" = witnessed, tick-exact; "· 12–31t?"
                        // = inferred window (pop lands somewhere inside; "?" =
                        // nobody saw the death). Shows WHY the trainer priced the
                        // spawn the way it did (race the lower bound vs re-check
                        // at the upper).
                        + (t.ghost && t.respawnTicks != null
                            ? (t.respawnTicksMin != null
                                    && t.respawnTicksMin !== t.respawnTicks
                                ? ` · ${t.respawnTicksMin}–${t.respawnTicks}t`
                                : ` · ${t.respawnTicks}t`)
                                + (t.respawnAssumed ? "?" : "")
                            : "")
                    : null;
            const plateZoomMax = t.kind === "bot" ? BOT_PLATE_ZOOM
                : t.kind === "player" ? PLAYER_PLATE_ZOOM : NPC_PLATE_ZOOM;
            if (plateText && this.showPlates && zoomTiles <= plateZoomMax) {
                platesUsed.add(t.key);
                let div = this.plates.get(t.key);
                if (!div) {
                    div = document.createElement("div");
                    // All tiers are clickable: the view's pointer handlers
                    // (on the host, so drags/wheel work across plates) read
                    // data-entity-key on pointerdown and select the entity
                    // when the press ends within the click threshold.
                    const common =
                        "position:absolute;transform:translate(-50%,-100%);" +
                        "pointer-events:auto;cursor:pointer;white-space:nowrap;" +
                        "border-radius:4px;";
                    if (t.kind === "bot") {
                        div.style.cssText = common +
                            "font:11px monospace;color:#e8f2ff;" +
                            "background:rgba(10,14,24,.72);padding:1px 5px;" +
                            "z-index:900000;";
                    } else if (t.kind === "player") {
                        div.style.cssText = common +
                            "font:10px monospace;color:rgba(255,216,240,.85);" +
                            "background:rgba(10,14,24,.45);padding:0 4px;" +
                            "z-index:890000;";
                    } else {
                        div.style.cssText = common +
                            "font:10px monospace;color:rgba(255,196,172,.8);" +
                            "background:rgba(10,14,24,.4);padding:0 4px;" +
                            "z-index:880000;";
                    }
                    div.dataset.entityKey = t.key;
                    // Hovering the plate = hovering the entity. The view's
                    // unified pointer path (World3DView) reads data-entity-key on
                    // pointermove and focuses this entity through the same
                    // commitHover as a scene pick, so plate and scene hover can
                    // never both be lit. (No local mouseenter/leave here — that
                    // was a second, competing highlight source.)
                    this.plateHost.appendChild(div);
                    this.plates.set(t.key, div);
                }
                // The stock client draws the gathering action bubble (pick,
                // net…) OVER the head — useless when players stack on one
                // tile, so it lives in the nameplate as a small icon instead.
                const ab = this.actionBubbles.get(t.key);
                if (ab && now > ab.until) {
                    this.actionBubbles.delete(t.key);
                }
                // Fatigue sleep shows a sleeping bag as a synthetic bubble
                // (no latch — `sleeping` is live state, not an emit).
                const bubbleId = t.sleeping ? SLEEPING_BAG_ITEM
                    : t.kind !== "npc" && ab && now <= ab.until
                        ? ab.id : null;
                const contentKey =
                    `${plateText}|${bubbleId ?? ""}|${this.itemFrame ? 1 : 0}`;
                if (div.dataset.content !== contentKey) {
                    div.dataset.content = contentKey;
                    div.textContent = "";
                    const f = bubbleId != null
                        ? this.itemFrame?.(bubbleId) : null;
                    if (f) {
                        const k = Math.min(14 / f.w, 14 / f.h);
                        const icon = document.createElement("span");
                        icon.style.cssText =
                            "display:inline-block;vertical-align:-2px;"
                            + `margin-right:3px;width:${f.w * k}px;`
                            + `height:${f.h * k}px;`
                            + "background-image:url(/api/world3d/item-atlas.png);"
                            + `background-position:${-f.x * k}px ${-f.y * k}px;`
                            + `background-size:${f.atlasW * k}px ${f.atlasH * k}px;`
                            + "image-rendering:pixelated;";
                        div.appendChild(icon);
                    }
                    div.appendChild(document.createTextNode(plateText));
                }
                // Ghost tooltip: the full decision inputs — window bounds,
                // provenance, and how stale the last look is (the patrol
                // ordering key). Cheap per-frame property set; empty for
                // non-ghosts so a recycled div can't keep a stale tip.
                div.title = t.ghost && t.respawnTicks != null
                    ? `pop in ${t.respawnTicksMin != null
                            && t.respawnTicksMin !== t.respawnTicks
                        ? `${t.respawnTicksMin}–${t.respawnTicks}`
                        : `${t.respawnTicks}`} ticks`
                        + (t.respawnAssumed
                            ? " · assumed (nobody saw the death)" : " · witnessed")
                        + (t.respawnCheckedAgo != null
                            ? ` · last looked at ${t.respawnCheckedAgo}t ago` : "")
                    : "";
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05
                    || feet.y < -1.05 || feet.y > 1.05) {
                    div.style.display = "none";
                } else {
                    // Fighters shift with their health bars (±20% depth
                    // scale, stance A left / B right), so each tag tracks
                    // its own sprite; the stance joins the group key so an
                    // A/B pair on one tile doesn't stack needlessly.
                    const plateShift = t.dir === 8 ? -51.2
                        : t.dir === 9 ? 51.2 : 0;
                    div.style.display = "";
                    platePlacements.push({
                        div,
                        x: feetX + plateShift * pxPerUnit,
                        top: feetY - spriteH * pxPerUnit - 12,
                        kind: t.kind,
                        key: t.key,
                        group: `${Math.round(t.curX)},${Math.round(t.curZ)}`
                            + `:${t.dir === 8 ? "a" : t.dir === 9 ? "b" : "-"}`,
                    });
                    div.style.outline = t.key === this.hoverKey
                        ? "1px solid #ffe45c"
                        : (t.selected || t.key === this.selectedEntityKey)
                            ? "1px solid #fff" : "";
                    // The hovered tag surfaces above every other overlay.
                    div.style.zIndex = String(t.key === this.hoverKey
                        ? 990000
                        : t.kind === "bot" ? 900000
                        : t.kind === "player" ? 890000 : 880000);
                    // Ghost spawns read greyer/fainter than live npcs (same
                    // div is reused when the npc pops, so set per frame).
                    if (t.kind === "npc") {
                        div.style.color = t.ghost
                            ? "rgba(196,200,208,.5)" : "rgba(255,196,172,.8)";
                        div.style.background = t.ghost
                            ? "rgba(10,14,24,.25)" : "rgba(10,14,24,.4)";
                    }
                }
            }

            // Health bar: any entity with known hp that is hurt or fighting.
            const barsWanted = t.maxHp != null && t.maxHp > 0 && t.hp != null
                && (t.hp < t.maxHp || t.inCombat);
            let bar = this.bars.get(t.key);
            if (barsWanted) {
                if (!bar) {
                    // Stock proportions (mudclient:2801): 30x5px, pure
                    // green/red at ~75% alpha, no border.
                    bar = document.createElement("div");
                    bar.style.cssText =
                        "position:absolute;transform:translate(-50%,-100%);" +
                        "width:30px;height:5px;background:rgba(255,0,0,.75);" +
                        "pointer-events:none;overflow:hidden;z-index:800000;";
                    const fill = document.createElement("div");
                    fill.style.cssText =
                        "height:100%;background:rgba(0,255,0,.75);width:100%;";
                    bar.appendChild(fill);
                    this.plateHost.appendChild(bar);
                    this.bars.set(t.key, bar);
                }
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05
                    || feet.y < -1.05 || feet.y > 1.05) {
                    bar.style.display = "none";
                } else {
                    // Stock (drawNPC/drawPlayer): bar sits at the sprite's
                    // projected TOP (y−3), shifted ±20% of the depth scale
                    // (256·20/100 = 51.2 world units), A left / B right.
                    const shift = t.dir === 8 ? -51.2 : t.dir === 9 ? 51.2 : 0;
                    bar.style.display = "";
                    bar.style.left = `${feetX + shift * pxPerUnit}px`;
                    bar.style.top = `${feetY - spriteH * pxPerUnit + 2}px`;
                    (bar.firstChild as HTMLDivElement).style.width =
                        `${Math.max(0, Math.min(100, (t.hp! / t.maxHp!) * 100))}%`;
                }
            } else if (bar) {
                bar.remove();
                this.bars.delete(t.key);
            }

            // Hit splat: stock red circle + white number (blue for a 0 miss),
            // centred on the chest for ~1s after the hit lands.
            const splat = this.splats.get(t.key);
            if (splat && now > splat.until) {
                this.splats.delete(t.key);
            }
            let sd = this.splatDivs.get(t.key);
            if (splat && now <= splat.until) {
                if (!sd) {
                    // The stock bubble sprite: red for players, blue for
                    // npcs (colour keys on TARGET kind, not hit/miss), with
                    // the damage in white bold ~13px just below centre.
                    sd = document.createElement("div");
                    sd.style.cssText =
                        "position:absolute;transform:translate(-50%,-50%);" +
                        "width:24px;height:24px;" +
                        "background-size:24px 24px;image-rendering:pixelated;" +
                        "font:bold 12px sans-serif;color:#fff;text-align:center;" +
                        "line-height:25px;pointer-events:none;";
                    sd.style.backgroundImage = t.kind === "npc"
                        ? "url(/api/world3d/splat-blue.png)"
                        : "url(/api/world3d/splat-red.png)";
                    this.plateHost.appendChild(sd);
                    this.splatDivs.set(t.key, sd);
                }
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05
                    || feet.y < -1.05 || feet.y > 1.05) {
                    sd.style.display = "none";
                } else {
                    // Stock: splat centred at the entity's own sprite
                    // MID-HEIGHT (y + height/2), shifted ±10% of the depth
                    // scale (256·10/100 = 25.6 world units), A left / B right.
                    const shift = t.dir === 8 ? -25.6 : t.dir === 9 ? 25.6 : 0;
                    // Above chat bubbles (700000), below bars (800000) —
                    // stock draws bars in a later pass than splats; the seq
                    // keeps newer splats over older ones.
                    sd.style.zIndex = String(750000 + splat.seq);
                    sd.style.display = "";
                    sd.style.left = `${feetX + shift * pxPerUnit}px`;
                    sd.style.top = `${feetY - (spriteH / 2) * pxPerUnit}px`;
                    sd.textContent = String(splat.dmg);
                }
            } else if (sd) {
                sd.remove();
                this.splatDivs.delete(t.key);
            }

            // Chat bubble: the latest overhead line, at the stock anchor —
            // centred at sprite mid-x, first-line BASELINE at the sprite's
            // projected top, extra wrapped lines growing downward
            // (mudclient drawNPC/drawPlayer -> drawCharacterOverlay).
            const bubble = this.bubbles.get(t.key);
            if (bubble && now > bubble.until) {
                this.bubbles.delete(t.key);
            }
            let bd = this.bubbleDivs.get(t.key);
            if (bubble && now <= bubble.until && this.chatFont) {
                // Glyphs render into a ceil(DPR)-scaled backing store CSS-
                // sized back to logical px: 1:1 canvases positioned at
                // fractional coords get nearest-neighbour column drops that
                // read as slanted "italic" text (worst at fractional DPR).
                const dpr = window.devicePixelRatio || 1;
                const glyphScale = Math.max(1, Math.ceil(dpr));
                if (!bubble.canvas || bubble.scale !== glyphScale) {
                    const r = this.chatFont.renderOverhead(
                        bubble.msg, 300, "#ff0", glyphScale);
                    bubble.canvas = r.canvas;
                    bubble.baseline = r.baseline;
                    bubble.midX = r.midX;
                    bubble.scale = glyphScale;
                    bubble.totalWidth = r.totalWidth;
                    bubble.extraLinesHeight = r.extraLinesHeight;
                }
                if (!bd) {
                    // Chat draws UNDER health bars and splats (user pref —
                    // combat state must stay readable through the chatter).
                    bd = document.createElement("div");
                    bd.style.cssText =
                        "position:absolute;pointer-events:none;" +
                        "z-index:700000;line-height:0;";
                    this.plateHost.appendChild(bd);
                    this.bubbleDivs.set(t.key, bd);
                }
                if (bd.firstChild !== bubble.canvas) {
                    bd.replaceChildren(bubble.canvas);
                }
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05
                    || feet.y < -1.05 || feet.y > 1.05) {
                    bd.style.display = "none";
                } else {
                    bd.style.display = "";
                    bubblePlacements.push({
                        div: bd,
                        x: feetX,
                        y: feetY - spriteH * pxPerUnit,
                        halfWidth: Math.min(150, bubble.totalWidth / 2),
                        height: bubble.extraLinesHeight,
                        baseline: bubble.baseline,
                        midX: bubble.midX,
                    });
                }
            } else if (bd) {
                bd.remove();
                this.bubbleDivs.delete(t.key);
            }
        }
        // Position nameplates per same-tile group: anchor at the tallest
        // member's tag height, bots nearest the heads, players above, npcs
        // on top, 15px apart.
        {
            const KIND_ORDER: Record<EntityKind, number> =
                {bot: 0, player: 1, npc: 2};
            const groups = new Map<string, typeof platePlacements>();
            for (const p of platePlacements) {
                let g = groups.get(p.group);
                if (!g) groups.set(p.group, g = []);
                g.push(p);
            }
            for (const [, members] of groups) {
                members.sort((a, b) =>
                    (KIND_ORDER[a.kind] - KIND_ORDER[b.kind])
                    || (a.key < b.key ? -1 : 1));
                const anchor = Math.min(...members.map(m => m.top));
                for (let k = 0; k < members.length; k++) {
                    members[k].div.style.left = `${members[k].x}px`;
                    members[k].div.style.top = `${anchor - k * 15}px`;
                }
            }
        }

        // Stock bubble collision (drawCharacterOverlay): each bubble is
        // pushed up above any earlier bubble it overlaps, repeating until
        // it lands in clear air.
        {
            const lineH = this.chatFont?.lineHeight ?? 14;
            // Snap to the DEVICE pixel grid: fractional offsets resample the
            // glyph canvas and warp the text.
            const dpr = window.devicePixelRatio || 1;
            const snap = (v: number) => Math.round(v * dpr) / dpr;
            for (let bi = 0; bi < bubblePlacements.length; bi++) {
                const b = bubblePlacements[bi];
                let collided = true;
                while (collided) {
                    collided = false;
                    for (let bj = 0; bj < bi; bj++) {
                        const o = bubblePlacements[bj];
                        if (o.y - lineH < b.y + b.height
                            && b.y - lineH < o.y + o.height
                            && o.x + o.halfWidth > b.x - b.halfWidth
                            && b.x + b.halfWidth > o.x - o.halfWidth
                            && o.y - b.height - lineH < b.y) {
                            b.y = o.y - (lineH + b.height);
                            collided = true;
                        }
                    }
                }
                b.div.style.left = `${snap(b.x - b.midX)}px`;
                b.div.style.top = `${snap(b.y - b.baseline)}px`;
            }
        }
        for (const [key, div] of this.plates) {
            if (!platesUsed.has(key)) {
                div.remove();
                this.plates.delete(key);
            }
        }
        this.rings.count = i;
        this.ghosts.count = i;
        this.rings.instanceMatrix.needsUpdate = true;
        this.ghosts.instanceMatrix.needsUpdate = true;
        if (this.rings.instanceColor) this.rings.instanceColor.needsUpdate = true;
        if (this.ghosts.instanceColor) this.ghosts.instanceColor.needsUpdate = true;
    }

    /** Nearest bot within `maxTiles` of a ground point (ring clicks). */
    nearestBot(x: number, z: number, maxTiles: number): string | null {
        const e = this.nearestEntity(x, z, maxTiles, "bot");
        return e ? e.key.slice(4) : null;
    }

    /** Nearest tracked entity of any (or one) kind near a ground point. */
    nearestEntity(x: number, z: number, maxTiles: number, kind?: EntityKind):
        {key: string; kind: EntityKind; name?: string | null; npcId?: number;
         appearance?: string | null;
         x: number; z: number; inCombat?: boolean} | null {
        let best: Tracked | null = null;
        let bestD = maxTiles;
        for (const t of this.tracked.values()) {
            if (kind && t.kind !== kind) continue;
            const d = Math.hypot(t.curX - x, t.curZ - z);
            // Bots win ties (they're what you usually want to click).
            const dAdj = t.kind === "bot" ? d - 0.25 : d;
            if (dAdj < bestD) {
                bestD = dAdj;
                best = t;
            }
        }
        return best ? {key: best.key, kind: best.kind, name: best.name,
            npcId: best.npcId, appearance: best.appearance,
            x: best.curX, z: best.curZ, inCombat: best.inCombat} : null;
    }

    /** Every tracked entity at its current lerped position (hit-testing). */
    current(): {key: string; kind: EntityKind; name?: string | null;
                npcId?: number; appearance?: string | null; dir?: number | null;
                x: number; z: number; inCombat?: boolean}[] {
        const out = [];
        for (const t of this.tracked.values()) {
            out.push({key: t.key, kind: t.kind, name: t.name, npcId: t.npcId,
                appearance: t.appearance, dir: t.dir, x: t.curX, z: t.curZ,
                inCombat: t.inCombat});
        }
        return out;
    }

    /** Live tracked states for other layers (sprite billboards). */
    snapshot(kind: EntityKind, now: number):
        {key: string; npcId?: number; appearance?: string | null;
         dir?: number | null; ghost?: boolean;
         x: number; z: number; moving: boolean; dx: number; dz: number}[] {
        const out = [];
        for (const t of this.tracked.values()) {
            if (t.kind !== kind) continue;
            out.push({
                key: t.key, npcId: t.npcId, appearance: t.appearance,
                dir: t.dir, ghost: t.ghost, x: t.curX, z: t.curZ,
                moving: (now - t.t0) < LERP_MS && (t.fromX !== t.toX || t.fromZ !== t.toZ),
                dx: t.toX - t.fromX, dz: t.toZ - t.fromZ,
            });
        }
        return out;
    }

    dispose(scene: THREE.Scene) {
        for (const m of [this.rings, this.ghosts]) {
            scene.remove(m);
            m.geometry.dispose();
            (m.material as THREE.Material).dispose();
        }
        for (const pool of [this.plates, this.bars, this.splatDivs,
                            this.bubbleDivs]) {
            for (const [, div] of pool) div.remove();
            pool.clear();
        }
        this.splats.clear();
        this.splatSeen.clear();
        this.bubbles.clear();
        this.bubbleSeen.clear();
        this.tracked.clear();
    }
}
