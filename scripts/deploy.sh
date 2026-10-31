#!/usr/bin/env bash
# Builds the site for every published world into ./site and publishes it with deploy-pages.sh:
# Uranium at the root, each EXTRA_WORLDS entry in its own subdirectory. Each world is a full site of
# its own, since landscape, locs and collision all differ between worlds. --no-bake keeps ./site's
# bakes and rebuilds only the pathfinder and the viewers.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SITE="$ROOT/site"

NO_BAKE=""
for arg in "$@"; do
  case "$arg" in
    --no-bake) NO_BAKE=1 ;;
    *) echo "usage: scripts/deploy.sh [--no-bake]" >&2; exit 2 ;;
  esac
done

# The subpath the site is served from; each world's viewer resolves /api against its own.
BASE="${PAGES_BASE:-/rsc-map-renderer/}"

# "<server conf name>:<subdirectory>"
EXTRA_WORLDS=(
  "rsccabbage:cabbage"
)

if [[ -n "$NO_BAKE" ]]; then
  if [[ ! -d "$SITE" ]]; then
    echo "--no-bake: there is no $SITE to keep — run without it once to bake the worlds" >&2
    exit 1
  fi
  echo "############ keeping the bakes in $SITE; rebuilding the pathfinder and the viewers"
else
  rm -rf "$SITE"
  mkdir -p "$SITE"
fi
BUILD_ARGS=()
[[ -n "$NO_BAKE" ]] && BUILD_ARGS+=(--no-bake)

echo "############ world: uranium (authentic) -> ${BASE}"
VITE_BASE="$BASE" "$ROOT/scripts/build-site.sh" "${BUILD_ARGS[@]}" "$SITE"

for entry in "${EXTRA_WORLDS[@]}"; do
  conf="${entry%%:*}"
  dir="${entry##*:}"
  echo
  echo "############ world: $conf -> ${BASE}${dir}/"
  VITE_BASE="${BASE}${dir}/" "$ROOT/scripts/build-site.sh" "${BUILD_ARGS[@]}" "$SITE/$dir" "$conf"
done

echo
"$ROOT/scripts/deploy-pages.sh" "$SITE"

echo
echo "Live: https://swazrgb.github.io/rsc-map-renderer/"
for entry in "${EXTRA_WORLDS[@]}"; do
  echo "      https://swazrgb.github.io/rsc-map-renderer/${entry##*:}/"
done
