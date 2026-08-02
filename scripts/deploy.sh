#!/usr/bin/env bash
# One-shot: build the 3D-viewer demo for EVERY published world and publish them to
# GitHub Pages for swazrgb/rsc-map-renderer  ->  https://swazrgb.github.io/rsc-map-renderer/
#
#   scripts/deploy.sh
#
# What it does:
#   1. bakes the authentic (Uranium) world into ./site           -> /rsc-map-renderer/
#   2. bakes each extra world into ./site/<subdir>               -> /rsc-map-renderer/<subdir>/
#   3. force-pushes the assembled ./site to the `gh-pages` branch.
#
# Each world is a full, self-contained site (its own viewer build, /api tree and 2D
# layers), because a world's landscape, scenery/npc locs and collision all differ —
# Cabbage is not a skin over Uranium, it is different data.
#
# Enable Pages once: repo Settings -> Pages -> Source: branch `gh-pages` / (root).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SITE="$ROOT/site"

# Pages project subpath. The viewer resolves every /api/* fetch against its build
# base, so each world's base must be the subpath it will actually be served from.
BASE="${PAGES_BASE:-/rsc-map-renderer/}"

# Extra worlds, as "<server conf name>:<subdirectory>". The authentic world is baked
# at the root below, preserving the existing published URL.
EXTRA_WORLDS=(
  "rsccabbage:cabbage"
)

rm -rf "$SITE"
mkdir -p "$SITE"

echo "############ world: uranium (authentic) -> ${BASE}"
VITE_BASE="$BASE" "$ROOT/scripts/build-site.sh" "$SITE"

for entry in "${EXTRA_WORLDS[@]}"; do
  conf="${entry%%:*}"
  dir="${entry##*:}"
  echo
  echo "############ world: $conf -> ${BASE}${dir}/"
  VITE_BASE="${BASE}${dir}/" "$ROOT/scripts/build-site.sh" "$SITE/$dir" "$conf"
done

echo
"$ROOT/scripts/deploy-pages.sh" "$SITE"

echo
echo "Live: https://swazrgb.github.io/rsc-map-renderer/"
for entry in "${EXTRA_WORLDS[@]}"; do
  echo "      https://swazrgb.github.io/rsc-map-renderer/${entry##*:}/"
done
