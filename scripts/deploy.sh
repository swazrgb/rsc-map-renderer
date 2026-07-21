#!/usr/bin/env bash
# One-shot: build the 3D-viewer demo and publish it to GitHub Pages for
# swazrgb/rsc-map-renderer  ->  https://swazrgb.github.io/rsc-map-renderer/
#
#   scripts/deploy.sh /path/to/Client_Base/Cache
#
# What it does:
#   1. bakes the static /api asset tree + wander tracks (needs the game data),
#   2. builds the viewer with the project-Pages base (/rsc-map-renderer/),
#   3. assembles ./site and force-pushes it to the `gh-pages` branch.
#
# Enable Pages once: repo Settings -> Pages -> Source: branch `gh-pages` / (root).
set -euo pipefail

CACHE="${1:?usage: scripts/deploy.sh <clientCacheDir>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Served under https://<user>.github.io/rsc-map-renderer/, so the viewer's
# absolute /api/* fetches must resolve under that subpath.
export VITE_BASE=/rsc-map-renderer/

"$ROOT/scripts/build-site.sh" "$CACHE" "$ROOT/site"
"$ROOT/scripts/deploy-pages.sh" "$ROOT/site"

echo
echo "Live (after the first Pages build): https://swazrgb.github.io/rsc-map-renderer/"
