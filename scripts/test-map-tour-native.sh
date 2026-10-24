#!/usr/bin/env bash
# Map tour of the native game client (NATIVE_CLIENT=1 build) on the USB-connected device, for checking the
# renderer by eye: warps through towns, fields, dungeons and indoor maps; on each one zooms all the way in, steps
# out one notch at a time, turns the camera and walks a few cells. Watch the screen while it runs.
# Reports per map whether it was entered, the client's warnings and errors while there, and whether the game
# kept running; a screenshot per zoom step stays in out/map-tour/<time>/ (TOUR_SHOTS=0 skips them).
# TOUR_QUICK=1: only load each map and take one screenshot, for sweeping many maps.
# The game must be open and in game on a GM account. Monster immunity (@monsterignore) is on for the tour and off
# after it; GM commands are confirmed in the server's command log (common.sh game_command).
# Usage: scripts/test-map-tour-native.sh [map...]   (default: the list below)
set -euo pipefail
source "$(dirname "$0")/common.sh"
run="$OUT/map-tour/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$run"
shots="${TOUR_SHOTS:-1}"
quick="${TOUR_QUICK:-0}"

maps=("$@")
if [ ${#maps[@]} -eq 0 ]; then
	maps=(prontera morocc geffen alberta izlude aldebaran yuno amatsu gonryun louyang umbala comodo niflheim
		prt_church gef_tower prt_sewb1 moc_pryd01 pay_dun00 gef_dun00 glast_01 alde_dun01 prt_fild08 payon)
fi

log() { adb shell run-as "$GAME_APP" cat files/client.log; }
game_pid() { adb shell pidof "$GAME_APP:game" || true; }
# whether the client loaded map $2 after log line $1 (grep -c reads everything: grep -q would leave tail to a
# SIGPIPE that pipefail reports as failure)
entered() { [ "$(log | tail -n +"$1" | grep -ac "Map $2.rsw ")" -gt 0 ]; }
pid=$(game_pid)
[ -n "$pid" ] || { echo "the game is not running: open the app and Play" >&2; exit 1; }
game_ready || exit 1
adb logcat -b crash -c

# Select+D-pad: the camera (Up zooms out, Down in, Left/Right turn)
camera() { adb shell input keycombination KEYCODE_BUTTON_SELECT "KEYCODE_DPAD_$1"; }
shot() { [ "$shots" = 0 ] || adb exec-out screencap -p > "$run/$1.png"; }

# the client's own noise, not about the map
NOISE='TODO: |sound not found|Retrying sprite load| fps [0-9]+$'

visit() {
	local map=$1 from i
	from=$(($(log | wc -l) + 1))
	# the dead cannot warp: monsters of the last map may have killed the character
	game_chat '@alive'
	if ! game_command "@warp $map"; then
		echo "$map: not entered (the warp failed, reason above)" | tee -a "$run/summary.txt"
		return
	fi
	for i in $(seq 40); do
		entered "$from" "$map" && break
		sleep 0.5
	done
	if ! entered "$from" "$map"; then
		echo "$map: not entered" | tee -a "$run/summary.txt"
		return
	fi
	sleep 3
	game_command '@heal' || true
	shot "$map-0"
	[ "$quick" = 1 ] || look_around "$map"
	report "$map" "$from"
}

look_around() {
	local map=$1 i
	for i in $(seq 8); do camera DOWN; sleep 0.3; done
	sleep 1
	for i in $(seq 12); do
		camera UP
		sleep 1
		shot "$map-zoom$i"
	done
	for i in $(seq 6); do camera LEFT; sleep 0.4; done
	for i in $(seq 6); do camera RIGHT; sleep 0.4; done
	for i in $(seq 6); do camera DOWN; sleep 0.3; done
	# taps clear of the windows: right, down, left, up of the character
	game_in_front || return 0
	game_chat '@alive'
	for at in "1400 450" "1200 760" "760 520" "960 300"; do
		adb shell input tap $at
		sleep 2.5
		[ "$at" != "1200 760" ] || shot "$map-walked"
	done
}

report() {
	local map=$1 from=$2
	log | tail -n +"$from" | grep -a ' WARN \| ERROR \|panicked' | grep -avE "$NOISE" \
		| sed -E 's/^[^ ]+ +//' | sort | uniq -c | sort -rn > "$run/$map.log" || true
	echo "$map: $(wc -l < "$run/$map.log") distinct warnings/errors" | tee -a "$run/summary.txt"
	sed 's/^/    /' "$run/$map.log" | head -8
}

# dead, the respawn window takes the keys and no command gets through
set_immunity on || exit 1
trap 'set_immunity off' EXIT
for map in "${maps[@]}"; do
	[ -z "${GAME_BLOCKED:-}" ] || { echo "stopped: the game no longer takes commands" | tee -a "$run/summary.txt"; break; }
	game_in_front || { echo "stopped: the game left the front" | tee -a "$run/summary.txt"; break; }
	visit "$map"
	if [ "$(game_pid)" != "$pid" ]; then
		echo "the game stopped on $map" | tee -a "$run/summary.txt"
		adb logcat -b crash -d > "$run/crash.txt"
		log | tail -40 > "$run/client-tail.log"
		break
	fi
done
echo "Raw data   $run"
