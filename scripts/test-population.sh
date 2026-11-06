#!/usr/bin/env bash
# Checks the Population Engine port (rathena-nostalre feat/population-engine) on host servers with their own
# database (out/pop/db) and logs (out/pop/logs), driven by the network crate's session probe on the test account:
#   off  engine switched off (the default): nothing loads, @populate refused, NPC count = before + arena.txt
#   on   population_engine_enable: 1: the 9 yml load, @populate 20 prontera makes 20 shells, nothing of theirs
#        reaches SQLite, no SQL errors, a whisper that only contains "pt" changes no party, the player's own save
#        survives a restart
#   s2   phone tuning: shells only where the player is, released after the grace window (20 s here), density
#        scales the crowd, buying from a shell vendor logs no shell row, wander skips maps nobody is on
#   s3   the app's world settings (ServerConf.battleFile keys) reach the server: shell cap in the boot line,
#        mob_count_rate in the monsters the boot loads
# Needs: map/char/login servers built from the engine branch (POP_BIN, default out/pop/bin:
#   RATHENA_DIR=<rathena-nostalre> with build-server.sh, or any host build) and the client checkout (the probe).
#   party shells as party companions: a whisper or an invite joins, the party list shows it, it fights for the player
#        and follows across maps, it leaves on "bye", on logout and when the engine stops, and only the player's own
#        rows reach SQLite
# Usage: scripts/test-population.sh [off|on|s2|s3|party|all]   (RATHENA_DIR=<server checkout>, POP_BIN=<bin dir>)
set -euo pipefail
source "$(dirname "$0")/common.sh"

what="${1:-all}"
bin="${POP_BIN:-$OUT/pop/bin}"
work="$OUT/pop"
db="$work/db/ragnarok.db"
logs="$work/logs"
conf="$RATHENA/conf/import/battle_conf.txt"
char=PopTester
npcs_before=24240 # map.log "Done loading '24240' NPCs" on nostalre 59dc4d8, before the port
mkdir -p "$logs"

sql() { sqlite3 -batch -noheader "$db" "$1"; }
fails=0
check() { # check <name> <ok?>
	if [ "$2" = 0 ]; then echo "ok    $1"; else echo "FAIL  $1"; fails=$((fails + 1)); fi
}
yes_no() { if eval "$1"; then echo 0; else echo 1; fi; } # yes_no '<shell condition>' → 0 when it holds

for exe in login-server char-server map-server; do
	[ -x "$bin/$exe" ] || { echo "no $bin/$exe (POP_BIN)" >&2; exit 1; }
done
server_imports "$RATHENA"

# the import conf the app writes at each start: kept, then restored
saved="$(mktemp)"
cp "$conf" "$saved"
pids=""
stop_servers() {
	[ -z "$pids" ] || { kill $pids 2>/dev/null || true; wait $pids 2>/dev/null || true; }
	pids=""
}
trap 'stop_servers; cp "$saved" "$conf"; rm -f "$saved"' EXIT

start_servers() { # start_servers <enable 0|1> [extra battle conf line...]
	stop_servers
	{ cat "$saved"; echo "population_engine_enable: $1"; shift; printf '%s\n' "$@"; } > "$conf"
	rm -f "$logs"/{login,char,map}.log
	(
		cd "$RATHENA"
		export RA_SQLITE_DIR="$(dirname "$db")" PATH="$bin:$PATH"
		exec login-server > "$logs/login.log" 2>&1
	) & pids="$pids $!"
	sleep 1
	( cd "$RATHENA"; export RA_SQLITE_DIR="$(dirname "$db")" PATH="$bin:$PATH"; exec char-server > "$logs/char.log" 2>&1 ) &
	pids="$pids $!"
	sleep 1
	( cd "$RATHENA"; export RA_SQLITE_DIR="$(dirname "$db")" PATH="$bin:$PATH"; exec map-server > "$logs/map.log" 2>&1 ) &
	pids="$pids $!"
	timeout 240 bash -c "until grep -aq 'Map-server connected to char-server' '$logs/map.log' 2>/dev/null; do sleep 1; done" \
		|| { echo "map-server did not come up (see $logs/map.log)" >&2; exit 1; }
}

