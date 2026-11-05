#!/usr/bin/env bash
# Checks the Population Engine port (rathena-nostalre feat/population-engine) on host servers with their own
# database (out/pop/db) and logs (out/pop/logs), driven by the network crate's session probe on the test account:
#   off  engine switched off (the default): nothing loads, @populate refused, NPC count = before + arena.txt
#   on   population_engine_enable: 1: the 9 yml load, @populate 20 prontera makes 20 shells, nothing of theirs
#        reaches SQLite, no SQL errors, whispering "party" to a shell changes no party, the player's own save
#        survives a restart
#   s2   phone tuning: shells only where the player is, released after the grace window (20 s here), density
#        scales the crowd, buying from a shell vendor logs no shell row, wander skips maps nobody is on
#   s3   the app's world settings (ServerConf.battleFile keys) reach the server: shell cap in the boot line,
#        mob_count_rate in the monsters the boot loads
#   levels shells suit their map: on a low, a mid and a high map and a town, each shell's level falls in the range
#        its map and class allow, stats are ones that level could afford, gear is worn; off keeps the 99s
# Needs: map/char/login servers built from the engine branch (POP_BIN, default out/pop/bin:
#   RATHENA_DIR=<rathena-nostalre> with build-server.sh, or any host build) and the client checkout (the probe).
# Usage: scripts/test-population.sh [off|on|s2|s3|levels|all]   (RATHENA_DIR=<server checkout>, POP_BIN=<bin dir>)
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
		probe "$tmp/party.txt" --social "whisper:$shell:party" --social wait:5 --seconds 2
		# the shell took the whisper (ack 0) and answered with the refusal (population_engine.cpp, nostalRE)
		check "AC8 '$shell' took the whisper and refused" \
			"$(yes_no 'grep -aq "social: whisper:" "$tmp/party.txt" && grep -aq "hunt alone" "$tmp/party.txt"')"
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

