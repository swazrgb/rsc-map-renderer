import * as THREE from "three";
import {freezeTransform} from "./staticTransform";
import {uploadPrefix} from "./bufferUpload";
import type {GameFont} from "./World3DChatFont";
import {RSC_OVERHEAD_COLOR} from "./rscMarkup";
import {versionedNow} from "./api";

export type EntityKind = "bot" | "npc" | "player";

/**
 * One bot, NPC or player for the entity layer, at a floor-local tile (fractions allowed). The event
 * fields (`dmg`, `msg`, `bubble`) show once per server tick stamped beside them; the ring and tag
 * fields mean what they mean on MapEntity.
 */
export interface Entity3D {
    key: string;
    kind: EntityKind;
    x: number;
    z: number;
    name?: string | null;
    selected?: boolean;
    inCombat?: boolean;
    npcId?: number;
    appearance?: string | null;
    combatLvl?: number | null;
    skulled?: boolean;
    /** The shop this NPC keeps; its nameplate gets a clickable shop glyph carrying it as `data-shop-id`. */
    shopId?: string;
    /** The server's facing, 0-7, or 8 and 9 for the combat stances. */
    dir?: number | null;
    hp?: number | null;
    maxHp?: number | null;
    dmg?: number | null;
    dmgTick?: number | null;
    msg?: string | null;
    msgTick?: number | null;
    bubble?: number | null;
    bubbleTick?: number | null;
    sleeping?: boolean | null;
    /** A jump counter: when it changes the entity is placed, not glided. */
    snapSeq?: number;
    ringColor?: number;
    ringColorFirst?: boolean;
    tagColor?: number;
    tagAlways?: boolean;
    tagOutline?: number;
    tagStrong?: boolean;
    /** A bot no longer live, at the tile it was last seen on: grey ring and plate. */
    offline?: boolean;
    /** A spawn whose NPC is dead: no ring, a faint plate with the respawn countdown. */
    ghost?: boolean;
    /**
     * The respawn window in ticks from now, `respawnTicksMin` to `respawnTicks` (0 overdue);
     * `respawnAssumed` when nobody saw the death, `respawnCheckedAgo` ticks since the spawn was last seen.
     */
    respawnTicks?: number | null;
    respawnTicksMin?: number | null;
    respawnAssumed?: boolean | null;
    respawnCheckedAgo?: number | null;
}

const COLORS: Record<EntityKind, THREE.Color> = {
    bot: new THREE.Color(0x49a6ff),
    npc: new THREE.Color(0xff6a4d),
    player: new THREE.Color(0xffb1e0),
};
const SELECTED = new THREE.Color(0xffffff);
const OFFLINE = new THREE.Color(0x6b7280);
const COMBAT = new THREE.Color(0xff2222);
const HOVERED = new THREE.Color(0xffe45c);
const RING_COLOURS = new Map<number, THREE.Color>();
function ringColour(hex: number): THREE.Color {
    let c = RING_COLOURS.get(hex);
    if (!c) RING_COLOURS.set(hex, (c = new THREE.Color(hex)));
    return c;
}

const RADIUS: Record<EntityKind, number> = {
    bot: 0.62,
    npc: 0.45,
    player: 0.52,
};

// Rings drawn at most; more are skipped.
const CAP = 6144;
// The server's tick: how long one step's movement is drawn over by default.
const LERP_MS = 640;

// View heights in tiles under which nameplates show: bots first while zooming in, then players, then NPCs.
const BOT_PLATE_ZOOM = 250;
const PLAYER_PLATE_ZOOM = 120;
const NPC_PLATE_ZOOM = 60;

// The item whose sprite marks a sleeping bot's nameplate.
const SLEEPING_BAG_ITEM = 1263;

const _feet = new THREE.Vector3();

type Cached = HTMLElement & {__lastWritten?: Record<string, string>};

// Writes a DOM property or style only when it changed: most of what a frame writes changed long ago,
// and every write goes through the CSSOM. The last value is kept on the element itself.
function setProp(el: HTMLElement, prop: "title", value: string) {
    const c = el as Cached;
    const seen = c.__lastWritten ?? (c.__lastWritten = {});
    if (seen[prop] === value) return;
    seen[prop] = value;
    el[prop] = value;
}

