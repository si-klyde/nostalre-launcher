#!/usr/bin/env bash
# Skill spam performance test of nostalRE on the USB-connected device: high-level monsters spawned and Lord of
# Vermilion, Storm Gust and Meteor Storm cast back to back, GM commands typed into the chat over adb. Frame rate from
# the client's log (SOLO_FPS_LOG in env.txt), skill hits from its SkillDamage lines, CPU from top. Casts go out on a
# fixed schedule (SKILL_SPAM_CAST_MS). The game must be open and in game on a GM account, below 90% weight.
# Raw results stay in out/skill-spam/<time>-native/ for comparing runs.
# Usage: scripts/test-skill-spam-native.sh [seconds] [character name]   (default 60, the character in game)
set -euo pipefail
source "$(dirname "$0")/common.sh"
seconds="${1:-60}"
name="${2:-}"
run="$OUT/skill-spam/$(date +%Y%m%d-%H%M%S)-native"
mkdir -p "$run"
log() { adb shell run-as "$GAME_APP" cat files/client.log; }

adb shell pidof "$GAME_APP:game" >/dev/null || { echo "the game is not running: open the app and Play" >&2; exit 1; }
game_ready || exit 1
[ -n "$name" ] || name=$(game_character)
# the frame rate log on, other settings in env.txt kept
{ adb exec-out "run-as $GAME_APP sh -c 'cat files/env.txt 2>/dev/null'" | grep -av '^SOLO_FPS_LOG=' || true; echo SOLO_FPS_LOG=1; } \
	| adb shell "run-as $GAME_APP sh -c 'cat > files/env.txt'"
start=$(log | wc -l)
sleep 3
if [ "$(log | tail -n +"$start" | grep -ac ' fps ')" = 0 ]; then
	echo "no frame rate in the log: leave the game (Back, Exit) and Play again, it reads env.txt when it starts" >&2
	exit 1
fi

now_ms() { echo $(($(date +%s%N) / 1000000)); }
spawn() { for monster in "$@"; do game_chat "@monster $monster"; done; }

# Lord of Vermilion, Storm Gust, Meteor Storm; Raydric ... Bloody Knight, levels 52-82
skills=(85 89 83)
monsters=("1163 8" "1132 8" "1098 8" "1219 8" "1268 8")
refill=("1268 2" "1219 2" "1098 2")

adb shell "top -b -d 2 -n $((seconds / 2 + 30)) -o %CPU,NAME" > "$run/top.txt" &
top_pid=$!
cleanup() {
	game_command '@setbattleflag casting_rate 100' || true
	game_command '@setbattleflag delay_rate 100' || true
	game_command '@killmonster2' || true
	set_immunity off || true
	kill $top_pid 2>/dev/null || true
}
trap cleanup EXIT

game_chat '@alive'
game_command '@heal'
set_immunity on
game_command '@setbattleflag casting_rate 0'
game_command '@setbattleflag delay_rate 0'
for monster in "${monsters[@]}"; do game_command "@monster $monster"; done
sleep 2
from=$(log | wc -l)
start=$(now_ms)
end=$((start + seconds * 1000))
casts=0
late=0
while [ "$(now_ms)" -lt $end ]; do
	for round in 1 2 3 4 5; do
		[ "$(now_ms)" -lt $end ] || break
		game_in_front || { echo "stopped: the game left the front" >&2; exit 1; }
		for skill in "${skills[@]}"; do
			ahead=$((start + casts * SKILL_SPAM_CAST_MS - $(now_ms)))
			if [ $ahead -gt 0 ]; then
				sleep "$(printf '%d.%03d' $((ahead / 1000)) $((ahead % 1000)))"
			elif [ $casts -gt 0 ]; then
				late=$((late + 1))
			fi
			game_chat "@useskill $skill 10 $name"
			casts=$((casts + 1))
		done
		game_chat '@heal'
	done
	spawn "${refill[@]}"
done
log | tail -n +"$from" > "$run/client.log"
trap - EXIT
cleanup

python3 -I - "$run" "$casts" "$late" <<'EOF'
import re, statistics, sys
run = sys.argv[1]
lines = open(f"{run}/client.log", errors="replace").read().splitlines()
fps = [int(m.group(1)) for line in lines if (m := re.search(r" fps (\d+)$", line))]
hits = sum(1 for line in lines if re.search(r"SkillDamage: skill=(WzVermilion|WzStormgust|WzMeteor)\b", line))
WARMUP = 5  # first seconds: sprites of the new monsters still loading
cpu = {"game": [], "map-server": []}
for line in open(f"{run}/top.txt"):
    parts = line.split()
    if len(parts) == 2 and parts[1] in ("net.ragnarok.solo:game", "libmapserver.so"):
        cpu["game" if parts[1].endswith(":game") else "map-server"].append(float(parts[0]))
steady = sorted(fps[WARMUP:]) or [0]
print(f"FPS        median {statistics.median(steady):.0f}   5th percentile {steady[len(steady) // 20]}"
      f"   min {steady[0]}   (first {WARMUP} s: {fps[:WARMUP]})")
print(f"Skill hits {hits}")
print(f"Casts      {sys.argv[2]}   late {sys.argv[3]}")
for name, values in cpu.items():
    if values:
        print(f"CPU {name:<11}median {statistics.median(values):.0f}%   max {max(values):.0f}%   (% of one core)")
print(f"Raw data   {run}")
if hits == 0:
    sys.exit("invalid run: skills did not hit (overweight? wrong character name?)")
EOF
