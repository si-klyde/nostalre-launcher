#!/usr/bin/env bash
# The client tables generated from rAthena's data still match it: regenerate each into a temporary file and compare
# with the one in the tree. A difference means rAthena changed (rerun the script) or someone edited the output.
#
# Usage: scripts/check-generated.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RATHENA="$ROOT/server"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

stale=0
# FILE is under client/, or under this checkout with BASE "."
check() {
    local script=$1 file=$2 base=${3:-client} out
    out="$TMP/$(basename "$file")"
    python3 "$ROOT/scripts/$script" "$RATHENA" "$out" >/dev/null || { echo "failed: scripts/$script" >&2; stale=1; return; }
    if ! cmp -s "$out" "$ROOT/$base/$file"; then
        echo "stale: $ROOT/$base/$file differs from scripts/$script's output; rerun it, don't edit the file" >&2
        stale=1
    fi
}
check skill-enum.py rust-ro/models/src/enums/skill_enums.rs
check effect-id.py rust-ro/models/src/enums/effect_id.rs
check item-tables.py lib/game/src/item_tables.rs
check job-tables.py lib/game/src/job_tables.rs
check skill-tree.py lib/game/src/skill_tree_tables.rs
check job-names.py android/app/src/main/java/io/github/siklyde/nostalre/JobNames.kt .
exit $stale