# --- levels: shell levels suit the map -------------------------------------------
# levels_check <probe output> <map> <monster level, 0 = town>: every shell of the map in `@populate levels`
levels_check() {
	python3 -c "$levels_py" "$@" "$RATHENA/db/re/statpoint.yml" >&2
}
levels_py=$(cat <<'PY'
import re, sys
path, name, map_level, statpoints = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
FIRST = {"Swordman", "Swordsman", "Mage", "Magician", "Archer", "Acolyte", "Merchant", "Thief", "Taekwon"}
NO_NEXT_CLASS = {"Gunslinger", "Ninja"}
FIST = {"Taekwon", "Star Gladiator", "Soul Linker"} # kick and punch: their gear sets hold no weapon
def window(job): # population_roll_map_levels' range for this map and class
	floor, cap = (1, 15) if job == "Novice" else (10, 70) if job in FIRST else (10, 99) if job in NO_NEXT_CLASS \
		else (60, 99) if job == "Assassin Cross" else (45, 99)
	spread = max(3, map_level // 10) if map_level else 12
	lo, hi = (map_level - spread, map_level + spread) if map_level else (30, 85)
	hi = min(max(hi, floor + spread), cap)
	return max(min(lo, hi - spread), floor), hi
table = dict(re.findall(r'Level: (\d+)\s+Points: (\d+)', open(statpoints).read()))
def budget(level):
	return int(table[str(level)])
def cost(stat):
	return sum((i - 1) // 10 + 2 for i in range(1, stat))
text = open(path, errors="replace").read()
# this map's part of the probe's output: from its @warp up to the next one
part = text.split("say:@warp " + name)[-1].split("say:@warp ")[0]
rows = re.findall(r'shell job ([^"]+?) lv (\d+)/(\d+) stats (\d+)/(\d+)/(\d+)/(\d+)/(\d+)/(\d+) weapon (\d+) armor (\d+) shoes (\d+)', part)
bad, jobs, levels, ratios, no_weapon, no_armor = [], {}, [], [], 0, 0
for job, base, jl, *rest in rows:
	base, jl = int(base), int(jl)
	stats = [int(x) for x in rest[:6]]
	weapon, armor = int(rest[6]), int(rest[7])
	lo, hi = window(job)
	levels.append(base)
	jobs[job] = jobs.get(job, 0) + 1
	ratios.append(sum(cost(s) for s in stats) / budget(base))
	if not lo <= base <= hi:
		bad.append(f"{job} {base} outside {lo}-{hi}")
	if not 1 <= jl <= 70:
		bad.append(f"{job} job level {jl}")
	if max(stats) > 99:
		bad.append(f"{job} stats {stats}")
	if ratios[-1] > 1:
		bad.append(f"{job} {base} stats {stats} cost more than the level has")
	if armor == 0:
		no_armor += 1
	if weapon == 0 and job not in FIST:
		no_weapon += 1
		bad.append(f"{job} {base} has no weapon")
if not rows:
	bad.append("no shells listed")
print(f"      {name}: {len(rows)} shells, levels {min(levels, default=0)}-{max(levels, default=0)}, "
	f"stat points vs budget {min(ratios, default=0):.2f}-{max(ratios, default=0):.2f}, "
	f"no weapon {no_weapon}, no armor {no_armor}, jobs {sorted(jobs)}")
for line in sorted(set(bad))[:15]:
	print("      ! " + line)
sys.exit(1 if bad else 0)
PY
)
if [ "$what" = levels ] || [ "$what" = all ]; then
	echo "== map levels"
	[ -n "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ] || {
		start_servers 0; probe "$tmp/create.txt" --create "$char" --seconds 1; }
	grace="population_engine_demand_grace_ms: 20000"
	full_density="population_engine_density_pct: 100"
	# low (Knights only, then first classes too), mid, high, a town, and a field with no monsters at all
	visit=()
	for m in prt_fild08 gef_fild00 gef_fild10 tur_dun04 prontera pay_fild05; do
		visit+=(--social "say:@warp $m" --social wait:14 --social "say:@populate levels" --social wait:1)
	done

	start_servers 1 "$grace" "$full_density"
	probe "$tmp/levels.txt" "${visit[@]}" --seconds 1
	map_level() { grep -aoP "map '$1' has monster level \K\d+" "$logs/map.log" | head -1 || true; }
	low=$(map_level prt_fild08); low2=$(map_level gef_fild00); mid=$(map_level gef_fild10); high=$(map_level tur_dun04)
	check "AC1 monster levels from moblist: prt_fild08 ${low:-?} < 10, gef_fild10 ${mid:-?} in 30-60, tur_dun04 ${high:-?} >= 85" \
		"$(yes_no '[ "${low:-99}" -lt 10 ] && [ "${mid:-0}" -ge 30 ] && [ "${mid:-0}" -le 60 ] && [ "${high:-0}" -ge 85 ]')"
	check "AC1 prt_fild08 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" prt_fild08 "${low:-0}"')"
	check "AC1 gef_fild00 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" gef_fild00 "${low2:-0}"')"
	check "AC1 gef_fild10 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" gef_fild10 "${mid:-0}"')"
	check "AC1 tur_dun04 shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" tur_dun04 "${high:-0}"')"
	check "AC2 prontera (town) shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" prontera 0')"
	check "AC2 pay_fild05 (no monsters) shells fit" "$(yes_no 'levels_check "$tmp/levels.txt" pay_fild05 0')"

	start_servers 1 "$grace" "$full_density" "population_engine_map_levels: 0"
	probe "$tmp/levels-off.txt" --social "say:@warp gef_fild10" --social wait:14 --social "say:@populate levels" --seconds 1
	top=$(grep -aoP 'shell job .* lv \K99(?=/70 )' "$tmp/levels-off.txt" | wc -l || true)
	check "AC4 switch off: the old 99/70 shells on gef_fild10 ($top), no map level computed" \
		"$(yes_no '[ "$top" -gt 0 ] && ! grep -aq "has monster level" "$logs/map.log"')"
fi

echo "logs: $logs  probe output: kept only on failure"
[ "$fails" = 0 ] || { cp -r "$tmp" "$work/probe-$(date +%Y%m%d-%H%M%S)"; exit 1; }