// Positions through transform, which is not a layout input, unlike left and top.
function place(el: HTMLElement, x: number, y: number, centre?: string) {
    setStyle(el, "transform", centre ? `translate(${x}px,${y}px) ${centre}` : `translate(${x}px,${y}px)`);
}

function setStyle(
    el: HTMLElement,
    prop: "outline" | "zIndex" | "color" | "background" | "display" | "transform" | "font" | "padding",
    value: string,
) {
    const c = el as Cached;
    const seen = c.__lastWritten ?? (c.__lastWritten = {});
    const key = "s:" + prop;
    if (seen[key] === value) return;
    seen[key] = value;
    el.style[prop] = value;
}

interface Tracked extends Entity3D {
    fromX: number;
    fromZ: number;
    toX: number;
    toZ: number;
    t0: number;
    curX: number;
    curZ: number;
}

/**
 * Live entities as ground rings, glided between the tiles they arrive at, plus their DOM nameplates,
 * health bars, hit splats and overhead chat, placed as the client places them. Rings draw again
 * faintly where something hides them.
 */
export class EntityLayer {
    /** How long one step's movement is drawn over, in milliseconds: the host's tick. */
    lerpMs = LERP_MS;
    private readonly rings: THREE.InstancedMesh;
    private readonly ghosts: THREE.InstancedMesh;
    private readonly tracked = new Map<string, Tracked>();
    private readonly plates = new Map<string, HTMLDivElement>();
    private readonly bars = new Map<string, HTMLDivElement>();
    private readonly splatDivs = new Map<string, HTMLDivElement>();
    private readonly splats = new Map<string, {dmg: number; until: number; seq: number}>();
    private splatSeq = 0;
    private readonly splatSeen = new Map<string, number>();
    private readonly bubbleDivs = new Map<string, HTMLDivElement>();
    private readonly bubbles = new Map<
        string,
        {
            msg: string;
            until: number;
            canvas: HTMLCanvasElement | null;
            baseline: number;
            midX: number;
            scale: number;
            totalWidth: number;
            extraLinesHeight: number;
            animated: boolean;
        }
    >();
    private readonly bubbleSeen = new Map<string, number>();
    private readonly actionBubbles = new Map<string, {id: number; until: number}>();
    private readonly actionBubbleSeen = new Map<string, number>();
    chatFont: GameFont | null = null;
    private readonly plateHost: HTMLElement;
    hoverKey: string | null = null;
    selectedEntityKey: string | null = null;
    showPlates = true;
    /** Which entities get a nameplate while plates show; null for every one. */
    plateFor: ((e: {kind: string; name?: string | null}) => boolean) | null = null;
    /** NPC sprite size in engine units, so overlays sit at each sprite's own top and middle. */
    npcDims: ((npcId: number) => {w: number; h: number} | null) | null = null;
    /** An item's frame in the item atlas, for a nameplate icon; `src` must be the atlas's stamped URL. */
    itemFrame:
        | ((
              itemId: number,
          ) => {x: number; y: number; w: number; h: number; atlasW: number; atlasH: number; src: string} | null)
        | null = null;

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

        const ghost = new THREE.MeshBasicMaterial({
            transparent: true,
            opacity: 0.33,
            // depthWrite, so rings stacked on one tile do not build up into an opaque ring through walls.
            depthFunc: THREE.GreaterDepth,
            depthWrite: true,
        });
        this.ghosts = new THREE.InstancedMesh(geo, ghost, CAP);
        this.ghosts.renderOrder = 11;

