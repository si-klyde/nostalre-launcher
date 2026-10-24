#!/usr/bin/env bash
# Dumps the packet table of our rAthena at one packet version: every id the servers send (length from the packet's
# struct, or -1 for variable length) and every id they parse (length and field offsets from clif_packetdb.hpp and
# clif_shuffle.hpp). It compiles a small program against rAthena's headers with the map server's own flags, so the
# server must have been configured once for that version (scripts/build-server.sh host).
# Output: client/lib/network/data/rathena-<packetver>.tsv, which the client frames packets with and its
# tests check the packet crate against (client/docs/packet-status-20250716.md).
# Usage: scripts/packetdb-dump.sh [output.tsv]
set -euo pipefail
source "$(dirname "$0")/common.sh"

build="$OUT/build/server-host"
[ -f "$build/build.ninja" ] || { echo "no $build, run scripts/build-server.sh host first" >&2; exit 1; }
out="${1:-$ROOT/client/lib/network/data/rathena-$PACKETVER.tsv}"
work="$OUT/packetdb-dump"
mkdir -p "$work" "$(dirname "$out")"

python3 -I "$ROOT/scripts/packetdb_dump.py" "$ROOT/server/src" "$build/build.ninja" "$work" "$out" "$PACKETVER"
