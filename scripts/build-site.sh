#!/usr/bin/env bash
# Build the full static 3D-viewer demo site: bake the /api asset tree, build the
# viewer, and assemble both into one directory a dumb static host can serve
# (GitHub Pages, `python -m http.server`, nginx, …).
#
#   scripts/build-site.sh <clientCacheDir> [outDir]
#
# <clientCacheDir>  stock OpenRSC client cache (Authentic_Landscape.orsc,
#                   Authentic_Sprites.orsc, Custom_Sprites.osar + defs).
# outDir            site root (default: ./site).
#
# The server game-data tree (locs + JAG maps) is located by ServerConf — run
# from inside the openrsc checkout, or pass
# -Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server via MAVEN_OPTS.
#
# For a GitHub *project* Pages site (served under /<repo>/), set VITE_BASE=/<repo>/
# — but note the viewer fetches absolute /api/* paths, so a root deployment
# (user/org Pages or a custom domain) is simplest.
set -euo pipefail

CACHE="${1:?usage: build-site.sh <clientCacheDir> [outDir]}"
OUT="${2:-site}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

echo "==> [1/4] baking static /api asset tree into $OUT/api"
mvn -q -f "$ROOT/pom.xml" -pl world3d-bake,map2d -am -DskipTests package
java -jar "$ROOT/world3d-bake/target/world3d-bake.jar" "$CACHE" "$OUT"

echo "==> [2/4] baking collision-aware NPC/player wander tracks"
# DemoEntityBaker lives in map2d (it needs the CollisionMap); run it off the
# fat jar's classpath (its manifest main-class is the 2D map renderer).
java -cp "$ROOT/map2d/target/rsc-map-renderer.jar" openrsc.map.DemoEntityBaker "$OUT"

echo "==> [3/4] building the viewer (static JS/HTML)"
( cd "$ROOT/viewer" && npm ci --no-audit --no-fund && npm run build )

echo "==> [4/4] assembling site"
cp -r "$ROOT/viewer/dist/." "$OUT/"

echo "==> done. Static site at: $OUT"
echo "    preview:  (cd '$OUT' && python3 -m http.server 8080)  ->  http://localhost:8080"
