#!/usr/bin/env bash
# Builds the session proxy's release zip: session-proxy.jar, the browser game client in client/, and
# the OpenRSC data the proxy reads, laid out where it looks for them (openrsc/server/conf/server, the
# world .conf files beside it, openrsc/Client_Base/Cache), so it runs from the unpacked folder with no
# settings. session-proxy.cmd starts it with a double click on Windows.
#
# The browser client is the orsc-teavm-client submodule (git submodule update --init), or a checkout
# of it beside this repo; the OpenRSC data comes from a checkout of OpenRSC beside this repo, or from
# OPENRSC_SERVER_CONF and OPENRSC_CLIENT_CACHE as the proxy reads them.
set -euo pipefail

usage() {
  echo "usage: scripts/release-session-proxy.sh [out.zip]"
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  -*) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-session-proxy.zip}"
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
CONF="${OPENRSC_SERVER_CONF:-$ROOT/../openrsc/server/conf/server}"
CACHE="${OPENRSC_CLIENT_CACHE:-$ROOT/../openrsc/Client_Base/Cache}"
CLIENT=
for dir in "$ROOT/orsc-teavm-client" "$ROOT/../orsc-teavm-client"; do
  if [[ -f "$dir/build.gradle.kts" ]]; then
    CLIENT="$(cd "$dir" && pwd)"
    break
  fi
done
if [[ -z "$CLIENT" ]]; then
  echo "no browser client: run git submodule update --init, or check out orsc-teavm-client beside this repo" >&2
  exit 1
fi
if [[ ! -d "$CONF" || ! -d "$CACHE" ]]; then
  echo "no OpenRSC data at $CONF and $CACHE: clone https://gitlab.com/openrsc/openrsc beside this repo" >&2
  exit 1
fi
CONF="$(cd "$CONF" && pwd)"
CACHE="$(cd "$CACHE" && pwd)"

"$ROOT/gradlew" -q -p "$ROOT" :session-proxy-core:shadowJar
"$CLIENT/gradlew" -q -p "$CLIENT" assemble -Dopenrsc.cache.dir="$CACHE"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
DIR="$STAGE/session-proxy"
mkdir -p "$DIR/openrsc/server/conf" "$DIR/openrsc/Client_Base"
cp "$ROOT/session-proxy-core/target/session-proxy.jar" "$ROOT/LICENSE" "$DIR/"
cp -r "$CLIENT/target/client-base-teavm-1.0-SNAPSHOT" "$DIR/client"
cp -r "$CONF" "$DIR/openrsc/server/conf/server"
cp "$CONF/../../uranium.conf" "$CONF/../../rsccoleslaw.conf" "$DIR/openrsc/server/"
cp -r "$CACHE" "$DIR/openrsc/Client_Base/Cache"
printf '@echo off\r\ncd /d "%%~dp0"\r\njava -jar session-proxy.jar %%*\r\npause\r\n' > "$DIR/session-proxy.cmd"

rm -f "$OUT"
(cd "$STAGE" && zip -qr "$OUT" session-proxy)
echo "$OUT"
