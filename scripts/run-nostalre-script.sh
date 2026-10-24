#!/usr/bin/env bash
# Scripted run of the desktop native client (nostalRE) you can watch: the client logs in by itself, enters a
# character of the test account and plays a script (client/src/script.rs) through GM commands and camera moves.
#   tour [map...]   the map tour of test-map-tour-native.sh: warp, zoom all the way in, out a notch at a time with
#                   a screenshot each, turn, walk; monsters ignore the character meanwhile (@monsterignore)
#   spam [seconds]  the skill spam of test-skill-spam-native.sh: 40 monsters, Lord of Vermilion, Storm Gust and
#                   Meteor Storm every SKILL_SPAM_CAST_MS, frame rate from the client log. Played by its own
#                   character, Spammer: created on first use (the network crate's session probe), made a level 99
#                   Wizard with 99 INT so it has the SP, cast in Prontera
#   FILE            any script file
# Screenshots (grim, the whole screen; gamescopectl when run inside a headless gamescope), the client log and a
# summary go to out/nostalre-script/<time>-<name>/.
# GM commands are checked against the server's command log (atcommandlog), like common.sh game_command does.
# Uses the servers of scripts/run-nostalre.sh, started if they are not running.
# Usage: scripts/run-nostalre-script.sh tour|spam|FILE [args]   (NOSTALRE_CHAR=<name>, default the first character)
set -euo pipefail
source "$(dirname "$0")/common.sh"
source "$(dirname "$0")/common.sh"

what="${1:?usage: run-nostalre-script.sh tour|spam|FILE [args]}"
shift
db="$OUT/db/ragnarok.db"
logs="$OUT/logs"
sql() { sqlite3 -batch -noheader "$db" "$1"; }

