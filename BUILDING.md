# Building

## Bake 2D world map

`map2d` bakes the world to per-floor raster layers — terrain in two treatments (full-saturation
`light` and the washed-out `dim` minimap shading), a walls overlay and an impassable-tile overlay —
plus a GeoJSON feature layer (doors + NPC spawns).

Prereqs: JDK 26 (the Gradle wrapper fetches Gradle itself), plus a checkout of [OpenRSC](https://gitlab.com/openrsc/openrsc) beside this repo (`../openrsc`).

```bash
scripts/render-map.sh map-out        # per-floor layers + previews/thumbnails into ./map-out
scripts/render-map.sh map-out 2      # …at 2× resolution
```

## Build the session proxy

Prereqs: JDK 26 and `zip`, plus a checkout of [OpenRSC](https://gitlab.com/openrsc/openrsc) beside this repo
(`../openrsc`), as for the maps. Clone this repo with `--recursive`, or run `git submodule update --init`, for the
browser game client.

```bash
scripts/release-session-proxy.sh target/session-proxy.zip   # the same zip as the release's
```

Or build the proxy and the client on their own, and run the proxy from this repo:

```bash
./gradlew :session-proxy-core:shadowJar
orsc-teavm-client/gradlew -p orsc-teavm-client assemble -Dopenrsc.cache.dir="$PWD/../openrsc/Client_Base/Cache"
java -jar session-proxy-core/target/session-proxy.jar --client orsc-teavm-client/target/client-base-teavm-1.0-SNAPSHOT
```

The proxy looks for the OpenRSC data in an `openrsc` folder in the folder it runs from, or in any folder above that:
run from this repo, it finds the checkout beside it. `-Dopenrsc.serverConfDir=<openrsc>/server/conf/server` names one.

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
  ServerData data = ServerData.load(conf, cabbage, landscape);
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

## Dev

```bash
# viewer with hot reload, served against a baked asset dir:
cd viewer && WORLD3D_SITE=/path/to/site npm run dev

# 2D map renderer:
scripts/render-map.sh map-out
```