# probe <out file> <probe args...>: a session on tester as $char; packets with Playerchat in their name printed
probe() {
	local out=$1
	shift
	( cd "$CLIENT" && cargo run -q -p nostalre-network --example session_probe -- \
		--user tester --pass tester --dump Playerchat "$@" ) > "$out" 2>&1 || true
}
# the GM command made it: rAthena logs only the commands that succeed
logged() { [ "$(sql "SELECT COUNT(*) FROM atcommandlog WHERE command LIKE '$1%'")" -gt 0 ]; }
pop_lines() { grep -ac 'Population engine' "$logs/map.log" || true; }
npc_count() { grep -aoP "Done loading '\K\d+(?=' NPCs)" "$logs/map.log" | tail -1 || true; }
arena_npcs() { grep -acP '^\w+,\d+,\d+,\d+\t(script|duplicate\([^)]*\)|shop|warp)\t' "$RATHENA/npc/custom/population/arena.txt" 2>/dev/null || echo 0; }
tmp="$(mktemp -d)"
trap 'stop_servers; cp "$saved" "$conf"; rm -f "$saved"; rm -rf "$tmp"' EXIT
[ -f "$conf" ] || : > "$conf"

RA_DB="$db" "$ROOT/scripts/make-db.sh" --force --test-account > /dev/null

# --- off ---------------------------------------------------------------------
if [ "$what" = off ] || [ "$what" = all ]; then
	echo "== engine off"
	start_servers 0
	probe "$tmp/create.txt" --create "$char" --seconds 1
	probe "$tmp/off.txt" --chat "@populate 5" --seconds 5
	check "AC2 no engine lines in map log ($(pop_lines))" "$(yes_no '[ "$(pop_lines)" = 0 ]')"
	check "AC2 @populate refused" "$(yes_no '! logged @populate')"
	arena=$(arena_npcs)
	n=$(npc_count)
	check "AC3 NPCs $n = $npcs_before + $arena (arena.txt)" \
		"$(yes_no '[ "${n:-0}" = $((npcs_before + arena)) ] && [ "$arena" -gt 0 ]')"
	# test-custom-npcs.sh step 2 on this run's log: the custom NPCs and arena.txt load cleanly
	errors=$(grep -aiE 'error|warning' "$logs/map.log" | grep -aE 'npc/custom/(jobmaster|healer|warper|resetnpc|stylist|population/)' || true)
	check "AC3 custom NPCs + arena.txt load without errors" "$(yes_no '[ -z "$errors" ]')"
	[ -z "$errors" ] || echo "$errors" | head -5
fi

