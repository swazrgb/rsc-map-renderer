#!/usr/bin/env bash
# Bake the 2D web-map layers from the OpenRSC game data — per floor: two terrain
# rasters (light + dim), a walls overlay, a blocked-tile overlay, and a
# doors/NPC-spawn GeoJSON feature layer.
#
#   scripts/render-map.sh [outDir] [scale]
#
# outDir  output directory (default: ./map-out).
# scale   resolution multiplier passed as -Dmap.scale (default: 1 = the native
#         3 px/tile minimap scale; 2 doubles it, and so on). Every layer scales
#         together so they stay in registration.
#
# The server game-data tree (locs + JAG maps) is located by ServerConf — run
# from inside the openrsc checkout, or pass
# -Dopenrsc.serverConfDir=/path/to/openrsc/server/conf/server via MAVEN_OPTS.
#
# Terrain and collision come from that tree's JAG map archives (data/maps/maps64.jag + .mem), the
# dataset the server itself paths against. To render a DIFFERENT landscape, point
# -Dopenrsc.landscape at it via JAVA_OPTS — an .orsc repack, another map revision, or a directory
# of JAG archives:
#
#   JAVA_OPTS=-Dopenrsc.landscape=/path/Custom_Landscape.orsc  scripts/render-map.sh
#   JAVA_OPTS=-Dopenrsc.landscape=/path/data/maps/maps63.jag   scripts/render-map.sh
#   JAVA_OPTS="-Dopenrsc.landscape=/path/data/maps -Dopenrsc.mapRev=31" scripts/render-map.sh
#
# There is no fallback between the two formats: a landscape that cannot be opened is a hard error.
set -euo pipefail

OUT="${1:-map-out}"
SCALE="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Word-split deliberately: JAVA_OPTS may carry several -D flags.
read -r -a JAVA_OPTS_ARR <<< "${JAVA_OPTS:-}"

echo "==> [1/2] building the 2D map renderer (map2d)"
mvn -q -f "$ROOT/pom.xml" -pl map2d -am -DskipTests package

echo "==> [2/2] rendering map layers into $OUT (scale ${SCALE}x)"
java -Dmap.scale="$SCALE" "${JAVA_OPTS_ARR[@]}" \
  -jar "$ROOT/map2d/target/rsc-map-renderer.jar" "$OUT"

echo "==> done. Layers in: $OUT"
