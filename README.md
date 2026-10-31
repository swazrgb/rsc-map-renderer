# rsc-map-renderer

Software to generate a 2D & 3D render of the world of RuneScape Classic.

**[3D world map](https://swazrgb.github.io/rsc-map-renderer/)**  
[![3D world map](viewer/demo.webp)](https://swazrgb.github.io/rsc-map-renderer/)

**[2D world map](https://swazrgb.github.io/rsc-map-renderer/2d/)**  
[![2D world map](https://swazrgb.github.io/rsc-map-renderer/2d/floor-0.light.thumb.png)](https://swazrgb.github.io/rsc-map-renderer/2d/floor-0.light.preview.png)

## Modules

| Module | What it is |
| --- | --- |
| **`game-data/`** | The OpenRSC data tree as one loaded world — `GameEnvironment.load(conf, world, landscape)` over `ServerConf` (locates `conf/server`), `WorldProfile` (which world's locs and landscape), `LandscapeSource` (the classic `.jag`/`.mem` archives or an `.orsc` repack behind one interface), the def and locs parsers, the collision map, the id tables and `Location`. |
| **`account-data/`** | One account's durable state as frozen tick-values — `Inventory`/`Bank` containers, `Stats`, `Quests`, and the `AccountFacts` eligibility view the pathfinder plans against. |
| **`nav/`** | The pathfinder: the contraction-hierarchy engine (`openrsc.nav.engine`), the `Transport` contract, the route follower (`openrsc.nav.walk` — how a route is walked one tick at a time, shared by the bot's walker and the route probe), the full transport registry (`AllTransports` — every ladder, ship, gate, spell, agility shortcut and quest obstacle as data, with `AccountFacts` prerequisites and an `EngageSpec`), and `Pathfinder`, built once from a `GameEnvironment`. Planning-side only: executing a transport against the live world is the bot's job. |
| **`client-render/`** | Headless port of the OpenRSC client software renderer: rasterizes terrain + scenery models + walls from the `.orsc` cache into a plain `int[]` buffer. |
| **`world3d-bake/`** | Bakes the complete static `/api/world3d/*` + `/api/map/*` asset tree the 3D viewer consumes — world-mesh cells, engine textures, the object/door libraries, npc/item/font/scenery sprite atlases, and the per-layer player-sprite atlas the viewer composites appearances from. |
| **`viewer/`** | The standalone WebGL 3D world viewer (React + three.js). Renders the baked terrain + scenery mesh and billboarded NPC/player sprites. Entity data is injected via props — empty in the open-source demo. |
| **`map2d/`** | The standalone per-floor 2D world-map raster renderer (terrain PNG + walls overlay + GeoJSON feature layer). |

## Bake 2D world map

`map2d` bakes the world to per-floor raster layers — terrain in two treatments (full-saturation
`light` and the washed-out `dim` minimap shading), a walls overlay and an impassable-tile overlay —
plus a GeoJSON feature layer (doors + NPC spawns).

Prereqs: JDK 26 (the Gradle wrapper fetches Gradle itself), plus a checkout of [OpenRSC](https://gitlab.com/openrsc/openrsc) beside this repo (`../openrsc`).

```bash
scripts/render-map.sh map-out        # per-floor layers + previews/thumbnails into ./map-out
scripts/render-map.sh map-out 2      # …at 2× resolution
```

## Build the 3D viewer demo

Prereqs: JDK 26, Node, and a checkout of [OpenRSC](https://gitlab.com/openrsc/openrsc) beside this repo (`../openrsc`).

```bash
scripts/build-site.sh site      # server tree + client cache auto-resolve from the ../openrsc checkout
# serve it with anything:
(cd site && python3 -m http.server 8080)   # → http://localhost:8080
```

### Choosing the world

One OpenRSC checkout ships the data for several *worlds*, selected by the server's own `.conf`
files. `-Dopenrsc.world=<conf name>` (default: the authentic Uranium world) picks one, and it
carries everything that world changes — landscape, scenery/NPC placements, collision:

| | Uranium (`uranium.conf`) | Cabbage / Coleslaw (`rsccabbage.conf`) |
| --- | --- | --- |
| landscape | JAG `maps64` | `Custom_Landscape.orsc` |
| locs | base + Discontinued | + Runecraft, Harvesting, CustomQuest, Expansion, ModRoom, Auction, Ironman, … |

```bash
scripts/build-site.sh site                                      # Uranium
VITE_BASE=/cabbage/ scripts/build-site.sh site/cabbage rsccabbage   # Cabbage, served at /cabbage/
JAVA_OPTS=-Dopenrsc.world=rsccabbage scripts/render-map.sh map-out  # 2D layers only
```

`scripts/deploy.sh` bakes every published world and pushes them together — Uranium at the Pages
root, each extra world in its own subdirectory. Add one by appending to `EXTRA_WORLDS` there.

From Java the same thing is a `WorldProfile`, read from the server's file or built by hand:

```java
ServerConf conf = ServerConf.resolve();
WorldProfile cabbage = WorldProfile.fromConf(conf, "rsccabbage");
try (LandscapeSource landscape = cabbage.openLandscape(conf)) {
  GameEnvironment env = GameEnvironment.load(conf, cabbage, landscape);
}

WorldProfile mine = WorldProfile.builder("my-world")
    .locationData(2)
    .enable(Feature.CUSTOM_LANDSCAPE, Feature.RUNECRAFT)
    .build();
```

### Choosing the landscape directly

A world picks its own landscape, but `-Dopenrsc.landscape` overrides that — for baking an arbitrary
landscape file without a world behind it. The container is inferred from the path:

| Value | Landscape |
| --- | --- |
| *(unset)* | whatever the selected world uses |
| `/path/Custom_Landscape.orsc` | that `.orsc` ZIP |
| `/path/data/maps/maps63.jag` | JAG revision 63 (sibling `.mem` / `land63.*` picked up automatically) |
| `/path/data/maps` | JAG archives in that directory, revision 64 unless `-Dopenrsc.mapRev` says otherwise |

```bash
JAVA_OPTS=-Dopenrsc.landscape=/path/Custom_Landscape.orsc scripts/build-site.sh site
JAVA_OPTS=-Dopenrsc.landscape=/path/data/maps/maps63.jag  scripts/render-map.sh map-out
```

The `.orsc` repack is a genuinely different dataset from the JAG archives (it carries sectors
`maps64` does not, notably filled-in upper floors), so there is no fallback between the two: a
landscape that cannot be opened is a hard error rather than a silent switch to the other one.

### How it works

The viewer fetches everything from absolute static paths (`/api/world3d/*`,
`/api/world/*`, `/api/map/scenery-atlas.json`, `/api/nav/*`). **Player sprites are
composited in the browser** (`playerCompositor.ts`) from a per-layer atlas: the
bake ships every wearable sprite reduced to recolour-neutral pixels, and the
viewer replays the engine's recolour + layering to draw any `layers|colours`
appearance token — so all combinations render with nothing pre-baked per token.
Control verbs (`sendWalk`/`sendInteract`) are no-ops in the open-source build —
there's nothing to command — so the demo is purely a viewer.

**The living-world demo.** With the in-view **simulation** toggle on (default)
the page plays the server (`viewer/src/sim/demoServer.ts`): **every** NPC spawn
roams its box, aggroes, chases and fights by the stock server's rules, over the
pathfinder's own walk graph, and a few players wander Lumbridge. With a route
planned, a **route bot** walks it live — the pathfinder in its worker is the bot,
told each tick what it sees and answering what it sends. Untick the toggle to drop
the living world and reveal the raw `/api/world/npc-spawns.json` spawn table
(ghost markers) instead.

**Search.** The box under the toolbar finds every NPC spawn, transport, scenery
object and boundary object (doors, railings, webs) by name
(`viewer/src/search/`). Partial words match, and what matched most ranks first;
results group by name and def, so "spider" lists every spider spawn, each by its
server index, and "fountain" every fountain. A number is an NPC def id (`34`) and `#` with a number
a server index (`#1094`), both matched exactly; `|` searches for any of several
(`spider|monk`, `spider|123`, `34|23|74`). Every match's spawn tile is outlined
and, while the simulation runs, its live NPC ringed and named. Pointing at a
spawn lights the box it
wanders in, its tile, and its NPC's ring and nameplate (or an edge pointer when
it is out of view); pointing at a group lights all of it, and clicking a row flies the
camera there (and opens the card a click on it in the world would). A transport
also answers to the object it is fired from, so "ladder" finds the ladders
whatever the engine named them — once, as transports, not again as objects. Every
match is listed, five thousand trees included: the list draws only the lines in
view. Pointing at a group header lights the whole group, up to a thousand. The search
is kept in `?q=`, so a search is a link.

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

### 1. Entity data — the `observers` and `world` props

Live data arrives as two things: who is looking, and what is there.

`observers: Observer[]` is the first. Each `Observer` is one **vantage point** —
one of your bots, or (to mirror a whole server) every online player — carrying
its own position and vitals, and nothing about anyone else:

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
  /** Shows the PK-skull prefix on the nameplate. */
  skulled?: boolean;
  /** Latest overhead chat line… */
  msg?: string;
  /** …and the tick it was said (drawn as a chat bubble). */
  msgTick?: number;
  /** Action-bubble item id (small nameplate icon)… */
  bubble?: number;
  /** …and the tick it popped. */
  bubbleTick?: number;

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

`world: WorldEntities` is the second: everything the observers can see between
them, **deduplicated** — one entry per thing, not one per (thing, observer who
sees it).

```ts
interface WorldEntities {
  npcs: MapEntity[];
  players: MapEntity[];
  /** Live scenery — a mined rock, an opened chest (overrides the static bake). */
  objects: MapEntity[];
  /** Doors / gates, addressed by tile-edge (their `dir`). */
  wallObjects: MapEntity[];
  groundItems: MapEntity[];
}
```

Deduplicating is the feed's job, not the view's. Observers overlap heavily — a
crowded bank is the same forty players in eight lists — and whoever assembles
the feed already knows which sighting is freshest, where the view could only
take the first one it met and patch later chat onto it. Sending pooled state
also keeps the per-tick payload proportional to the world rather than to
observers × world.

Every entry is a `MapEntity`. `serverIndex` is the server's stable handle for it;
everything else is optional — send only what you know:

```ts
interface MapEntity {
  /** Server's stable id for this npc/player/object — the key the view tracks it by. */
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
  /** NPCs and players: the ground ring's own colour (0xRRGGBB), to mark this one out. The ring
   *  glides with the sprite, so it marks a walker smoothly. Hover, selection and combat still win… */
  ringColor?: number;
  /** …unless this is set: then only hover and selection do. */
  ringColorFirst?: boolean;
  /** NPCs and players: the nameplate's fill (0xRRGGBB), with dark text. */
  tagColor?: number;
  /** NPCs and players: show the nameplate whatever the tags toggle and zoom say. */
  tagAlways?: boolean;
  /** NPCs and players: an outline round the nameplate, for one of a set the host marks. */
  tagOutline?: number;
}
```

Feed **fresh arrays once per server tick**. The viewer:

- **interpolates** every entity from its previous tile to the new one over one
  tick — you send discrete positions each tick, *not* paths, and movement comes
  out smooth;
- drops any player whose `serverIndex` belongs to one of your observers, so an
  observer is never re-drawn as a player somebody else can see;
- reconciles observed `objects` / `wallObjects` against the static bake, rebuilding
  only the affected scenery cells (a mined rock, an opened door). An entry
  **leaving** the pool folds back to the static object — the tree regrew, the
  door closed — and an entry with `id: -1` is a tombstone: the static object is
  outright gone (delloc'd quest doors) and its tile renders empty.

```tsx
function Live() {
  const [tick, setTick] = useState<{observers: Observer[]; world: WorldEntities}>(EMPTY);
  useEffect(() => {
    const ws = new WebSocket("wss://your-backend/stream");
    ws.onmessage = e => setTick(JSON.parse(e.data));   // one snapshot per tick
    return () => ws.close();
  }, []);
  return <World3DView observers={tick.observers} world={tick.world} onPickEntity={setPicked} />;
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

### 2. Picks — the `onPickEntity` prop

Clicking a player, NPC, scenery object or boundary reports what it landed on.
This is the one prop besides `observers` you have to pass: the view draws no
info card of its own, because what to show for a pick — and where — belongs to
the app around it. That is also what lets an app showing this beside a 2D map
put one card behind both views rather than a private copy per view.

```tsx
const [picked, setPicked] = useState<PickedEntity | null>(null);

<World3DView observers={observers} onPickEntity={setPicked} />
{picked && <EntityCard pick={picked} onClose={() => setPicked(null)} />}
```

`null` means the click hit nothing, so whatever is open should close.
`EntityCard` is the default card, exported for embedders that would rather not
write one; it needs no data beyond the pick. Building your own instead? Read
`wornItems(appearance, wearables)` for the equipment rows — that is all the
built-in one does with the token. Its `details` prop takes labelled rows the
app knows and the pick doesn't, in place of the kind / id / in-combat line,
each copied to the clipboard on a click: the
demo's NPC card joins an NPC pick's `serverIndex` to its spawn
(`npc-spawns.json`) and its `npcId` to its def (`npc-defs.json`: stats, examine
text, and the respawn in the baked world's seconds, its respawn multiplier and
tick applied), and a search row opens the same card. While the list points at
nothing, the NPC whose card is open is the search's target: its tag amber, and
a pointer at the edge of the view while it is out of sight.

Because the pick is yours, so is what can produce one: `kind` is any string, not
just the five the view reports, so an overlay that wants a click of its own to
open the same card just calls the same setter. The demo's regions do exactly
that.

### Tools, camera and toggles — the rest of the props

Everything below is optional; the standalone demo wires a few to showcase them
and leaves the rest unset. Each prop's full contract is documented inline on
`World3DViewProps` in [`World3DView.tsx`](viewer/src/World3DView.tsx), which is
exported for wrapping the component.

| Prop | What it does |
| --- | --- |
| `trail: TrailPoint[]` | A recorded session's walked trail as an amber draped ribbon — the 3D twin of a 2D trail polyline. No `hop` flag; teleports are inferred from jumps. |
| `scrubbing` | True while `observers` carry a *reconstructed past instant*: positions snap instead of interpolating, so the scene lands in lockstep with your scrub slider. |
| `layers` | Per-class toggles (`bots`, `npcs`, `players`, `npcSpawns`, `transports`, `shops`, `wilderness`), all default on. Off skips that class's *assembly*, not just its visibility — the toggles double as perf levers. `wilderness` draws depth contours with level-number pills. |
| `transports` | Static transports on the active floor: each anchor object glows, hovering highlights its entry tiles and raises a clickable destination chip. |
| `shops` | Static shop registry. Shops attach to their keeper NPCs (a ⚖ glyph on the nameplate); clicking reports `onSelectShop(id)`. |
| `follow` | Username the camera follows — an observer or any other player in view, whichever answers to the name. Re-resolved against the live entities every frame, so a relog (which changes a player's server index) doesn't lose them. The camera snaps when the name changes and defers zoom-to-cursor to stay centred; a name nobody answers to parks it until they're seen again. |
| `floorIndex` / `onFloorIndexChange` | Controlled floor in RSC's semantic numbering (0 ground, 1/2 upper storeys, 3 underground). `FLOOR_KEYS_BY_INDEX` / `floorKeyForIndex` / `floorIndexForKey` are exported for hosts seeding from a URL. |
| `toggles` / `onTogglesChange` | Controlled roofs/sight/tags toggles, for an app that renders them in its own chrome. Whether the in-view toolbar exists is `toolbar`, separately. |
| `toolbar` | The in-view toolbar — floor buttons, toggles, fly. Default on; `extraToggles` appends to it and `hideSight` drops its sight checkbox. |
| `areaTool` / `areaRect` / `onAreaRectChange` | Area tool: a left drag sweeps a ground rectangle and reports the inclusive tile box; a committed rectangle has resize handles that report on every tile they cross. `areaRect` is controlled — it *is* the box, so keeping what `onAreaRectChange` hands you is what commits a sweep, dropping it is what discards one, and `null` clears. A gesture in flight is the exception: it draws locally until you release it. |
| `selectOnClick` | Which kinds a click marks selected in the view (white ring, outlined plate); default every kind. A kind it refuses is the app's to show — it hears every pick anyway — so there is one highlight however the pick was made: the demo hands NPCs to its search's target, since their card also opens from the search list. |
| `walkTool` | Clicks command the selected observer through `sendWalk`/`sendInteract` — left runs the top menu entry by stock client priority, right opens the full "Choose option" menu. |
| `useItem` / `onUseItemDone` | Armed "Use *item* with …" mode: the next world click dispatches use-item on the object/NPC/wall/ground under the cursor, then disarms. |
| `visible` | Pass false while another tab is showing: the component stays mounted (WebGL context and geometry survive for an instant switch-back) but the render loop parks. |
| `sharedView` | Mutable camera box shared with a 2D map: the active view writes on pan, the newly revealed view reads once to re-centre. |

Plus small knobs — `selectedBot`/`onSelectBot`, `focus`, `onFlyingChange`,
`extraToggles`, `hideSight` — documented inline.

**One-shot commands go through a ref**, not through props carrying a nonce you
bump to re-fire:

```tsx
const view = useRef<World3DHandle>(null);
<World3DView ref={view} … />

view.current?.panTo({x, z, floor});  // search hit, chat jump, "locate"
view.current?.snapNext();            // the next update is a jump in time, not motion
view.current?.toggleFly();           // start/stop the flyby (`onFlyingChange` reports it)
```

`snapNext` is latched rather than immediate: the data for a new instant lands
well after the request, so it marks whichever update carries it. `scrubbing`
can't express that — it stays false through playback, where interpolating is
right, and only the seek is discontinuous.

### 3. Overlays — your own decoration

The view draws the world and what the game defines. Anything a particular
deployment cares about — no-go boxes, raid markers, whatever you think of next —
is an **overlay**: it gets the frame, the coordinate transform, and the two
primitives that are tedious to rebuild correctly (draped outlines and pooled
labels).

```tsx
const regions = useMemo(() => regionOverlay(), []);   // impl STABLE
<World3DView … overlays={[overlay("regions", regions, myRegions)]} />  // data per render
```

Entries are keyed by `id`, so a fresh array of fresh objects every render costs
nothing — only add and remove are lifecycle events, and `data` is an argument to
`frame`. The `overlay()` helper checks that an implementation is paired with the
data it expects.

An overlay implements `frame` (runs every frame — so guard the expensive part
with `drapeGate()`, below), optionally `mount`/`dispose`, and optionally
`hitTest` + `hover` to respond to the cursor. Its context carries:

| | |
| --- | --- |
| `group` | Its own scene node, already excluded from the pick pass, torn down with it. |
| `toWorld(x, z)` | Tile → world with terrain height folded in. Takes **absolute** z, so an overlay never does floor arithmetic. |
| `npcAt(serverIndex)` | Where the view draws that live NPC this frame — mid-glide, as its sprite is — and its def id, or null (a ghost spawn is not live). For anything that should follow a walker smoothly; its tile jumps one a tick. |
| `floor` | The floor being shown; filtering your data to it is the overlay's job. |
| `heightsRev` | Changes when the terrain has **settled** at a new shape — redrape then, not on every streamed cell. |
| `zoomTiles` / `view` | View height in tiles (zoom-gate detail), and the frame's screen size, measured once. |
| `ribbon(…)` / `tags(…)` | A draped outline and a pooled label layer, both owned by the overlay. |

Geometry drapes at extrude time, so it has to be rebuilt when the data, the
floor or the settled terrain changes — and only then. `drapeGate()` is that
check: `const stale = drapeGate()` at construction, `if (!stale(ctx, data))
return` at the top of `frame`. Labels reproject every frame and stay outside it.

Two interaction rules are worth knowing before you design against them. **Hover
is broadcast**: every overlay whose `hitTest` claims the cursor is told, because
a character standing inside a zone must not stop the zone highlighting.
**Clicks are not part of the interface** — put them on a label by making its tag
pool `interactive` and setting its `onClick`. Geometry that takes clicks also
swallows them, and a zone is big enough that the hole would eat most of the
walkable ground it covers. A label only consumes the click itself: pressing one
and dragging pans the camera like a press anywhere else.

Draw order is named rather than numeric: `OVERLAY_BAND.ground` sits under the
character sprites (where an outline belongs), `aboveGround` over other ground
decoration, `chrome` over everything. Labels stack by `TAG_BAND` the same way — the same vocabulary in the DOM.

Overlays are how the app this was extracted from draws its own layers — no-go
zones with hover highlighting and a clickable label, cannon owners, victim
markers — none of which the library knows about. The view's own route and trail
ribbons are overlays too, same interface, same band, no private path. Both come from `pathOverlay(style)`, which is exported: a
draped polyline that breaks at floor changes, at flagged jumps and over gaps too
big to have been walked is a style away, not a rewrite. For everything else,
`viewer/src/world3dTestMain.tsx` has a worked example with a ribbon, a clickable
label and hover highlighting.

### 4. Control — `configureViewerHost`

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

`viewer/src/world3dTestMain.tsx` is a complete, runnable embedding: it loads
baked wander tracks, builds a `WorldEntities` from them every tick, holds the
pick and area-selection state the view reports, and adds an overlay of its own
with hover highlighting and a clickable label — the whole contract in a few
`useState`s. Swap its baked-track expansion for your live stream (and add
`configureViewerHost` for walk/interact) and you have a live client.

## Dev

```bash
# viewer with hot reload, served against a baked asset dir:
cd viewer && WORLD3D_SITE=/path/to/site npm run dev

# 2D map renderer:
scripts/render-map.sh map-out
```
