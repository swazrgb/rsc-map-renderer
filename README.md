# rsc-map-renderer

Software to generate a 2D & 3D render of the world of RuneScape Classic.

**[3D world map](https://swazrgb.github.io/rsc-map-renderer/)**  
[![3D world map](viewer/demo.webp)](https://swazrgb.github.io/rsc-map-renderer/)

**[2D world map](https://swazrgb.github.io/rsc-map-renderer/2d/)**  
[![2D world map](https://swazrgb.github.io/rsc-map-renderer/2d/floor-0.light.thumb.png)](https://swazrgb.github.io/rsc-map-renderer/2d/floor-0.light.preview.png)

## Modules

| Module | What it is |
| --- | --- |
| **`game-data/`** | Shared loaders for the OpenRSC data tree — `ServerConf` (locates `conf/server`), the `SceneryLocs`/`BoundaryLocs`/`NpcLocs` parsers, and the classic `.jag`/`.mem` landscape readers. |
| **`client-render/`** | Headless port of the OpenRSC client software renderer: rasterizes terrain + scenery models + walls from the `.orsc` cache into a plain `int[]` buffer. |
| **`world3d-bake/`** | Bakes the complete static `/api/world3d/*` + `/api/map/*` asset tree the 3D viewer consumes — world-mesh cells, engine textures, the object/door libraries, npc/item/font/scenery sprite atlases, and the per-layer player-sprite atlas the viewer composites appearances from. |
| **`viewer/`** | The standalone WebGL 3D world viewer (React + three.js). Renders the baked terrain + scenery mesh and billboarded NPC/player sprites. Entity data is injected via props — empty in the open-source demo. |
| **`map2d/`** | The standalone per-floor 2D world-map raster renderer (terrain PNG + walls overlay + GeoJSON feature layer). |

## Bake 2D world map

`map2d` bakes the world to per-floor raster layers — terrain in two treatments (full-saturation
`light` and the washed-out `dim` minimap shading), a walls overlay and an impassable-tile overlay —
plus a GeoJSON feature layer (doors + NPC spawns).

Prereqs: a JDK and Maven, plus a checkout of [OpenRSC](https://gitlab.com/openrsc/openrsc) beside this repo (`../openrsc`).

```bash
scripts/render-map.sh map-out        # per-floor layers + previews/thumbnails into ./map-out
scripts/render-map.sh map-out 2      # …at 2× resolution
```

## Build the 3D viewer demo

Prereqs: a JDK, Maven, Node, and a checkout of [OpenRSC](https://gitlab.com/openrsc/openrsc) beside this repo (`../openrsc`).

```bash
scripts/build-site.sh site      # server tree + client cache auto-resolve from the ../openrsc checkout
# serve it with anything:
(cd site && python3 -m http.server 8080)   # → http://localhost:8080
```

### How it works

The viewer fetches everything from absolute static paths (`/api/world3d/*`,
`/api/map/*`, `/api/npc-spawns`, `/api/items/wearables`). **Player sprites are
composited in the browser** (`playerCompositor.ts`) from a per-layer atlas: the
bake ships every wearable sprite reduced to recolour-neutral pixels, and the
viewer replays the engine's recolour + layering to draw any `layers|colours`
appearance token — so all combinations render with nothing pre-baked per token.
Control verbs (`sendWalk`/`sendInteract`) are no-ops in the open-source build —
there's nothing to command — so the demo is purely a viewer.

**The living-world demo.** `map2d`'s `DemoEntityBaker` (run via `-cp` in the
build script, since it needs the `CollisionMap`) simulates the server's roam
behaviour offline: **every** NPC spawn gets a ~5-minute **collision-aware** wander
track, plus a few players around Lumbridge. The tracks are stored in
`/api/demo/entities.json` and looped client-side. With the in-view **simulation**
toggle on (default) the whole world is alive; untick it to drop the live NPCs and
reveal the raw `/api/npc-spawns` spawn table (ghost markers) instead.

## Feeding it live data

The viewer is deliberately data-source agnostic. It fetches its **static world**
(mesh, scenery, textures, sprite atlases) from the `/api/*` tree a dumb host
serves, and takes **live entities** (players, NPCs, ground items, projectiles,
observed scenery changes) as React props. The demo synthesizes those from baked
wander tracks; a real deployment streams them from whatever backend it likes.
Everything embeds through the viewer's entry point (`viewer/src/index.ts`):

```ts
import {World3DView, configureViewerHost} from "rsc-world3d-viewer";
import type {Observer, MapEntity} from "rsc-world3d-viewer";
```

### 1. Entity data — the `observers` prop

Everything moving in the world arrives through one prop: `observers: Observer[]`.
Each `Observer` is one **vantage point** onto the world — one of your bots, or
(to mirror a whole server) every online player — carrying its own vitals *and its
local view of everything it can see*:

```ts
interface Observer {
  /** The observer account's name — its stable key. */
  username: string;
  /** Its current server tick — stamps the timed events below. */
  serverTick: number | null;
  /** The observer's own tile + floor; `null` = it draws no sprite of its own. */
  position: {x; z; floor} | null;
  /** Its own server index, so it isn't re-drawn as a "player" it sees. */
  serverIndex?: number;
  /** The observer's look — an appearance token (see "Player appearance"). */
  appearance?: string;
  /** Facing, 0–7. */
  dir?: number;
  /** Current hitpoints (with `maxHits`, draws its health bar). */
  hits?: number;
  /** Maximum hitpoints. */
  maxHits?: number;
  /** Damage just dealt to it (`0` = a blocked hit) — flashes a splat at `serverTick`. */
  dmg?: number;
  /** Currently fighting (tints the nameplate). */
  inCombat?: boolean;
  /** Asleep (Zzz on the nameplate). */
  sleeping?: boolean;
  /** Latest overhead chat line… */
  msg?: string;
  /** …and the tick it was said (drawn as a chat bubble). */
  msgTick?: number;
  /** Action-bubble item id (small nameplate icon)… */
  bubble?: number;
  /** …and the tick it popped. */
  bubbleTick?: number;

  // The observer's LOCAL VIEW — everything this observer can see right now, merged
  // across all observers (deduped by serverIndex / tile) into one shared world:
  /** NPCs in view. */
  npcs?: MapEntity[];
  /** Other players in view. */
  players?: MapEntity[];
  /** Items lying on the floor. */
  groundItems?: MapEntity[];
  /** Live scenery — a mined rock, an opened chest (overrides the static bake). */
  objects?: MapEntity[];
  /** Doors / gates, addressed by tile-edge (their `dir`). */
  wallObjects?: MapEntity[];
  /** Arrows / spells launched this tick (each animates once, then is gone). */
  projectiles?: {
    /** Which sprite: 0 orb, 1 magic, 2 ranged, 3 gnomeball, 4 skull, 5 spikeball. */
    sprite: number;
    /** The shooter's `serverIndex`. */
    from: number;
    /** `true` if `from` is an NPC (else a player/bot). */
    fromNpc: boolean;
    /** The target's `serverIndex`. */
    to: number;
    /** `true` if `to` is a player/bot (else an NPC). */
    toPlayer: boolean;
  }[];
}
```

Each `npcs` / `players` / `groundItems` / `objects` / `wallObjects` entry is a
`MapEntity`. `serverIndex` is the server's stable handle for it (and the dedup key
across observers); everything else is optional — send only what you know:

```ts
interface MapEntity {
  /** Server's stable id for this npc/player/object — the dedup key across observers. */
  serverIndex: number;
  /** Definition id (npc id, item id, scenery id); `0` for players. */
  id: number;
  /** Display name, or `null` to fall back to the def's own name. */
  name: string | null;
  /** Tile X. */
  x: number;
  /** Tile Z — **absolute**, floor folded in: `z = tileZ + floor*944` (floor 0 ground … 3 underground). */
  z: number;
  /** Currently fighting (nameplate tint). */
  inCombat: boolean;
  /** Tile-edge for walls/gates; facing for scenery. */
  dir?: number | null;
  /** Players only — appearance token (see "Player appearance"). */
  appearance?: string | null;
  /** Players only — combat level shown on the nameplate. */
  combatLvl?: number | null;
  /** Players only — PK-skull icon. */
  skulled?: boolean;
  /** Current hitpoints (with `maxHp`, draws a health bar). */
  hp?: number | null;
  /** Maximum hitpoints. */
  maxHp?: number | null;
  /** Damage just taken (`0` = a blocked hit)… */
  dmg?: number | null;
  /** …and the tick it landed (hit splat). */
  dmgTick?: number | null;
  /** Overhead chat line… */
  msg?: string | null;
  /** …and the tick it was said (chat bubble). */
  msgTick?: number | null;
  /** Action-bubble item id… */
  bubble?: number | null;
  /** …and the tick it popped. */
  bubbleTick?: number | null;
}
```

Feed a **fresh array once per server tick**. The viewer:

- **interpolates** every entity from its previous tile to the new one over one
  tick — you send discrete positions each tick, *not* paths, and movement comes
  out smooth;
- **dedupes** across observers by `serverIndex` (NPCs/players) and by tile
  (ground items / scenery), so overlapping fields-of-view merge into one world
  and nothing is drawn twice (nor an observer re-drawn as a player another sees);
- reconciles observed `objects` / `wallObjects` against the static bake, rebuilding
  only the affected scenery cells (a mined rock, an opened door).

```tsx
function Live() {
  const [observers, setObservers] = useState<Observer[]>([]);
  useEffect(() => {
    const ws = new WebSocket("wss://your-backend/stream");
    ws.onmessage = e => setObservers(JSON.parse(e.data));   // one snapshot per tick
    return () => ws.close();
  }, []);
  return <World3DView observers={observers} />;
}
```

**Respawn ghosts.** Two parallel top-level lists drive the countdown ghosts:
`npcRespawns: NpcRespawn[]` for dead NPCs (which have vanished from every
observer's `npcs`), and `objectRespawns: ObjectRespawn[]` for depleted scenery
(a mined rock, a looted chest). Each entry is ghosted at its tile with a
predicted pop window and the id of what it becomes — the viewer resolves that
object's name from the library, so no name is sent.

**Planned route.** `route: RoutePoint[]` is the selected observer's upcoming
path, drawn as a line across the map — one `{x, z}` tile per step, in order. A
step with `hop: true` is a jump rather than a walk (a transport, ladder, or
teleport landing), so the line breaks there instead of tracing the ground.

### 2. Control — `configureViewerHost`

Call once at startup to wire the only per-deployment behaviour: the two outbound
control verbs. Both optional; unset verbs no-op (the open-source build is
view-only).

```ts
configureViewerHost({
  // world clicks in walk/act mode
  sendWalk: (username, {x, z}) => myBackend.walk(username, x, z),
  sendInteract: (username, action, x, z, id, dir, item) => myBackend.interact(/* … */),
});
```

`sendWalk`/`sendInteract` are the viewer's only outbound calls — it issues no
other requests to your backend. Player sprites need no hook: any appearance
renders in-browser (see below).

### Player appearance

A player's look is one token — the RSC appearance record serialized 1:1 with
the server's appearance packet, e.g. `"4,5,3|1,12,2,3"`:

```
  s0,s1,…,s11  |  hair,top,bottom,skin
  └─ worn sprites ─┘  └─ colour indices ─┘
```

- **Left of `|`** — the worn-sprite array, one animation id per appearance slot in
  draw order (up to 12). `0` = empty slot; a non-zero value is `animationId + 1`
  (the +1 keeps `0` free for "nothing"). This *is* the equipped outfit: putting on
  a helmet is the server changing the sprite id in that slot, so there are no
  separate item ids to resolve. Slots 3/4 are weapon/shield; the rest are the
  head/body/legs base plus armour overrides.
- **Right of `|`** — four palette *indices* (not RGB): hair, top (shirt), bottom
  (trousers), skin. These pick colours out of the recolour ramps.

The viewer composites this into a sprite strip entirely client-side (recolour +
layer of the per-layer atlas), caching by token, so **any** combination renders
with no server and nothing pre-baked. It's kept a compact string because it's one
field per player per tick and doubles as that cache key — but it's a plain
serialization of the [`Appearance`](viewer/src/api.ts) struct, and
`formatAppearance(appearance)` / `parseAppearance(token)` convert either way:

```ts
import {formatAppearance} from "rsc-world3d-viewer";
const token = formatAppearance({sprites: [4, 5, 3], hair: 1, top: 12, bottom: 2, skin: 3});
// → "4,5,3|1,12,2,3"
```

### A working reference

`viewer/src/world3dTestMain.tsx` is a complete, runnable host: it loads baked
wander tracks and, every frame, assembles a one-element `observers` array and
hands it to `World3DView`. Swap its baked-track expansion for your live stream
(and add `configureViewerHost` for walk/interact) and you have a live client.

## Dev

```bash
# viewer with hot reload, served against a baked asset dir:
cd viewer && WORLD3D_SITE=/path/to/site npm run dev

# 2D map renderer:
scripts/render-map.sh map-out
```