# --- on ----------------------------------------------------------------------
if [ "$what" = on ] || [ "$what" = all ]; then
	echo "== engine on"
	start_servers 1
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || probe "$tmp/create.txt" --create "$char" --seconds 1
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	[ -n "$id" ] || { echo "FAIL  no character $char"; exit 1; }
	parties=$(sql "SELECT COUNT(*) FROM party")
	sql "UPDATE \`char\` SET zeny = 1000, party_id = 0 WHERE char_id = $id"

	load_errors=$(grep -aE 'Population engine: .*(missing or invalid|failed|not found)' "$logs/map.log" | grep -av 'itemdb' || true)
	missing_items=$(grep -ac 'Population engine: .* not found in itemdb' "$logs/map.log" || true)
	# the port's boot line (population_engine.cpp, nostalRE): proof the engine ran its load at all
	check "AC4 engine boot line 'Population engine: on'" "$(yes_no 'grep -aq "Population engine: on" "$logs/map.log"')"
	check "AC4 9 yml load without errors (missing items: $missing_items)" "$(yes_no '[ -z "$load_errors" ]')"
	[ -z "$load_errors" ] || echo "$load_errors" | head -5

	# @populate <n> needs the autosummon stopped first (it refuses while the engine runs)
	probe "$tmp/on.txt" --warp prontera --chat "@populate stop" --chat "@populate 20 prontera" --chat "@zeny 777" \
		--social wait:8 --social "say:@populate stats" --social "say:@who" --social wait:2 --seconds 3
	active=$(grep -aoP 'Active / created / errors : \K\d+' "$tmp/on.txt" | tail -1 || true)
	check "AC5 @populate stats: ${active:-?} active, want 20" "$(yes_no '[ "${active:-0}" = 20 ]')"

	# a shell's name from @who: any listed name but ours
	shell=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/on.txt" | grep -vxF "$char" | head -1 || true)
	if [ -n "$shell" ]; then
		# "empty" holds the keyword "pt" but is not a request (the "party" stage below covers real ones)
		probe "$tmp/party.txt" --social "whisper:$shell:empty" --social wait:5 --seconds 2
		check "AC8 '$shell' took the whisper and did not join" \
			"$(yes_no 'grep -aq "social: whisper:" "$tmp/party.txt" && ! grep -aq "lead the way" "$tmp/party.txt"')"
		check "AC8 no party row (parties $parties -> $(sql "SELECT COUNT(*) FROM party"))" \
			"$(yes_no '[ "$(sql "SELECT COUNT(*) FROM party")" = "$parties" ] && [ "$(sql "SELECT party_id FROM \`char\` WHERE char_id = $id")" = 0 ]')"
	else
		check "AC8 a shell listed by @who to whisper" 1
	fi

	echo "   5 min with the shells on ..."
	probe "$tmp/stay.txt" --warp prontera --seconds 300
	# sql_sqlite.cpp ShowSQL: "DB error - ..." (connect lines carry the [SQL] tag too)
	check "AC7 no SQL errors in map log" "$(yes_no '! grep -aqE "\[SQL\]: (DB error|Failed)" "$logs/map.log"')"

	start_servers 1
	fake=0
	for t in "\`char\`:char_id" "login:account_id" "inventory:char_id" "vendings:char_id" "party:leader_char"; do
		n=$(sql "SELECT COUNT(*) FROM ${t%%:*} WHERE ${t#*:} >= 95000000")
		[ "$n" = 0 ] || { echo "   ${t%%:*}: $n rows of shells"; fake=1; }
	done
	check "AC6 no shell rows in char, login, inventory, vending, party" $fake

	zeny=$(sql "SELECT zeny FROM \`char\` WHERE char_id = $id")
	map=$(sql "SELECT last_map FROM \`char\` WHERE char_id = $id")
	check "AC9 player save after restart: zeny $zeny (want 1777), map $map (want prontera)" \
		"$(yes_no '[ "$zeny" = 1777 ] && [ "$map" = prontera ]')"
fi

