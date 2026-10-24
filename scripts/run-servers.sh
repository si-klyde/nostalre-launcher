#!/usr/bin/env bash
# Runs rAthena's login, char and map servers on this machine (SQLite, out/db), for nostalRE on the desktop
# (scripts/run-nostalre.sh starts them itself). Ctrl+C stops them.
# Needs: scripts/build-server.sh host, make-db.sh.
# Usage: scripts/run-servers.sh
# SECOND_MAP_SERVER=<map> runs a second map server (port 5122) that alone loads that map: a warp there is a
# map-server move (0x0ac7). The configuration files stay as they are; the two servers read generated ones.
set -euo pipefail
source "$(dirname "$0")/common.sh"

logs="$OUT/logs"
db="$OUT/db"
mkdir -p "$logs"
rm -f "$logs/map.log" # the ready wait below must not find the last run's line
[ -f "$db/ragnarok.db" ] || { echo "no database, run scripts/make-db.sh first" >&2; exit 1; }

trap 'kill $(jobs -p) 2>/dev/null; wait' EXIT INT TERM
server_imports "$RATHENA"

second_map="${SECOND_MAP_SERVER:-}"
first_conf=() second_conf=""
if [ -n "$second_map" ]; then
	confs="$OUT/second-map-server"
	mkdir -p "$confs"
	rm -f "$logs/map2.log"
	printf 'import: conf/map_athena.conf\ndelmap: %s\n' "$second_map" > "$confs/first.conf"
	printf 'import: conf/map_athena.conf\ndelmap: all\nmap: %s\nmap_port: 5122\n' "$second_map" > "$confs/second.conf"
	first_conf=(--map-config "$confs/first.conf")
	second_conf="$confs/second.conf"
fi

(
	# its own trap, on the servers' PIDs: killing the subshell alone leaves them running
	pids=""
	trap 'kill $pids 2>/dev/null; wait' EXIT INT TERM
	cd "$RATHENA"
	export RA_SQLITE_DIR="$db" PATH="$OUT/host/bin:$PATH"
	# started through PATH: rathena changes into the binary's directory when given a path
	login-server > "$logs/login.log" 2>&1 &
	pids="$pids $!"
	sleep 1; char-server > "$logs/char.log" 2>&1 &
	pids="$pids $!"
	sleep 1; map-server "${first_conf[@]}" > "$logs/map.log" 2>&1 &
	pids="$pids $!"
	if [ -n "$second_conf" ]; then
		map-server --map-config "$second_conf" > "$logs/map2.log" 2>&1 &
		pids="$pids $!"
	fi
	wait
) &

for log in map ${second_map:+map2}; do
	timeout 180 bash -c "until grep -aq 'Map-server connected to char-server' '$logs/$log.log' 2>/dev/null; do sleep 1; done"
done
echo "Ready: login server on 127.0.0.1:6900  (logs in $logs)"
wait
