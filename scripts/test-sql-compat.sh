#!/usr/bin/env bash
# Before/after check for the SQLite backend: runs sql-compat-test against
#   before: original MySQL backend (sql.cpp) on MariaDB, built in a container
#   after:  SQLite backend (sql_sqlite.cpp) on a fresh out/db database
# and diffs the transcripts. Needs Docker.
# Usage: scripts/test-sql-compat.sh
set -euo pipefail
source "$(dirname "$0")/common.sh"

export TZ=Asia/Manila
work="$OUT/compat"
db_name=sql-compat-mariadb
builder=ra-mysql-builder
mkdir -p "$work"

# --- before: MySQL backend ---------------------------------------------------
if ! docker image inspect "$builder" >/dev/null 2>&1; then
	docker build -t "$builder" - <<'EOF'
FROM debian:trixie
RUN apt-get update && apt-get install -y --no-install-recommends \
	g++ cmake ninja-build make libmariadb-dev libmariadb-dev-compat zlib1g-dev tzdata ca-certificates \
	&& rm -rf /var/lib/apt/lists/*
EOF
fi

in_builder() {
	docker run --rm --network host -e TZ -u "$(id -u):$(id -g)" \
		-v "$ROOT:$ROOT" -w "$ROOT/server" "$builder" bash -c "$1"
}

build="$OUT/build/server-mysql"
in_builder "cmake -S . -B '$build' -G Ninja -DCMAKE_BUILD_TYPE=Release -DENABLE_WEB_SERVER=OFF \
	-DPACKETVER=$PACKETVER -DCMAKE_CXX_FLAGS=-DPRERE \
	-DCMAKE_RUNTIME_OUTPUT_DIRECTORY='$OUT/mysql/bin' \
	-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY='$build/lib' -DCMAKE_LIBRARY_OUTPUT_DIRECTORY='$build/lib' >/dev/null \
	&& ninja -C '$build' sql-compat-test >/dev/null"

docker rm -f "$db_name" >/dev/null 2>&1 || true
docker run -d --name "$db_name" -e TZ -p 127.0.0.1:13306:3306 \
	-e MARIADB_ROOT_PASSWORD=root -e MARIADB_DATABASE=ragnarok \
	-e MARIADB_USER=ragnarok -e MARIADB_PASSWORD=ragnarok mariadb:11.8 >/dev/null
trap 'docker rm -f "$db_name" >/dev/null' EXIT

until docker exec "$db_name" mariadb -uragnarok -pragnarok -e 'SELECT 1' ragnarok >/dev/null 2>&1; do sleep 1; done
for f in main logs web roulette_default_data; do
	docker exec -i "$db_name" mariadb -uragnarok -pragnarok ragnarok < "$ROOT/server/sql-files/$f.sql"
done

in_builder "PATH='$OUT/mysql/bin':\$PATH sql-compat-test 127.0.0.1 13306 ragnarok ragnarok ragnarok" > "$work/mysql.raw" 2>&1
grep '^| ' "$work/mysql.raw" > "$work/mysql.txt"

# every query in the sources, plus the runtime-built ones, compiled (not run) on both backends
{ python3 -I "$ROOT/scripts/extract_queries.py" "$ROOT/server" 2> "$work/corpus.skipped"
  python3 -I "$ROOT/scripts/dynamic_queries.py"; } \
	| python3 -I -c 'import json, sys
for line in sys.stdin:
	if line.startswith("{"):
		q = json.loads(line); line = q["where"] + "\t" + " ".join(q["sql"].split()) + "\n"
	sys.stdout.write(line)' > "$work/corpus.tsv"
in_builder "PATH='$OUT/mysql/bin':\$PATH sql-compat-test 127.0.0.1 13306 ragnarok ragnarok ragnarok --prepare '$work/corpus.tsv'" \
	| grep '^| ' > "$work/corpus.mysql"

# --- after: SQLite backend ---------------------------------------------------
"$ROOT/scripts/build-server.sh" host >/dev/null
ninja -C "$OUT/build/server-host" sql-compat-test >/dev/null
RA_DB="$work/db/ragnarok.db" "$ROOT/scripts/make-db.sh" --force >/dev/null
(cd "$ROOT/server" && RA_SQLITE_DIR="$work/db" PATH="$OUT/host/bin:$PATH" \
	sql-compat-test 127.0.0.1 3306 ragnarok ragnarok ragnarok) > "$work/sqlite.raw" 2>&1
grep '^| ' "$work/sqlite.raw" > "$work/sqlite.txt"
(cd "$ROOT/server" && RA_SQLITE_DIR="$work/db" PATH="$OUT/host/bin:$PATH" \
	sql-compat-test 127.0.0.1 0 x x ragnarok --prepare "$work/corpus.tsv") | grep '^| ' > "$work/corpus.sqlite"

# --- compare -----------------------------------------------------------------
status=0
echo "query corpus: $(grep -c '^| OK' "$work/corpus.mysql") of $(wc -l < "$work/corpus.tsv") compile on MySQL, $(grep -c '^| OK' "$work/corpus.sqlite") on SQLite ($(tail -1 "$work/corpus.skipped"))"
if ! diff "$work/corpus.mysql" "$work/corpus.sqlite" > "$work/corpus.diff"; then
	echo "corpus: DIFFERENCES ($work/corpus.diff)"
	status=1
fi

echo "transcript: before $(wc -l < "$work/mysql.txt") lines, after $(wc -l < "$work/sqlite.txt") lines"
if diff -u "$work/mysql.txt" "$work/sqlite.txt" > "$work/diff.txt"; then
	echo "transcript: IDENTICAL"
else
	echo "transcript: DIFFERENCES ($work/diff.txt)"
	status=1
fi
exit $status