        for (const m of [this.rings, this.ghosts]) {
            m.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
            m.count = 0;
            m.frustumCulled = false;
            m.userData.noPick = true;
            scene.add(freezeTransform(m));
        }
    }

    /**
     * Replaces the entity set. `snap` places every entity at once instead of gliding, for a jump in time.
     * A hit splat lasts 1 s, chat 3 s and an action bubble 3 s from its last emit, as in the client.
     *
     * @see https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/PacketHandler.java#L2614 PacketHandler.drawNearbyPlayers
     */
    update(list: Entity3D[], now: number, snap = false) {
        const seen = new Set<string>();
        for (const e of list) {
            seen.add(e.key);
            if (e.dmg != null && e.dmgTick != null && this.splatSeen.get(e.key) !== e.dmgTick) {
                this.splatSeen.set(e.key, e.dmgTick);
                this.splats.set(e.key, {dmg: e.dmg, until: now + 1000, seq: this.splatSeq++});
            }
            if (e.msg != null && e.msgTick != null && this.bubbleSeen.get(e.key) !== e.msgTick) {
                this.bubbleSeen.set(e.key, e.msgTick);
                this.bubbles.set(e.key, {
                    msg: e.msg,
                    until: now + 3000,
                    canvas: null,
                    baseline: 0,
                    midX: 0,
                    scale: 0,
                    totalWidth: 0,
                    extraLinesHeight: 0,
                    animated: false,
                });
            }
            if (e.bubble != null && e.bubbleTick != null && this.actionBubbleSeen.get(e.key) !== e.bubbleTick) {
                this.actionBubbleSeen.set(e.key, e.bubbleTick);
                this.actionBubbles.set(e.key, {id: e.bubble, until: now + 3000});
            }
            const t = this.tracked.get(e.key);
            if (!t) {
                this.tracked.set(e.key, {
                    ...e,
                    fromX: e.x,
                    fromZ: e.z,
                    toX: e.x,
                    toZ: e.z,
                    curX: e.x,
                    curZ: e.z,
                    t0: now,
                });
            } else {
                if (snap || e.snapSeq !== t.snapSeq) {
                    t.fromX = t.toX = t.curX = e.x;
                    t.fromZ = t.toZ = t.curZ = e.z;
                    t.t0 = now - this.lerpMs;
                    // A change between ghost and live, or online and offline, is a change of representation, not movement.
                } else if ((t.ghost ?? false) !== (e.ghost ?? false) || (t.offline ?? false) !== (e.offline ?? false)) {
                    t.fromX = t.toX = t.curX = e.x;
                    t.fromZ = t.toZ = t.curZ = e.z;
                    t.t0 = now - this.lerpMs;
                    // Retargeted from where the glide is now, so an entity whose target moves every frame still moves.
                } else if (t.toX !== e.x || t.toZ !== e.z) {
                    const u = Math.min(1, (now - t.t0) / this.lerpMs);
                    t.curX = t.fromX + (t.toX - t.fromX) * u;
                    t.curZ = t.fromZ + (t.toZ - t.fromZ) * u;
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
                t.shopId = e.shopId;
                t.appearance = e.appearance;
                t.combatLvl = e.combatLvl;
                t.skulled = e.skulled;
                t.dir = e.dir;
                t.hp = e.hp;
                t.maxHp = e.maxHp;
                t.bubble = e.bubble;
                t.sleeping = e.sleeping;
                t.ghost = e.ghost;
                t.offline = e.offline;
                t.ringColor = e.ringColor;
                t.ringColorFirst = e.ringColorFirst;
                t.tagColor = e.tagColor;
                t.tagAlways = e.tagAlways;
                t.tagOutline = e.tagOutline;
                t.tagStrong = e.tagStrong;
                t.snapSeq = e.snapSeq;
                t.respawnTicks = e.respawnTicks;
                t.respawnTicksMin = e.respawnTicksMin;
                t.respawnAssumed = e.respawnAssumed;
                t.respawnCheckedAgo = e.respawnCheckedAgo;
            }
        }
        for (const key of [...this.tracked.keys()]) {
            if (!seen.has(key)) {
                this.tracked.delete(key);
                for (const pool of [this.plates, this.bars, this.splatDivs, this.bubbleDivs]) {
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
     * Glides every entity to `now`, writes the rings and places the DOM overlays. `toWorld` maps a tile
     * to its ground position; `canvasW` and `canvasH` are CSS pixels; `zoomTiles` the view's height in
     * tiles, which gates the nameplates.
     */
    frame(
        now: number,
        toWorld: (x: number, z: number) => THREE.Vector3,
        groundNormal: (x: number, z: number) => THREE.Vector3,
        camera: THREE.Camera,
        canvasW: number,
        canvasH: number,
        zoomTiles: number,
    ) {
        camera.updateMatrixWorld();
        const oc = camera as THREE.OrthographicCamera;
        const pxPerUnit = oc.isOrthographicCamera ? canvasW / Math.max(1, oc.right - oc.left) : 0;
        const m = new THREE.Matrix4();
        const q = new THREE.Quaternion();
        const sc = new THREE.Vector3();
        const UP = new THREE.Vector3(0, 1, 0);
        let i = 0;
        const platesUsed = new Set<string>();
        const platePlacements: {
            div: HTMLDivElement;
            x: number;
            top: number;
            kind: EntityKind;
            key: string;
            group: string;
        }[] = [];
        const bubblePlacements: {
            div: HTMLDivElement;
            x: number;
            y: number;
            halfWidth: number;
            height: number;
            baseline: number;
            midX: number;
        }[] = [];
        for (const t of this.tracked.values()) {
            const u = Math.min(1, (now - t.t0) / this.lerpMs);
            t.curX = t.fromX + (t.toX - t.fromX) * u;
            t.curZ = t.fromZ + (t.toZ - t.fromZ) * u;
            const p = toWorld(t.curX, t.curZ);
            p.y += 6;
            // Spawn markers get no ring: thousands of them would swamp the cap.
            if (!t.ghost && i < CAP) {
                q.setFromUnitVectors(UP, groundNormal(t.curX, t.curZ));
                const r =
                    RADIUS[t.kind] * (t.selected ? 1.35 : t.key === this.hoverKey && t.ringColor != null ? 1.25 : 1);
                sc.set(r, 1, r);
                m.compose(p, q, sc);
                this.rings.setMatrixAt(i, m);
                this.ghosts.setMatrixAt(i, m);
                const c =
                    t.key === this.hoverKey
                        ? HOVERED
                        : t.selected || t.key === this.selectedEntityKey
                          ? SELECTED
                          : t.ringColorFirst && t.ringColor != null
                            ? ringColour(t.ringColor)
                            : t.offline
                              ? OFFLINE
                              : t.inCombat
                                ? COMBAT
                                : t.ringColor != null
                                  ? ringColour(t.ringColor)
                                  : COLORS[t.kind];
                this.rings.setColorAt(i, c);
                this.ghosts.setColorAt(i, c);
                i++;
            }

            const dims = t.kind === "npc" && t.npcId != null ? this.npcDims?.(t.npcId) : null;
            const spriteH = dims ? dims.h : t.appearance ? 220 : 130;
            // Overlays hang off the projected feet by screen pixels, since sprites stand along screen-vertical.
            const feet = _feet.copy(p).project(camera);
            const feetX = (feet.x * 0.5 + 0.5) * canvasW;
            const feetY = (-feet.y * 0.5 + 0.5) * canvasH;

            const plateZoomMax =
                t.kind === "bot" ? BOT_PLATE_ZOOM : t.kind === "player" ? PLAYER_PLATE_ZOOM : NPC_PLATE_ZOOM;
            const onView = feet.z < 1 && Math.abs(feet.x) <= 1.2 && Math.abs(feet.y) <= 1.2;
            const plateText =
                !onView ||
                (!t.tagAlways &&
                    (!this.showPlates || zoomTiles > plateZoomMax || (this.plateFor != null && !this.plateFor(t))))
                    ? null
                    : t.kind === "player"
                      ? (t.skulled ? "☠ " : "") + (t.name ?? "player") + (t.combatLvl ? ` (${t.combatLvl})` : "")
                      : t.kind === "bot"
                        ? (t.skulled ? "☠ " : "") + (t.name ?? "player") + (t.offline ? " · off" : "")
                        : t.npcId != null
                          ? `${t.name ?? "npc"} (${t.npcId}) #${t.key.slice(4)}` +
                            (t.ghost && t.respawnTicks != null
                                ? (t.respawnTicksMin != null && t.respawnTicksMin !== t.respawnTicks
                                      ? ` · ${t.respawnTicksMin}–${t.respawnTicks}t`
                                      : ` · ${t.respawnTicks}t`) + (t.respawnAssumed ? "?" : "")
                                : "")
                          : null;
            if (plateText) {
                platesUsed.add(t.key);
                let div = this.plates.get(t.key);
                if (!div) {
                    div = document.createElement("div");
                    const common =
                        "position:absolute;left:0;top:0;" +
                        "pointer-events:auto;cursor:pointer;white-space:nowrap;" +
                        "border-radius:4px;";
                    if (t.kind === "bot") {
                        div.style.cssText =
                            common +
                            "font:11px monospace;color:#e8f2ff;" +
                            "background:rgba(10,14,24,.72);padding:1px 5px;" +
                            "z-index:900000;";
                    } else if (t.kind === "player") {
                        div.style.cssText =
                            common +
                            "font:10px monospace;color:rgba(255,216,240,.85);" +
                            "background:rgba(10,14,24,.45);padding:0 4px;" +
                            "z-index:890000;";
                    } else {
                        div.style.cssText =
                            common +
                            "font:10px monospace;color:rgba(255,196,172,.8);" +
                            "background:rgba(10,14,24,.4);padding:0 4px;" +
                            "z-index:880000;";
                    }
                    div.dataset.entityKey = t.key;
                    this.plateHost.appendChild(div);
                    this.plates.set(t.key, div);
                }
                const ab = this.actionBubbles.get(t.key);
                if (ab && now > ab.until) {
                    this.actionBubbles.delete(t.key);
                }
                // The client draws an action bubble over the head; here it is an icon in the nameplate.
                const bubbleId = t.sleeping
                    ? SLEEPING_BAG_ITEM
                    : t.kind !== "npc" && ab && now <= ab.until
                      ? ab.id
                      : null;
                const contentKey = `${plateText}|${bubbleId ?? ""}|${this.itemFrame ? 1 : 0}` + `|${t.shopId ?? ""}`;
                if (div.dataset.content !== contentKey) {
                    div.dataset.content = contentKey;
                    div.textContent = "";
                    const f = bubbleId != null ? this.itemFrame?.(bubbleId) : null;
                    if (f) {
                        const k = Math.min(14 / f.w, 14 / f.h);
                        const icon = document.createElement("span");
                        icon.style.cssText =
                            "display:inline-block;vertical-align:-2px;" +
                            `margin-right:3px;width:${f.w * k}px;` +
                            `height:${f.h * k}px;` +
                            `background-image:url(${f.src});` +
                            `background-position:${-f.x * k}px ${-f.y * k}px;` +
                            `background-size:${f.atlasW * k}px ${f.atlasH * k}px;` +
                            "image-rendering:pixelated;";
                        div.appendChild(icon);
                    }
                    div.appendChild(document.createTextNode(plateText));
                    if (t.shopId) {
                        const shop = document.createElement("span");
                        shop.dataset.shopId = t.shopId;
                        shop.title = "open shop";
                        shop.style.cssText = "margin-left:4px;cursor:pointer;color:#ffd27a;";
                        shop.textContent = "⚖";
                        div.appendChild(shop);
                    }
                }
                setProp(
                    div,
                    "title",
                    t.offline
                        ? "offline — last seen on this tile"
                        : t.ghost && t.respawnTicks != null
                          ? `pop in ${
                                t.respawnTicksMin != null && t.respawnTicksMin !== t.respawnTicks
                                    ? `${t.respawnTicksMin}–${t.respawnTicks}`
                                    : `${t.respawnTicks}`
                            } ticks` +
                            (t.respawnAssumed ? " · assumed (nobody saw the death)" : " · witnessed") +
                            (t.respawnCheckedAgo != null ? ` · last looked at ${t.respawnCheckedAgo}t ago` : "")
                          : "",
                );
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05 || feet.y < -1.05 || feet.y > 1.05) {
                    setStyle(div, "display", "none");
                } else {
                    // A fighter's bar and plate shift 20% of 256 units with its sprite, the splat 10%, as the client's.
                    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L6798
                    const plateShift = t.dir === 8 ? -51.2 : t.dir === 9 ? 51.2 : 0;
                    setStyle(div, "display", "");
                    platePlacements.push({
                        div,
                        x: feetX + plateShift * pxPerUnit,
                        top: feetY - spriteH * pxPerUnit - 12,
                        kind: t.kind,
                        key: t.key,
                        group:
                            `${Math.round(t.curX)},${Math.round(t.curZ)}` +
                            `:${t.dir === 8 ? "a" : t.dir === 9 ? "b" : "-"}`,
                    });
                    setStyle(
                        div,
                        "outline",
                        t.key === this.hoverKey
                            ? t.tagColor != null
                                ? "1px solid #fff"
                                : t.tagOutline != null
                                  ? "2px solid #ffe45c"
                                  : "1px solid #ffe45c"
                            : t.selected || t.key === this.selectedEntityKey
                              ? t.tagColor != null
                                  ? "1px dashed #fff"
                                  : "1px solid #fff"
                              : t.tagOutline != null
                                ? `${t.tagStrong ? 2 : 1}px solid #${t.tagOutline.toString(16).padStart(6, "0")}`
                                : "",
                    );
                    setStyle(
                        div,
                        "zIndex",
                        String(
                            t.key === this.hoverKey
                                ? 990000
                                : t.tagAlways && t.tagColor != null
                                  ? 960000
                                  : t.tagAlways
                                    ? 950000
                                    : t.kind === "bot"
                                      ? 900000
                                      : t.kind === "player"
                                        ? 890000
                                        : 880000,
                        ),
                    );
                    setStyle(
                        div,
                        "font",
                        t.tagStrong ? "bold 12px monospace" : t.kind === "bot" ? "11px monospace" : "10px monospace",
                    );
                    setStyle(div, "padding", t.tagStrong ? "1px 6px" : t.kind === "bot" ? "1px 5px" : "0 4px");
                    if (t.tagColor != null) {
                        setStyle(div, "color", "#1a1405");
                        setStyle(div, "background", `#${t.tagColor.toString(16).padStart(6, "0")}`);
                    } else if (t.tagStrong) {
                        setStyle(div, "color", "#fff");
                        setStyle(div, "background", "rgba(10,14,24,.85)");
                    } else if (t.kind === "npc") {
                        setStyle(div, "color", t.ghost ? "rgba(196,200,208,.5)" : "rgba(255,196,172,.8)");
                        setStyle(div, "background", t.ghost ? "rgba(10,14,24,.25)" : "rgba(10,14,24,.4)");
                    } else if (t.kind === "bot") {
                        setStyle(div, "color", t.offline ? "rgba(170,178,190,.62)" : "#e8f2ff");
                        setStyle(div, "background", t.offline ? "rgba(10,14,24,.42)" : "rgba(10,14,24,.72)");
                    } else {
                        setStyle(div, "color", "rgba(255,216,240,.85)");
                        setStyle(div, "background", "rgba(10,14,24,.45)");
                    }
                }
            }

            const barsWanted = t.maxHp != null && t.maxHp > 0 && t.hp != null && (t.hp < t.maxHp || t.inCombat);
            let bar = this.bars.get(t.key);
            if (barsWanted) {
                if (!bar) {
                    bar = document.createElement("div");
                    bar.style.cssText =
                        "position:absolute;left:0;top:0;" +
                        "width:30px;height:5px;background:rgba(255,0,0,.75);" +
                        "pointer-events:none;overflow:hidden;z-index:800000;";
                    const fill = document.createElement("div");
                    fill.style.cssText = "height:100%;background:rgba(0,255,0,.75);width:100%;";
                    bar.appendChild(fill);
                    this.plateHost.appendChild(bar);
                    this.bars.set(t.key, bar);
                }
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05 || feet.y < -1.05 || feet.y > 1.05) {
                    setStyle(bar, "display", "none");
                } else {
                    const shift = t.dir === 8 ? -51.2 : t.dir === 9 ? 51.2 : 0;
                    setStyle(bar, "display", "");
                    place(bar, feetX + shift * pxPerUnit, feetY - spriteH * pxPerUnit + 2, "translate(-50%,-100%)");
                    (bar.firstChild as HTMLDivElement).style.width =
                        `${Math.max(0, Math.min(100, ((t.hp ?? 0) / (t.maxHp ?? 1)) * 100))}%`;
                }
            } else if (bar) {
                bar.remove();
                this.bars.delete(t.key);
            }

            const splat = this.splats.get(t.key);
            if (splat && now > splat.until) {
                this.splats.delete(t.key);
            }
            let sd = this.splatDivs.get(t.key);
            if (splat && now <= splat.until) {
                if (!sd) {
                    sd = document.createElement("div");
                    sd.style.cssText =
                        "position:absolute;left:0;top:0;" +
                        "width:24px;height:24px;" +
                        "background-size:24px 24px;image-rendering:pixelated;" +
                        "font:bold 12px sans-serif;color:#fff;text-align:center;" +
                        "line-height:25px;pointer-events:none;";
                    sd.style.backgroundImage =
                        t.kind === "npc"
                            ? `url(${versionedNow("/api/world3d/splat-blue.png")})`
                            : `url(${versionedNow("/api/world3d/splat-red.png")})`;
                    this.plateHost.appendChild(sd);
                    this.splatDivs.set(t.key, sd);
                }
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05 || feet.y < -1.05 || feet.y > 1.05) {
                    setStyle(sd, "display", "none");
                } else {
                    const shift = t.dir === 8 ? -25.6 : t.dir === 9 ? 25.6 : 0;
                    setStyle(sd, "zIndex", String(750000 + splat.seq));
                    setStyle(sd, "display", "");
                    place(sd, feetX + shift * pxPerUnit, feetY - (spriteH / 2) * pxPerUnit, "translate(-50%,-50%)");
                    sd.textContent = String(splat.dmg);
                }
            } else if (sd) {
                sd.remove();
                this.splatDivs.delete(t.key);
            }

            const bubble = this.bubbles.get(t.key);
            if (bubble && now > bubble.until) {
                this.bubbles.delete(t.key);
            }
            let bd = this.bubbleDivs.get(t.key);
            if (bubble && now <= bubble.until && this.chatFont) {
                const dpr = window.devicePixelRatio || 1;
                // A backing store at the device pixel ratio, so glyph pixels land on the device grid.
                const glyphScale = Math.max(1, Math.ceil(dpr));
                if (!bubble.canvas || bubble.scale !== glyphScale || bubble.animated) {
                    const r = this.chatFont.renderOverhead(
                        bubble.msg,
                        300,
                        RSC_OVERHEAD_COLOR,
                        glyphScale,
                        bubble.scale === glyphScale ? bubble.canvas : null,
                    );
                    bubble.canvas = r.canvas;
                    bubble.baseline = r.baseline;
                    bubble.midX = r.midX;
                    bubble.scale = glyphScale;
                    bubble.totalWidth = r.totalWidth;
                    bubble.extraLinesHeight = r.extraLinesHeight;
                    bubble.animated = r.animated;
                }
                if (!bd) {
                    bd = document.createElement("div");
                    bd.style.cssText =
                        "position:absolute;left:0;top:0;pointer-events:none;" + "z-index:700000;line-height:0;";
                    this.plateHost.appendChild(bd);
                    this.bubbleDivs.set(t.key, bd);
                }
                if (bd.firstChild !== bubble.canvas) {
                    bd.replaceChildren(bubble.canvas);
                }
                if (feet.z > 1 || feet.x < -1.05 || feet.x > 1.05 || feet.y < -1.05 || feet.y > 1.05) {
                    setStyle(bd, "display", "none");
                } else {
                    setStyle(bd, "display", "");
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
        {
            // Plates on one tile, and one combat side, stack as a group from the tallest member's top: bots
            // nearest the heads, then players, then NPCs, 15 px apart.
            const KIND_ORDER: Record<EntityKind, number> = {bot: 0, player: 1, npc: 2};
            const groups = new Map<string, typeof platePlacements>();
            for (const p of platePlacements) {
                let g = groups.get(p.group);
                if (!g) groups.set(p.group, (g = []));
                g.push(p);
            }
            for (const [, members] of groups) {
                members.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind] || (a.key < b.key ? -1 : 1));
                const anchor = Math.min(...members.map((m) => m.top));
                for (let k = 0; k < members.length; k++) {
                    place(members[k].div, members[k].x, anchor - k * 15, "translate(-50%,-100%)");
                }
            }
        }

        {
            const lineH = this.chatFont?.lineHeight ?? 14;
            const dpr = window.devicePixelRatio || 1;
            const snap = (v: number) => Math.round(v * dpr) / dpr;
            for (let bi = 0; bi < bubblePlacements.length; bi++) {
                const b = bubblePlacements[bi];
                // The client's bubble collision: each bubble is pushed above any earlier one it overlaps.
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/Client_Base/src/orsc/mudclient.java#L2760
                let collided = true;
                while (collided) {
                    collided = false;
                    for (let bj = 0; bj < bi; bj++) {
                        const o = bubblePlacements[bj];
                        if (
                            o.y - lineH < b.y + b.height &&
                            b.y - lineH < o.y + o.height &&
                            o.x + o.halfWidth > b.x - b.halfWidth &&
                            b.x + b.halfWidth > o.x - o.halfWidth &&
                            o.y - b.height - lineH < b.y
                        ) {
                            b.y = o.y - (lineH + b.height);
                            collided = true;
                        }
                    }
                }
                place(b.div, snap(b.x - b.midX), snap(b.y - b.baseline));
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
        for (const m of [this.rings, this.ghosts]) {
            // count bounds the draw, not the upload.
            uploadPrefix(m.instanceMatrix, i * 16);
            if (m.instanceColor) uploadPrefix(m.instanceColor, i * 3);
        }
    }

    /** The name of the nearest bot within `maxTiles` of a ground point, or null. */
    nearestBot(x: number, z: number, maxTiles: number): string | null {
        const e = this.nearestEntity(x, z, maxTiles, "bot");
        return e ? e.key.slice(4) : null;
    }

    /** The nearest tracked entity, of `kind` if given, within `maxTiles`; bots win near ties. */
    nearestEntity(
        x: number,
        z: number,
        maxTiles: number,
        kind?: EntityKind,
    ): {
        key: string;
        kind: EntityKind;
        name?: string | null;
        npcId?: number;
        appearance?: string | null;
        x: number;
        z: number;
        inCombat?: boolean;
    } | null {
        let best: Tracked | null = null;
        let bestD = maxTiles;
        for (const t of this.tracked.values()) {
            if (kind && t.kind !== kind) continue;
            const d = Math.hypot(t.curX - x, t.curZ - z);
            const dAdj = t.kind === "bot" ? d - 0.25 : d;
            if (dAdj < bestD) {
                bestD = dAdj;
                best = t;
            }
        }
        return best
            ? {
                  key: best.key,
                  kind: best.kind,
                  name: best.name,
                  npcId: best.npcId,
                  appearance: best.appearance,
                  x: best.curX,
                  z: best.curZ,
                  inCombat: best.inCombat,
              }
            : null;
    }

    /** Where a live entity is drawn at `now`, floor-local, mid-glide; null for none or a spawn marker. */
    at(key: string, now: number): {x: number; z: number; npcId: number | null} | null {
        const t = this.tracked.get(key);
        if (!t || t.ghost) return null;
        const u = Math.min(1, (now - t.t0) / this.lerpMs);
        return {x: t.fromX + (t.toX - t.fromX) * u, z: t.fromZ + (t.toZ - t.fromZ) * u, npcId: t.npcId ?? null};
    }

    /** Every tracked entity at its current position. */
    current(): {
        key: string;
        kind: EntityKind;
        name?: string | null;
        npcId?: number;
        appearance?: string | null;
        dir?: number | null;
        x: number;
        z: number;
        inCombat?: boolean;
    }[] {
        const out = [];
        for (const t of this.tracked.values()) {
            out.push({
                key: t.key,
                kind: t.kind,
                name: t.name,
                npcId: t.npcId,
                appearance: t.appearance,
                dir: t.dir,
                x: t.curX,
                z: t.curZ,
                inCombat: t.inCombat,
            });
        }
        return out;
    }

    /** The tracked entities of `kind` with their motion, for the sprite layers. */
    snapshot(
        kind: EntityKind,
        now: number,
    ): {
        key: string;
        npcId?: number;
        appearance?: string | null;
        dir?: number | null;
        ghost?: boolean;
        offline?: boolean;
        x: number;
        z: number;
        moving: boolean;
        dx: number;
        dz: number;
    }[] {
        const out = [];
        for (const t of this.tracked.values()) {
            if (t.kind !== kind) continue;
            out.push({
                key: t.key,
                npcId: t.npcId,
                appearance: t.appearance,
                dir: t.dir,
                ghost: t.ghost,
                offline: t.offline,
                x: t.curX,
                z: t.curZ,
                moving: now - t.t0 < this.lerpMs && (t.fromX !== t.toX || t.fromZ !== t.toZ),
                dx: t.toX - t.fromX,
                dz: t.toZ - t.fromZ,
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
        for (const pool of [this.plates, this.bars, this.splatDivs, this.bubbleDivs]) {
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
