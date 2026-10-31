#!/usr/bin/env bash
# Publishes a built site directory (default ./site) to the gh-pages branch of target: a git URL or
# path, or a remote of this checkout. Default: $PAGES_REPO, else the remote `pages`, added when
# missing. Never origin by default: where this directory sits inside another repository, origin is
# that one, and the push below is forced.
set -euo pipefail

SITE="${1:-site}"
TARGET="${2:-${PAGES_REPO:-pages}}"
PAGES_URL=https://github.com/swazrgb/rsc-map-renderer.git
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

[ -f "$SITE/index.html" ] || {
  echo "no $SITE/index.html — run scripts/build-site.sh first" >&2
  exit 1
}
if [[ "$TARGET" == *://* || "$TARGET" == *@*:* || -e "$TARGET" ]]; then
  URL="$TARGET"
else
  # Kept out of `git fetch --all`, which would pull the baked gh-pages into this repository.
  if [ "$TARGET" = pages ] && ! git -C "$ROOT" remote get-url pages >/dev/null 2>&1; then
    echo "==> adding remote pages: $PAGES_URL"
    git -C "$ROOT" remote add pages "$PAGES_URL"
    git -C "$ROOT" config remote.pages.skipFetchAll true
  fi
  URL="$(git -C "$ROOT" remote get-url "$TARGET")"
fi
echo "==> publishing $SITE to gh-pages of $URL"

# Pages runs Jekyll by default, which skips some files.
touch "$SITE/.nojekyll"

# A throwaway repository, force-pushed, so the large baked assets never enter any branch's history.
WORK="$(mktemp -d)"
cp -a "$SITE"/. "$WORK"/
git -C "$WORK" init -q
git -C "$WORK" checkout -q -b gh-pages
git -C "$WORK" add -A
git -C "$WORK" -c user.email=deploy@local -c user.name=deploy \
  commit -qm "deploy $(date -u +%Y-%m-%dT%H:%M:%SZ)"
git -C "$WORK" push -f "$URL" gh-pages
rm -rf "$WORK"