# --- s2: phone tuning --------------------------------------------------------
# found <file> <map> <n>: the player count of the n-th "@whomap <map>" reply (shells count as players)
found() {
	grep -aoP "message: \"\K(No player|\d+ players?) found in map '$2'" "$1" | sed -n "${3}p" \
		| sed -E 's/^No player.*/0/; s/^([0-9]+).*/\1/'
}
if [ "$what" = s2 ] || [ "$what" = all ]; then
	echo "== s2 phone tuning"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	grace="population_engine_demand_grace_ms: 20000"
	full_density="population_engine_density_pct: 100" # the counts below are relative to 100 (nostalRE ships 200)
	at_prontera() { sql "UPDATE \`char\` SET last_map = 'prontera', last_x = 156, last_y = 191, zeny = 1000000 WHERE char_id = $id"; }

	at_prontera
	start_servers 1 "$grace" "$full_density"
	sleep 30 # nobody on: demand spawn makes no shell
	probe "$tmp/s2.txt" --social wait:10 --social "say:@who" --social "say:@whomap prontera" \
		--social "say:@warp prt_fild08" --social wait:3 --social "say:@whomap prontera" \
		--social "say:@populate timingreset" --social wait:4 --social "say:@populate timing" \
		--social "say:@whomap prt_fild08" --social wait:22 \
		--social "say:@whomap prontera" --social "say:@whomap prt_fild08" --seconds 1
	total=$(grep -aoP 'message: "\K\d+(?= players? found\.)' "$tmp/s2.txt" | head -1 || true)
	p1=$(found "$tmp/s2.txt" prontera 1); p2=$(found "$tmp/s2.txt" prontera 2); p3=$(found "$tmp/s2.txt" prontera 3)
	f1=$(found "$tmp/s2.txt" prt_fild08 1); f2=$(found "$tmp/s2.txt" prt_fild08 2)
	examined=$(grep -aoP 'Avg shells examined/walked *: \K[0-9.]+' "$tmp/s2.txt" | head -1 || true)
	# @who comes first, @whomap a moment later: a shell may spawn, die or be released in between (+-2)
	check "AC1 all shells on the player's map: @who ${total:-?} ~ prontera ${p1:-?} (+-2), more than the player" \
		"$(yes_no '[ -n "$total" ] && [ -n "$p1" ] && [ "$total" -le $((p1 + 2)) ] && [ "$total" -gt 1 ]')"
	check "AC3 prontera keeps its shells 3 s after leaving (grace): ${p2:-?}" "$(yes_no '[ "${p2:-0}" -gt 0 ]')"
	check "AC6 wander examines only the player's map: avg ${examined:-?} <= prt_fild08 shells $(( ${f1:-1} - 1 ))" \
		"$(yes_no '[ -n "$examined" ] && awk "BEGIN { exit !($examined <= ${f1:-1} - 1) }"')"
	check "AC2 after the grace window: prontera ${p3:-?} = 0, prt_fild08 ${f2:-?} > 1" \
		"$(yes_no '[ "${p3:-x}" = 0 ] && [ "${f2:-0}" -gt 1 ]')"

	at_prontera
	start_servers 1 "$grace" "population_engine_density_pct: 50"
	probe "$tmp/s2-half.txt" --social wait:10 --social "say:@whomap prontera" --seconds 1
	h=$(found "$tmp/s2-half.txt" prontera 1)
	# shells only: the player is one of the counted
	full=$(( ${p1:-1} - 1 )) half=$(( ${h:-1} - 1 ))
	check "AC4 density 50: prontera $half shells vs $full at 100 (want 40-60%, +-1)" \
		"$(yes_no '[ "$full" -gt 0 ] && [ $((half * 100)) -ge $(( (full * 40) - 100 )) ] && [ $((half * 100)) -le $(( (full * 60) + 100 )) ]')"

	# a shell vendor on prt_mk (db/population_vendors.yml): its AID from the shop sign, then one item bought
	sql "UPDATE \`char\` SET last_map = 'prt_mk', last_x = 100, last_y = 100, zeny = 1000000 WHERE char_id = $id"
	# default grace: the vendor must outlive the gap between the two sessions
	start_servers 1
	probe "$tmp/s2-board.txt" --dump StoreEntry --social wait:12 --seconds 1
	vendor=$(grep -aoP 'VendingBoardShown \{ aid: \K\d+' "$tmp/s2-board.txt" | head -1 || true)
	if [ -n "$vendor" ]; then
		before=$(sql "SELECT COUNT(*) FROM picklog")
		# @refresh re-sends the units in sight: the shop sign sent at login comes before the steps listen
		probe "$tmp/s2-buy.txt" --social "say:@refresh" --social "vend-buy:$vendor" --seconds 1
		bought=$(grep -ac 'social: vend-buy.*bought item' "$tmp/s2-buy.txt" || true)
		shell_rows=$(sql "SELECT COUNT(*) FROM picklog WHERE char_id >= 95000000")
		check "AC5 bought from shell vendor $vendor ($bought), picklog shell rows $shell_rows (rows $before -> $(sql "SELECT COUNT(*) FROM picklog"))" \
			"$(yes_no '[ "$bought" -gt 0 ] && [ "$shell_rows" = 0 ]')"
	else
		check "AC5 a shell vendor's shop sign on prt_mk" 1
	fi
	sql "UPDATE \`char\` SET last_map = 'prontera', last_x = 156, last_y = 191 WHERE char_id = $id"
