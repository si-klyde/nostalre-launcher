#!/usr/bin/env bash
# Creates a fresh SQLite database from rAthena's MySQL schema files.
# Usage: scripts/make-db.sh [--test-account] [--force]   (--test-account adds tester/tester, GM level 99, and
#   prober/prober, the same for a second session (a network probe) so it never logs the game out;
#   an existing database is kept unless --force: it holds the characters)
# RA_DB=<file> writes elsewhere than out/db/ragnarok.db (the host servers' database; PACKETVER=<date>: out/db-<date>).
set -euo pipefail
source "$(dirname "$0")/common.sh"

test_account=false force=false
for arg in "$@"; do
	case "$arg" in
		--test-account) test_account=true ;;
		--force) force=true ;;
		*) echo "unknown option: $arg" >&2; exit 1 ;;
	esac
done

db="${RA_DB:-$OUT/db/ragnarok.db}"
sql="$ROOT/server/sql-files"
if [ -f "$db" ] && ! $force; then
	echo "$db exists, keeping it (--force replaces it, its characters included)" >&2
	exit 1
fi
mkdir -p "$(dirname "$db")"
rm -f "$db" "$db-wal" "$db-shm"

python3 -I "$ROOT/server/tools/mysql2sqlite.py" \
	"$sql/main.sql" "$sql/logs.sql" "$sql/web.sql" "$sql/roulette_default_data.sql" \
	| sqlite3 -bail "$db" >/dev/null

if $test_account; then
	sqlite3 "$db" "INSERT INTO login (userid, user_pass, sex, email, group_id) VALUES ('tester', 'tester', 'M', 'a@a.com', 99);"
	sqlite3 "$db" "INSERT INTO login (userid, user_pass, sex, email, group_id) VALUES ('prober', 'prober', 'M', 'a@a.com', 99);"
fi
echo "Created: $db"
