#!/usr/bin/env bash
# Publish a built ./site directory to this repo's `gh-pages` branch, which
# GitHub Pages then serves. Run build-site.sh first — and if this is a *project*
# Pages site (served at https://<user>.github.io/<repo>/), build it with the
# matching base so the viewer's /api/* fetches resolve under the subpath:
#
#   VITE_BASE=/<repo>/ scripts/build-site.sh /path/to/Client_Base/Cache site
#   scripts/deploy-pages.sh site
#
# For a user/org Pages site or a custom domain (served at the root), the default
# base ("/") is correct — no VITE_BASE needed.
#
#   scripts/deploy-pages.sh [siteDir] [gitRemote]
set -euo pipefail

SITE="${1:-site}"
REMOTE="${2:-origin}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

[ -f "$SITE/index.html" ] || {
  echo "no $SITE/index.html — run scripts/build-site.sh first" >&2
  exit 1
}
URL="$(git -C "$ROOT" remote get-url "$REMOTE")"

# GitHub Pages runs Jekyll by default (which skips some files); .nojekyll serves
# the baked tree verbatim.
touch "$SITE/.nojekyll"

# Build the branch in a throwaway repo and force-push it, so the (large) baked
# assets live only on gh-pages, never bloating main's history.
WORK="$(mktemp -d)"
cp -a "$SITE"/. "$WORK"/
git -C "$WORK" init -q
git -C "$WORK" checkout -q -b gh-pages
git -C "$WORK" add -A
git -C "$WORK" -c user.email=deploy@local -c user.name=deploy \
  commit -qm "deploy $(date -u +%Y-%m-%dT%H:%M:%SZ)"
git -C "$WORK" push -f "$URL" gh-pages
rm -rf "$WORK"