fi

if [ "$what" = s3 ] || [ "$what" = all ]; then
	echo "== s3 app world settings"
	# the lines ServerConf.battleFile writes (ServerConfTest checks the app side)
	start_servers 1 "population_engine_max_count: 50" "population_engine_density_pct: 50"
	check "AC5 max 50 in the boot line" "$(yes_no 'grep -aq "Population engine: on (max 50 shells)" "$logs/map.log"')"

	# the boot's monster count: npc.cpp scales each spawn line of more than one by mob_count_rate
	cached() { grep -aoP "'\K\d+(?=' Mobs Cached)" "$logs/map.log" | tail -1 || true; }
	start_servers 0 "mob_count_rate: 100"; m1=$(cached)
	start_servers 0 "mob_count_rate: 200"; m2=$(cached)
	check "AC5 mob_count_rate 200: ${m2:-?} monsters vs ${m1:-?} at 100 (want 1.9-2x)" \
		"$(yes_no '[ -n "$m1" ] && [ -n "$m2" ] && [ $((m2 * 100)) -ge $((m1 * 190)) ] && [ $((m2 * 100)) -le $((m1 * 200)) ]')"
fi

# --- party: shells as companions ------------------------------------------------
if [ "$what" = party ] || [ "$what" = all ]; then
	echo "== party companions"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
	sql "DELETE FROM party WHERE leader_char = $id"
	sql "UPDATE \`char\` SET party_id = 0, last_map = 'prt_fild08', last_x = 170, last_y = 375, base_exp = 0, job_exp = 0 WHERE char_id = $id"
	level=$(sql "SELECT base_level FROM \`char\` WHERE char_id = $id")
	start_servers 1

	# two field shells from @who; they outlive the gap between sessions (the demand grace, 3 min)
	probe "$tmp/p-who.txt" --social wait:10 --social "say:@who" --seconds 1
	names=$(grep -aoP 'message: "Name: \K[^|()"]+?(?= *(\(|\||"))' "$tmp/p-who.txt" | grep -vxF "$char" || true)
	shell=$(echo "$names" | sed -n 1p)
	shell2=$(echo "$names" | sed -n 2p)
	check "AC1 two shells to party with: '${shell:-?}', '${shell2:-?}'" "$(yes_no '[ -n "$shell" ] && [ -n "$shell2" ]')"

	if [ -n "$shell" ] && [ -n "$shell2" ]; then
		# whisper with no party: it makes one and joins. Then shared EXP and items, Porings next to a player who never
		# attacks, a map change, "bye", an invite by name, and the engine stopping under a companion.
		probe "$tmp/p-main.txt" --social "whisper:$shell:party" --social wait:3 \
			--social "party-option:1,1,1" --social wait:1 --social "say:@monster poring 12" --social wait:45 \
			--social "say:@warp prt_fild07" --social wait:4 --social "say:@whomap prt_fild07" \
			--social "whisper:$shell:bye" --social wait:2 \
			--social "say:@warp prt_fild08" --social wait:4 \
			--social "invite:$shell2" --social wait:3 \
			--social "say:@populate stop" --social wait:3 --seconds 1

		check "AC2 whisper answered: joined" "$(yes_no 'grep -aq "lead the way" "$tmp/p-main.txt"')"
		check "AC2 party list shows '$shell' next to the player" \
			"$(yes_no 'grep -aF "PartyMemberList" "$tmp/p-main.txt" | grep -aF "$shell" | grep -aqF "$char"')"
		check "AC3 it moved with the player: party list has it on prt_fild07" \
			"$(yes_no 'grep -aF "PartyMemberList" "$tmp/p-main.txt" | grep -aF "$shell" | grep -aq "prt_fild07"')"
		check "AC4 'bye' takes it out of the list" "$(yes_no 'grep -aq "PartyMemberRemoved" "$tmp/p-main.txt"')"
		check "AC5 invite by name: accepted" "$(yes_no 'grep -aF "PartyInviteResult" "$tmp/p-main.txt" | grep -aF "$shell2" | grep -aq "answer: 2"')"
		removed=$(grep -ac "PartyMemberRemoved" "$tmp/p-main.txt" || true)
		check "AC6 the engine stopping takes the last one out too ($removed removals, want 2)" "$(yes_no '[ "$removed" -ge 2 ]')"
		check "AC6 map server still up after the engine stopped" "$(yes_no 'kill -0 $(echo $pids | cut -d" " -f3) 2>/dev/null')"

		# the player only stood there: what they gained, the companion's kills gave them
		probe "$tmp/p-after.txt" --seconds 2
		now_exp=$(sql "SELECT base_exp FROM \`char\` WHERE char_id = $id")
		now_level=$(sql "SELECT base_level FROM \`char\` WHERE char_id = $id")
		check "AC7 a player who never attacked gained from its kills: exp $now_exp, level $level -> $now_level" \
			"$(yes_no '[ "${now_exp:-0}" -gt 0 ] || [ "${now_level:-0}" -gt "$level" ]')"
	fi

	party_errors=$(grep -aE '\[Error\].*[Pp]arty|party_[a-z_]+:' "$logs/map.log" "$logs/char.log" || true)
	check "AC8 no party errors in the server logs" "$(yes_no '[ -z "$party_errors" ]')"
	[ -z "$party_errors" ] || echo "$party_errors" | head -5
	check "AC8 no SQL errors in the logs" "$(yes_no '! grep -aqE "\[SQL\]: (DB error|Failed)" "$logs/map.log" "$logs/char.log"')"

	# the real test: what the save holds, and what a restart gives back
	start_servers 1
	pid=$(sql "SELECT party_id FROM \`char\` WHERE char_id = $id")
	check "AC9 the player's party survives: party_id ${pid:-?}, leader is the player" \
		"$(yes_no '[ "${pid:-0}" -gt 0 ] && [ "$(sql "SELECT leader_char FROM party WHERE party_id = $pid")" = "$id" ]')"
	members=$(sql "SELECT COUNT(*) FROM \`char\` WHERE party_id = ${pid:-0}")
	check "AC9 $members party member row (the player alone)" "$(yes_no '[ "$members" = 1 ]')"
	fake=0
	for t in "\`char\`:char_id" "party:leader_char" "inventory:char_id" "cart_inventory:char_id" "picklog:char_id" "zenylog:char_id"; do
		n=$(sql "SELECT COUNT(*) FROM ${t%%:*} WHERE ${t#*:} >= 95000000")
		[ "$n" = 0 ] || { echo "   ${t%%:*}: $n rows of shells"; fake=1; }
	done
	check "AC9 no shell rows in char, party, inventory, cart, picklog, zenylog" $fake
	check "AC9 no fake party id saved" "$(yes_no '[ "$(sql "SELECT COUNT(*) FROM \`char\` WHERE party_id >= 1879048192")" = 0 ]')"
	# @who names each character's party; the party list itself comes at login, before the probe prints events
	probe "$tmp/p-restart.txt" --social wait:6 --social "say:@who" --social wait:1 --seconds 1
	in_party=$(grep -aoP "message: \"Name: \K[^|(]+(?=.*Party: '$char Party')" "$tmp/p-restart.txt" | sort -u | tr '\n' ',' || true)
	check "AC9 after a restart the party holds the player only: ${in_party:-nobody}" "$(yes_no '[ "$in_party" = "$char," ]')"
fi

echo "logs: $logs  probe output: kept only on failure"
[ "$fails" = 0 ] || { cp -r "$tmp" "$work/probe-$(date +%Y%m%d-%H%M%S)"; exit 1; }
