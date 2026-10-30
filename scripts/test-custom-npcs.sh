#!/usr/bin/env bash
# Checks the custom NPCs the app's server turns on (npc/scripts_custom.conf): Job Master, Healer, Warper, Reset
# Girl and Stylist.
#   1. each is listed, stands in Prontera at least 2 cells from every other NPC the server loads
#   2. the map-server loads them without a script error or warning (out/logs/map.log of the last server start)
#   3. played on the desktop client (scripts/run-nostalre-script.sh), character JobTester on the test account:
#      a Novice at job level 9 is turned away by the Job Master, at job level 10 becomes a Swordman; the Healer
#      fills a character's HP
# Needs: scripts/build-server.sh host, make-db.sh --test-account, a desktop client build (run-nostalre.sh).
# Usage: scripts/test-custom-npcs.sh [static]   (static: steps 1 and 2 only)
set -euo pipefail
source "$(dirname "$0")/common.sh"

db="$OUT/db/ragnarok.db"
sql() { sqlite3 -batch -noheader "$db" "$1"; }
fails=0
check() { # check <name> <ok?>
	if [ "$2" = 0 ]; then echo "ok    $1"; else echo "FAIL  $1"; fails=$((fails + 1)); fi
}

# 1. listed and spaced out
r=0
python3 -I - "$RATHENA" <<'EOF' || r=$?
import os, re, sys
root = sys.argv[1]
want = {"jobmaster", "healer", "warper", "resetnpc", "stylist"}

def loaded(conf, seen):
	"""npc files the server loads from conf (following import: lines)"""
	files = []
	for line in open(os.path.join(root, conf), errors="ignore"):
		line = line.split("//")[0].strip()
		m = re.match(r"(npc|import):\s*(\S+)", line)
		if not m or m.group(2) in seen:
			continue
		seen.add(m.group(2))
		if m.group(1) == "import":
			files += loaded(m.group(2), seen)
		else:
			files.append(m.group(2))
	return files

files = loaded("npc/re/scripts_main.conf", set())
custom = {os.path.splitext(os.path.basename(f))[0]: f for f in files if f.startswith("npc/custom/")}
bad = 0
for name in sorted(want - custom.keys()):
	print(f"FAIL  {name}.txt not loaded"); bad += 1
spots = []
for f in files:
	path = os.path.join(root, f)
	if not os.path.isfile(path):
		continue
	for line in open(path, errors="ignore"):
		m = re.match(r"prontera,(\d+),(\d+),\d+\t(script|duplicate\([^)]*\)|shop|cashshop|trader)\t([^\t]+)", line)
		if m:
			spots.append((int(m.group(1)), int(m.group(2)), m.group(4).strip(), f))
for x, y, name, f in spots:
	if os.path.splitext(os.path.basename(f))[0] not in want:
		continue
	near = [o for o in spots if o[3] != f and max(abs(o[0] - x), abs(o[1] - y)) < 2]
	if near:
		print(f"FAIL  {name} at {x},{y} next to {', '.join(f'{n} {a},{b}' for a, b, n, _ in near)}"); bad += 1
	else:
		print(f"ok    {name} at {x},{y}")
sys.exit(1 if bad else 0)
EOF
check "custom NPCs listed and spaced" $r

# 2. loaded cleanly: the last server start's map log
log="$OUT/logs/map.log"
if [ -f "$log" ]; then
	errors=$(grep -aiE 'error|warning' "$log" | grep -aE 'npc/custom/(jobmaster|healer|warper|resetnpc|stylist)' || true)
	[ -z "$errors" ] && r=0 || { echo "$errors"; r=1; }
	check "map-server loads them without errors" $r
else
	check "map-server log present (start scripts/run-servers.sh first)" 1
fi

[ "${1:-}" = static ] && { [ "$fails" = 0 ]; exit; }

# 3. played
char=JobTester
if [ -z "$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")" ]; then
	( cd "$CLIENT" && cargo run -q -p nostalre-network --example session_probe -- --create "$char" --seconds 1 ) \
		> /dev/null 2>&1 || true
fi
id=$(sql "SELECT char_id FROM \`char\` WHERE name = '$char'")
[ -n "$id" ] || { echo "FAIL  no character $char"; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# a Novice next to the Job Master (2 cells north of it), skill points spent, at job level $1
novice() {
	sql "UPDATE \`char\` SET class = 0, base_level = 10, job_level = $1, skill_point = 0, hp = max_hp,
		last_map = 'prontera', last_x = 153, last_y = 195 WHERE char_id = $id"
}
# talk to the nearest NPC, then Enter through its dialog
cat > "$tmp/talk.txt" <<'EOF'
wait 4
talk
wait 1.5
key enter
wait 1
key enter
wait 1
key enter
wait 1
key enter
wait 1
key enter
wait 1
quit
EOF
play() { NOSTALRE_CHAR="$char" "$ROOT/scripts/run-nostalre-script.sh" "$1" > "$tmp/run.txt" 2>&1 || true; }

novice 9
play "$tmp/talk.txt"
check "Job Master turns away job level 9 (class $(sql "SELECT class FROM \`char\` WHERE char_id = $id"))" \
	"$([ "$(sql "SELECT class FROM \`char\` WHERE char_id = $id")" = 0 ] && echo 0 || echo 1)"

novice 10
play "$tmp/talk.txt"
class=$(sql "SELECT class FROM \`char\` WHERE char_id = $id")
check "Job Master makes job level 10 a Swordman (class $class)" "$([ "$class" = 1 ] && echo 0 || echo 1)"

# the Healer: 2 cells north of it, HP 1
read -r hx hy < <(grep -m1 -oP '^prontera,\K\d+,\d+(?=,\d+\tduplicate\(Healer\))' "$RATHENA/npc/custom/healer.txt" | tr , ' ')
sql "UPDATE \`char\` SET hp = 1, last_map = 'prontera', last_x = $hx, last_y = $((hy + 2)) WHERE char_id = $id"
play "$tmp/talk.txt"
hp=$(sql "SELECT hp || '/' || max_hp FROM \`char\` WHERE char_id = $id")
check "Healer fills HP ($hp)" "$([ "${hp%/*}" = "${hp#*/}" ] && echo 0 || echo 1)"

[ "$fails" = 0 ]
