#!/usr/bin/env bash
# Builds the static 3D-viewer site into outDir (default ./site): bakes the /api asset tree, renders
# the 2D map layers, builds the viewer, and assembles them for any static host.
#
# world names a server .conf (e.g. rsccabbage); default is the authentic Uranium world. The game
# data and client cache are found as ServerConf finds them, from an openrsc checkout above the
# working directory; JAVA_OPTS passes -D flags to every java step (-Dopenrsc.landscape, ...).
# The viewer resolves /api against VITE_BASE, so a site served from a subpath needs it set to that.
#
# --no-bake (or SKIP_BAKE=1) keeps the bake and 2D layers already in outDir and rebuilds only the
# pathfinder's WebAssembly and the viewer.
set -euo pipefail

usage() {
  echo "usage: scripts/build-site.sh [--no-bake] [outDir] [world]"
}

NO_BAKE="${SKIP_BAKE:-}"
POSITIONAL=()
for arg in "$@"; do
  case "$arg" in
    --no-bake) NO_BAKE=1 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option: $arg" >&2; usage >&2; exit 2 ;;
    *) POSITIONAL+=("$arg") ;;
  esac
done
if (( ${#POSITIONAL[@]} > 2 )); then
  echo "too many arguments: ${POSITIONAL[*]}" >&2; usage >&2; exit 2
fi

OUT="${POSITIONAL[0]:-site}"
WORLD="${POSITIONAL[1]:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# Word-split on purpose: JAVA_OPTS may carry several flags.
read -r -a JAVA_OPTS_ARR <<< "${JAVA_OPTS:-}"
if [[ -n "$WORLD" ]]; then
  JAVA_OPTS_ARR+=("-Dopenrsc.world=$WORLD")
  echo "==> world: $WORLD"
else
  echo "==> world: authentic (uranium)"
fi

if [[ -n "$NO_BAKE" ]]; then
  if [[ ! -f "$OUT/api/world3d/manifest.json" || ! -f "$OUT/api/nav/world.snap.gz" ]]; then
    echo "--no-bake: no bake in $OUT (api/world3d/manifest.json or api/nav/world.snap.gz missing)" >&2
    echo "           run once without --no-bake to bake the world first" >&2
    exit 1
  fi
  # The viewer reads what the baker wrote, so a bake of another format would draw wrong or nothing.
  # `|| true`: under pipefail a grep that matches nothing would end the script without a message.
  baked=$( (grep -o '"version": *[0-9]*' "$OUT/api/world3d/manifest.json" || true) | head -1 | tr -dc 0-9)
  wanted=$( (grep -o 'FORMAT_VERSION = [0-9]*' \
    "$ROOT/world3d-bake/src/main/java/openrsc/maprender/bake/WorldMeshExporter.java" || true) | tr -dc 0-9)
  if [[ -z "$wanted" ]]; then
    echo "--no-bake: cannot read FORMAT_VERSION from WorldMeshExporter.java" >&2
    exit 1
  fi
  if [[ "$baked" != "$wanted" ]]; then
    echo "--no-bake: $OUT holds a v${baked:-?} bake; this build bakes v$wanted" >&2
    echo "           run once without --no-bake to rebake the world" >&2
    exit 1
  fi
  echo "==> [1/5] reusing the baked /api asset tree in $OUT/api (--no-bake)"
  "$ROOT/gradlew" -q -p "$ROOT" :nav-web:assemble
else
  echo "==> [1/5] baking static /api asset tree into $OUT/api"
  "$ROOT/gradlew" -q -p "$ROOT" :world3d-bake:shadowJar :map2d:shadowJar :nav-web:assemble
  java "${JAVA_OPTS_ARR[@]}" -jar "$ROOT/world3d-bake/target/world3d-bake.jar" "$OUT"
fi

echo "==> [2/5] the route planner's pathfinder into $OUT/api/nav"
mkdir -p "$OUT/api/nav"
cp "$ROOT/nav-web/target/nav-web/nav.wasm" \
   "$ROOT/nav-web/target/nav-web/wasm-gc-module-runtime.js" "$OUT/api/nav/"

if [[ -n "$NO_BAKE" ]]; then
  echo "==> [3/5] 2D layers: kept from the bake (--no-bake)"
else
  # Nothing writes or reads api/demo; a stale one left in outDir is dropped.
  rm -rf "$OUT/api/demo"

  echo "==> [3/5] rendering the 2D world-map layers into $OUT/2d"
  java "${JAVA_OPTS_ARR[@]}" -jar "$ROOT/map2d/target/rsc-map-renderer.jar" "$OUT/2d"
  cp "$ROOT/map2d/web/index.html" "$OUT/2d/index.html"
fi

echo "==> [4/5] building the viewer (static JS/HTML)"
# The bake's stamp versions every /api URL the viewer fetches.
BAKE="$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1])).baked)' \
  "$OUT/api/world3d/manifest.json")"
( cd "$ROOT/viewer" && npm ci --no-audit --no-fund && VITE_BAKE="$BAKE" npm run build )

echo "==> [5/5] assembling site"
cp -r "$ROOT/viewer/dist/." "$OUT/"

echo "==> done. Static site at: $OUT"
echo "    preview:  (cd '$OUT' && python3 -m http.server 8080)  ->  http://localhost:8080"
