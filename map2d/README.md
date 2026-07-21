# rsc-map-renderer

Standalone world-map renderer for the OpenRSC game data. It loads the server's on-disk data tree (defs, locs, skill extras and the
JAG landscape) and bakes the world map per floor:

- a terrain colour raster (PNG),
- a walls / diagonals / blocked-tile overlay (transparent PNG), and
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
# build a runnable fat jar
mvn -pl . package

# render to ./map-out (run from inside the openrsc checkout)
java -jar target/rsc-map-renderer.jar [outputDir]

# or without packaging
mvn exec:java -Dexec.args="map-out"
```

Output, for each floor `f` in `0..3`:

- `floor-f.png`       — terrain colour raster (3 px/tile, matches the stock minimap)
- `floor-f.walls.png` — walls + diagonals + impassable-tile overlay (transparent RGBA PNG)
- `floor-f.geojson`   — `FeatureCollection` of door edges and NPC spawns (each spawn carries id,
  name, combat level, aggressive flag and, when it roams, a wander-box polygon)
