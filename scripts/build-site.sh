#!/usr/bin/env bash
# Build the full static 3D-viewer demo site: bake the /api asset tree, build the
# viewer, and assemble both into one directory a dumb static host can serve
# (GitHub Pages, `python -m http.server`, nginx, …).
#
#   scripts/build-site.sh [outDir] [world]
#
# outDir  site root (default: ./site).
# world   which OpenRSC world to bake, naming a server conf beside the conf tree
#         (default: the authentic/Uranium world). e.g. `rsccabbage`, `rsccoleslaw`.
#         The world selects its own landscape (Cabbage: Custom_Landscape.orsc) and
#         its own locs set (Runecrafting/Harvesting/CustomQuest/... content).
#
# The viewer resolves its /api/* fetches against VITE_BASE, so a world baked into a
# SUBDIRECTORY needs VITE_BASE to match that subpath:
#
#   scripts/build-site.sh site                            # Uranium at the root
#   VITE_BASE=/cabbage/ scripts/build-site.sh site/cabbage rsccabbage
#
# scripts/deploy.sh does exactly that for both worlds.
#
# Both the server game-data tree AND the client cache are located by ServerConf, walking up from the
# working directory for <ancestor>/openrsc/{server/conf/server, Client_Base/Cache} — so run from
# inside the openrsc checkout and neither needs an argument. Override either with
# -Dopenrsc.serverConfDir / OPENRSC_SERVER_CONF and -Dopenrsc.clientCacheDir / OPENRSC_CLIENT_CACHE.
#
# Terrain and collision come from that tree's JAG map archives (data/maps/maps64.jag + .mem), the
# dataset the server itself paths against. To bake a DIFFERENT landscape, point -Dopenrsc.landscape
# at it via JAVA_OPTS — an .orsc repack, another map revision, or a directory of JAG archives:
#
#   JAVA_OPTS=-Dopenrsc.landscape=/path/Custom_Landscape.orsc  scripts/build-site.sh
#   JAVA_OPTS=-Dopenrsc.landscape=/path/data/maps/maps63.jag   scripts/build-site.sh
#   JAVA_OPTS="-Dopenrsc.landscape=/path/data/maps -Dopenrsc.mapRev=31" scripts/build-site.sh
#
# There is no fallback between the two formats: a landscape that cannot be opened is a hard error.
#
# For a GitHub *project* Pages site (served under /<repo>/), set VITE_BASE=/<repo>/
# — but note the viewer fetches absolute /api/* paths, so a root deployment
# (user/org Pages or a custom domain) is simplest.
set -euo pipefail

OUT="${1:-site}"
WORLD="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# Word-split deliberately: JAVA_OPTS may carry several -D flags.
read -r -a JAVA_OPTS_ARR <<< "${JAVA_OPTS:-}"
# -Dopenrsc.world picks the world for every java step below (bake, wander tracks,
# 2D layers) so they can never disagree about which world they are rendering.
if [[ -n "$WORLD" ]]; then
  JAVA_OPTS_ARR+=("-Dopenrsc.world=$WORLD")
  echo "==> world: $WORLD"
else
  echo "==> world: authentic (uranium)"
fi

echo "==> [1/5] baking static /api asset tree into $OUT/api"
mvn -q -f "$ROOT/pom.xml" -pl world3d-bake,map2d -am -DskipTests package
java "${JAVA_OPTS_ARR[@]}" -jar "$ROOT/world3d-bake/target/world3d-bake.jar" "$OUT"

echo "==> [2/5] baking collision-aware NPC/player wander tracks"
# DemoEntityBaker lives in map2d (it needs the CollisionMap); run it off the
# fat jar's classpath (its manifest main-class is the 2D map renderer).
java "${JAVA_OPTS_ARR[@]}" -cp "$ROOT/map2d/target/rsc-map-renderer.jar" \
  openrsc.map.DemoEntityBaker "$OUT"

echo "==> [3/5] rendering the 2D world-map layers into $OUT/2d"
# Per floor: light/dim terrain, walls + blocked overlays, a GeoJSON feature
# layer, and a flattened preview + thumbnail.
java "${JAVA_OPTS_ARR[@]}" -jar "$ROOT/map2d/target/rsc-map-renderer.jar" "$OUT/2d"
cp "$ROOT/map2d/web/index.html" "$OUT/2d/index.html"   # browsable gallery at /2d/

echo "==> [4/5] building the viewer (static JS/HTML)"
( cd "$ROOT/viewer" && npm ci --no-audit --no-fund && npm run build )

echo "==> [5/5] assembling site"
cp -r "$ROOT/viewer/dist/." "$OUT/"

echo "==> done. Static site at: $OUT"
echo "    preview:  (cd '$OUT' && python3 -m http.server 8080)  ->  http://localhost:8080"
