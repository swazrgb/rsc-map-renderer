#!/usr/bin/env bash
# Build the full static 3D-viewer demo site: bake the /api asset tree, build the
# viewer, and assemble both into one directory a dumb static host can serve
# (GitHub Pages, `python -m http.server`, nginx, …).
#
#   scripts/build-site.sh [outDir]
#
# outDir  site root (default: ./site).
#
# Both the server game-data tree AND the client cache are located by ServerConf, walking up from the
# working directory for <ancestor>/openrsc/{server/conf/server, Client_Base/Cache} — so run from
# inside the openrsc checkout and neither needs an argument. Override either with
# -Dopenrsc.serverConfDir / OPENRSC_SERVER_CONF and -Dopenrsc.clientCacheDir / OPENRSC_CLIENT_CACHE.
#
# For a GitHub *project* Pages site (served under /<repo>/), set VITE_BASE=/<repo>/
# — but note the viewer fetches absolute /api/* paths, so a root deployment
# (user/org Pages or a custom domain) is simplest.
set -euo pipefail

OUT="${1:-site}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

echo "==> [1/5] baking static /api asset tree into $OUT/api"
mvn -q -f "$ROOT/pom.xml" -pl world3d-bake,map2d -am -DskipTests package
java -jar "$ROOT/world3d-bake/target/world3d-bake.jar" "$OUT"

echo "==> [2/5] baking collision-aware NPC/player wander tracks"
# DemoEntityBaker lives in map2d (it needs the CollisionMap); run it off the
# fat jar's classpath (its manifest main-class is the 2D map renderer).
java -cp "$ROOT/map2d/target/rsc-map-renderer.jar" openrsc.map.DemoEntityBaker "$OUT"

echo "==> [3/5] rendering the 2D world-map layers into $OUT/2d"
# Per floor: light/dim terrain, walls + blocked overlays, a GeoJSON feature
# layer, and a flattened preview + thumbnail.
java -jar "$ROOT/map2d/target/rsc-map-renderer.jar" "$OUT/2d"
cp "$ROOT/map2d/web/index.html" "$OUT/2d/index.html"   # browsable gallery at /2d/

echo "==> [4/5] building the viewer (static JS/HTML)"
( cd "$ROOT/viewer" && npm ci --no-audit --no-fund && npm run build )

echo "==> [5/5] assembling site"
cp -r "$ROOT/viewer/dist/." "$OUT/"

echo "==> done. Static site at: $OUT"
echo "    preview:  (cd '$OUT' && python3 -m http.server 8080)  ->  http://localhost:8080"
