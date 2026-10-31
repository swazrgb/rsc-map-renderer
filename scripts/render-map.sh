#!/usr/bin/env bash
# Renders the 2D map layers into outDir (default ./map-out) at scale times the native resolution
# (default 1). The game data is found as ServerConf finds it; JAVA_OPTS passes -D flags to java.
set -euo pipefail

OUT="${1:-map-out}"
SCALE="${2:-1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Word-split on purpose: JAVA_OPTS may carry several flags.
read -r -a JAVA_OPTS_ARR <<< "${JAVA_OPTS:-}"

echo "==> [1/2] building the 2D map renderer (map2d)"
"$ROOT/gradlew" -q -p "$ROOT" :map2d:shadowJar

echo "==> [2/2] rendering map layers into $OUT (scale ${SCALE}x)"
java -Dmap.scale="$SCALE" "${JAVA_OPTS_ARR[@]}" \
  -jar "$ROOT/map2d/target/rsc-map-renderer.jar" "$OUT"

echo "==> done. Layers in: $OUT"