if [ "$what" = spam ] && [ -z "${NOSTALRE_CHAR:-}" ]; then
	NOSTALRE_CHAR=Spammer
	if [ -z "$(sql "SELECT char_id FROM \`char\` WHERE name = 'Spammer'")" ]; then
		echo "creating Spammer"
		( cd "$ROOT/client" && cargo run -q -p nostalre-network --example session_probe -- \
			--create Spammer --seconds 1 ) > /dev/null 2>&1 || true
	fi
fi
name="${NOSTALRE_CHAR:-$(sql "SELECT c.name FROM \`char\` c JOIN login l USING (account_id) WHERE l.userid = 'tester' ORDER BY c.char_num LIMIT 1")}"
char_id=$(sql "SELECT char_id FROM \`char\` WHERE name = '${name//\'/\'\'}'")
[ -n "$char_id" ] || { echo "no character $name on the test account" >&2; exit 1; }
# the character's own account (the test accounts' passwords are in the database): a run can go on prober while the
# user plays on tester
account=$(sql "SELECT account_id FROM \`char\` WHERE char_id = $char_id")
export NOSTALRE_USER NOSTALRE_PASS
IFS='|' read -r NOSTALRE_USER NOSTALRE_PASS < <(sql "SELECT userid, user_pass FROM login WHERE account_id = $account")
# a run right after another finds the account's last session still online and is refused (0x0081, 8)
for _ in $(seq 60); do
	[ "$(sql "SELECT COUNT(*) FROM \`char\` WHERE account_id = $account AND online = 1")" = 0 ] && break
	sleep 1
done

run="$OUT/nostalre-script/$(date +%Y%m%d-%H%M%S)-$(basename "$what")"
mkdir -p "$run"
script="$run/script.txt"
case "$what" in
	tour)
		maps=("$@")
		[ ${#maps[@]} -gt 0 ] || maps=(prontera morocc geffen alberta izlude aldebaran yuno amatsu gonryun louyang
			umbala comodo niflheim prt_church gef_tower prt_sewb1 moc_pryd01 pay_dun00 gef_dun00 glast_01 alde_dun01
			prt_fild08 payon)
		{
			echo "wait 3"
			echo "chat @monsterignore"
			for map in "${maps[@]}"; do
				echo "# $map"
				echo "chat @alive"
				echo "chat @warp $map"
				echo "wait_map $map"
				echo "wait 3"
				echo "chat @heal"
				echo "shot $map-0"
				for _ in $(seq 8); do echo "zoom -2"; echo "wait 0.3"; done
				echo "wait 1"
				for i in $(seq 12); do echo "zoom 2"; echo "wait 1"; echo "shot $map-zoom$i"; done
				for _ in $(seq 6); do echo "turn -5"; echo "wait 0.4"; done
				for _ in $(seq 6); do echo "turn 5"; echo "wait 0.4"; done
				for _ in $(seq 6); do echo "zoom -2"; echo "wait 0.3"; done
				echo "walk 5 0"; echo "wait 2.5"; echo "walk 0 -5"; echo "wait 2.5"; echo "shot $map-walked"
				echo "walk -5 0"; echo "wait 2.5"; echo "walk 0 5"; echo "wait 2.5"
			done
			echo "chat @monsterignore"
			echo "wait 2"
			echo "quit"
		} > "$script"
		;;
	spam)
		seconds="${1:-60}"
		export SOLO_FPS_LOG=1
		level=$(sql "SELECT base_level FROM \`char\` WHERE char_id = $char_id")
		{
			echo "wait 3"
			if [ "$level" -lt 99 ]; then
				echo "chat @blvl $((99 - level))"
				echo "chat @job 9"
				echo "chat @int 98"
			fi
			echo "chat @warp prontera 156 180"
			echo "wait_map prontera"
			echo "wait 2"
			echo "chat @alive"
			echo "chat @heal"
			echo "chat @monsterignore"
			echo "chat @setbattleflag casting_rate 0"
			echo "chat @setbattleflag delay_rate 0"
			# Raydric ... Bloody Knight, levels 52-82 (as skill_spam.mjs)
			for monster in "1163 8" "1132 8" "1098 8" "1219 8" "1268 8"; do echo "chat @monster $monster"; done
			echo "wait 2"
			echo "shot spam-start"
			casts=$((seconds * 1000 / SKILL_SPAM_CAST_MS))
			wait=$(awk "BEGIN { print $SKILL_SPAM_CAST_MS / 1000 }")
			for i in $(seq 0 $((casts - 1))); do
				# Lord of Vermilion, Storm Gust, Meteor Storm
				skill=$(( (i % 3 == 0) ? 85 : (i % 3 == 1) ? 89 : 83 ))
				echo "chat @useskill $skill 10 $name"
				echo "wait $wait"
				[ $((i % 25)) != 12 ] || echo "shot spam-$i"
				[ $((i % 8)) != 7 ] || echo "chat @heal"
			done
			echo "chat @setbattleflag casting_rate 100"
			echo "chat @setbattleflag delay_rate 100"
			echo "chat @killmonster2"
			echo "chat @monsterignore"
			echo "wait 2"
			echo "quit"
		} > "$script"
		;;
	*)
		cp "$what" "$script"
		;;
esac

echo "$name (char $char_id), $(grep -c . "$script") steps: $script"
echo "Raw data   $run"
last=$(sql "SELECT COALESCE(MAX(atcommand_id), 0) FROM atcommandlog")
# its own log, not the one a client the user plays writes to as well
log="$logs/nostalre-script.log"
: > "$log"

# screenshots when the client asks for them; the watcher stops with the client
(
	tail -n0 -F "$log" 2>/dev/null | while IFS= read -r line; do
		case "$line" in
			*"script shot "*)
				shot="${line##*script shot }"
				shot="${shot//[^A-Za-z0-9_.-]/}"
				if [ -n "${GAMESCOPE_WAYLAND_DISPLAY:-}" ]; then
					gamescopectl screenshot "$run/$shot.png" > /dev/null 2>&1 || true
				else
					grim "$run/$shot.png" 2>/dev/null || true
				fi
				;;
			*"script done"*) break ;;
		esac
	done
) &
watcher=$!
trap 'kill $watcher 2>/dev/null || true' EXIT

NOSTALRE_SCRIPT="$script" NOSTALRE_CHAR_ID="$char_id" NOSTALRE_LOG="$log" "$ROOT/scripts/run-nostalre.sh" > /dev/null || true
cp "$log" "$run/client.log"

# what the server confirmed: rAthena logs only the commands that succeed
sql "SELECT command FROM atcommandlog WHERE atcommand_id > $last AND char_name = '${name//\'/\'\'}' ORDER BY atcommand_id" \
	> "$run/confirmed.txt"
sent=$(grep -cE '^chat [@#]' "$script" || true)
confirmed=$(grep -c . "$run/confirmed.txt" || true)
{
	echo "character        $name"
	echo "steps run        $(grep -acE 'script: [A-Z]' "$run/client.log" || true) of $(grep -cvE '^(#|$)' "$script")"
	echo "finished         $(grep -aq 'script done' "$run/client.log" && echo yes || echo NO)"
	echo "GM commands      $confirmed of $sent confirmed by the server"
	# a refused command is one the server did not log (the dead-only @alive on a living character, a typo...)
	refused=$(sed -n 's/^chat //p' "$script" | sed -E 's/[[:space:]]+#([[:space:]].*)?$//' | grep '^[@#]' | sort | uniq -c | while read -r n cmd; do
		got=$(grep -cxF "$cmd" "$run/confirmed.txt" || true)
		[ "$got" -ge "$n" ] || echo "$cmd x$((n - got))"
	done | paste -sd, - | sed 's/,/, /g')
	echo "not confirmed    ${refused:-none}"
	echo "screenshots      $(find "$run" -name '*.png' | wc -l)"
	echo "unknown packets  $(grep -ac 'skipping unknown packet' "$run/client.log" || true) (stream desyncs)"
	echo "unparsed packets $(grep -a 'skipping unparsed packet' "$run/client.log" | sed -E 's/.*packet (0x[0-9a-f]+).*/\1/' | sort | uniq -c | sort -rn | awk '{printf "%s%s x%s", sep, $2, $1; sep=", "}')"
	echo "panics           $(grep -ac 'panicked\|handler panicked' "$run/client.log" || true)"
	echo "maps not loaded  $(grep -ac 'map_loader: Failed to' "$run/client.log" || true)"
	echo "warnings/errors  $(grep -aE ' WARN | ERROR ' "$run/client.log" | sed -E 's/^[^ ]+ +//' | sort -u | wc -l) distinct"
	if [ "$what" = spam ]; then
		echo "frame rate       $(grep -aoE ' fps [0-9]+$' "$run/client.log" | awk '{n++; s+=$2; if (min == "" || $2 < min) min = $2} END {if (n) printf "%.0f average, %d lowest over %d s", s/n, min, n}')"
	fi
} | tee "$run/summary.txt"
