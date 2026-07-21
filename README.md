# rsc-map-renderer

Software to generate a 2D & 3D render of the world of RuneScape Classic.

## Modules

| Module | What it is |
| --- | --- |
| **`game-data/`** | Shared loaders for the OpenRSC data tree — `ServerConf` (locates `conf/server`), the `SceneryLocs`/`BoundaryLocs`/`NpcLocs` parsers, and the classic `.jag`/`.mem` landscape readers. |
| **`client-render/`** | Headless port of the OpenRSC client software renderer (idlersc lineage): rasterizes terrain + scenery models + walls from the `.orsc` cache into a plain `int[]` buffer. |
| **`world3d-bake/`** | Bakes the complete static `/api/world3d/*` + `/api/map/*` asset tree the 3D viewer consumes — world-mesh cells, engine textures, the object/door libraries, npc/item/font/scenery sprite atlases, and the per-layer player-sprite atlas the viewer composites appearances from. |
| **`viewer/`** | The standalone WebGL 3D world viewer (React + three.js). Renders the baked terrain + scenery mesh and billboarded NPC/player sprites. Entity data is injected via props — empty in the open-source demo. |
| **`map2d/`** | The standalone per-floor 2D world-map raster renderer (terrain PNG + walls overlay + GeoJSON feature layer). |

## Build the 3D viewer demo

Prereqs: a JDK, Maven, Node, and a checkout of the [OpenRSC](https://gitlab.com/openrsc/openrsc) server + stock client
cache. One command bakes the assets, builds the viewer, and assembles a static
site:

```bash
scripts/build-site.sh ../openrsc/Client_Base/Cache site
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

## Dev

```bash
# viewer with hot reload, served against a baked asset dir:
cd viewer && WORLD3D_SITE=/path/to/site npm run dev

# 2D map renderer:
mvn -pl map2d -am package && java -jar map2d/target/rsc-map-renderer.jar map-out
```
