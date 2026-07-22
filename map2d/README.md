# rsc-map-renderer

Standalone world-map renderer for the OpenRSC game data. It loads the server's on-disk data tree (defs, locs, skill extras and the
JAG landscape) and bakes the world map per floor:

- two terrain colour rasters (PNG): `light` (full saturation) and `dim` (the washed-out
  stock-minimap shading),
- a walls overlay and a blocked-tile overlay (separate transparent PNGs), and
- a GeoJSON feature layer (doors + NPC spawns, with wander boxes).

## Data source

The server's `conf/server` tree is located by `ServerConf.resolve()`, which walks **up** from the
working directory looking for `<ancestor>/openrsc/server/conf/server`. Running from anywhere inside
the `openrsc` checkout therefore needs no configuration. To point elsewhere:

- `-Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server`, or
- the `OPENRSC_SERVER_CONF` environment variable.

Collision is loaded from the authentic JAG map archives (`data/maps/maps64.jag` + `.mem`) when
present, falling back to `data/Authentic_Landscape.orsc`.

## Build & run

```bash
# convenience wrapper (builds the jar, then renders): outDir defaults to ./map-out
scripts/render-map.sh [outDir] [scale]

# or by hand — build a runnable fat jar
mvn -pl . package

# render to ./map-out (run from inside the openrsc checkout)
java -jar target/rsc-map-renderer.jar [outputDir]

# or without packaging
mvn exec:java -Dexec.args="map-out"
```

Pass `-Dmap.scale=N` (or the script's `scale` arg) to bake every layer at `N`× the native
3 px/tile — the layers scale together so they stay in registration.

Output, for each floor `f` in `0..3` (3 px/tile by default, `×scale` with `-Dmap.scale`):

- `floor-f.light.png`   — terrain colour raster, full saturation (the brighter web-map look)
- `floor-f.dim.png`     — terrain colour raster, washed out (the stock-minimap shading)
- `floor-f.walls.png`   — wall + diagonal outlines only (transparent RGBA PNG)
- `floor-f.blocked.png` — impassable-tile fill only (transparent RGBA PNG)
- `floor-f.geojson`     — `FeatureCollection` of door edges and NPC spawns (each spawn carries id,
  name, combat level, aggressive flag and, when it roams, a wander-box polygon)
