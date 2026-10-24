# Shared paths and settings, sourced by the other scripts.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/out"
RATHENA="$ROOT/server"
BRIDGE="$ROOT/bridge"
# the nostalRE checkout: the client/ submodule, or another one (NOSTALRE_DIR)
CLIENT="${NOSTALRE_DIR:-$ROOT/client}"
GAME_DATA="$ROOT/game-data"

# nostalRE speaks one packet version, 20250716 (`rathena_table::PACKETVER`), to Renewal servers: the servers are
# built for it (docs in client/docs/packet-status-20250716.md)
PACKETVER=20250716
ANDROID_API=26
ANDROID_ABI=arm64-v8a
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/30.0.16248370}"
NDK_BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
# skill spam test: one cast every this many ms
SKILL_SPAM_CAST_MS=800

native_client_info() {
	( cd "$1" && cargo metadata --offline --no-deps --format-version 1 ) | python3 -c '
import json, sys
metadata = json.load(sys.stdin)
package, = (p for p in metadata["packages"] if p["name"] == "nostalre-client")
library, = (t for t in package["targets"] if "cdylib" in t["crate_types"])
binary, = (t for t in package["targets"] if "bin" in t["kind"])
print(package["name"])
print(metadata["target_directory"] + "/aarch64-linux-android/release/lib" + library["name"] + ".so")
print(metadata["target_directory"] + "/release/" + binary["name"])
'
}

# CMake arguments for an NDK cross build
android_cmake_args() {
	echo -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
		-DANDROID_ABI="$ANDROID_ABI" \
		-DANDROID_PLATFORM="android-$ANDROID_API"
}

# rAthena's import/ override folders, fresh from import-tmpl where missing (the servers warn without them)
server_imports() {
	local tmpl
	for tmpl in "$1"/conf/import-tmpl "$1"/conf/msg_conf/import-tmpl "$1"/db/import-tmpl; do
		[ -d "${tmpl%-tmpl}" ] || cp -r "$tmpl" "${tmpl%-tmpl}"
	done
}

# A GM command (or any line) into the chat of the native game on the USB-connected device: Enter opens the chat
# line, the text goes in as key events (spaces as %s), Enter sends it
game_chat() {
	adb shell "input keyevent KEYCODE_ENTER; input text '${1// /%s}'; input keyevent KEYCODE_ENTER"
	sleep 0.15
}

GAME_APP=io.github.siklyde.nostalre

# A query on the device server's database (files/save/ragnarok.db with its WAL), on a copy taken in one go (tar):
# a copy torn by a checkpoint fails the query, and is taken again. Returns 1 when three copies fail.
device_sql() {
	local dir try out
	dir=$(mktemp -d)
	for try in 1 2 3; do
		rm -f "$dir"/ragnarok.db*
		adb exec-out "run-as $GAME_APP sh -c 'cd files/save && tar -cf - ragnarok.db*'" 2>/dev/null | tar -xf - -C "$dir" 2>/dev/null
		if out=$(sqlite3 "$dir/ragnarok.db" "$1" 2>/dev/null); then
			rm -rf "$dir"
			printf '%s\n' "$out"
			return 0
		fi
		sleep 0.5
	done
	rm -rf "$dir"
	echo "the device database could not be read" >&2
	return 1
}

# A count from device_sql, never empty: anything but a number is a failure, not a 0
device_count() {
	local n
	n=$(device_sql "$1") || return 1
	[[ $n =~ ^[0-9]+$ ]] || { echo "the device database answered '$n' instead of a number" >&2; return 1; }
	echo "$n"
}

# The device's clock, as the server writes it into its logs
device_now() { adb shell "date '+%Y-%m-%d %H:%M:%S'" | tr -d '\r'; }

sql_text() { printf "'%s'" "${1//\'/\'\'}"; }

# Whether the game screen itself is in front (not the app's hub, another app or a launcher): keys and taps go to
# whatever is, and on a launcher Enter opens things
game_in_front() {
	adb shell dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity' | grep -q "$GAME_APP/.GameActivity"
}

# Before typing into the game: the screen awake (woken if asleep; input stalls while it is off), not locked, and
# the game in front. Returns 1 with the reason otherwise.
game_ready() {
	if ! adb shell dumpsys power | grep -q 'mWakefulness=Awake'; then
		adb shell input keyevent KEYCODE_WAKEUP
		sleep 1
	fi
	if adb shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
		echo "the device is locked: unlock it and start again" >&2
		return 1
	fi
	if ! game_in_front; then
		echo "the game is not in front: open it and enter the game" >&2
		return 1
	fi
}

# The character in game: the last one selected
game_character() { device_sql "SELECT name FROM charlog WHERE char_msg = 'char select' ORDER BY id DESC LIMIT 1"; }

# Whether the server logged this command after row $2 and at or after time $3 (atcommandlog), looking for 5 s: one
# copy of the database can miss a fresh row, so a command is not taken as lost (and sent again: a toggle sent twice
# undoes itself) sooner. The time keeps an older identical command from counting when a stale copy gave too low a
# row. Returns 2 when the database cannot be read.
game_logged() {
	local i n
	for i in 1 2 3 4 5; do
		n=$(device_count "SELECT COUNT(*) FROM atcommandlog WHERE atcommand_id > $2 AND atcommand_date >= $(sql_text "$3") AND command = $(sql_text "$1")") || return 2
		[ "$n" -gt 0 ] && return 0
		sleep 1
	done
	return 1
}

# An @command into the game, confirmed in the server's command log. rAthena logs only commands that succeed, so a
# missing row means one of two things, told apart by a probe that always succeeds and cannot be mistaken for any
# other row (@me with a random word): the server refused the command (the probe arrives: returns 1), or the input
# went nowhere. Then two fixes are tried, each followed by one more try: Enter alone (the chat line was already
# open, so game_chat's own Enters closed and reopened it around the text), then the death menu's first button
# (Return to last save point, at 958,620 on the RP6). Input still lost, or the database unreadable: returns 1, and
# from then on at once (GAME_BLOCKED), since a cleanup after it would only wait on the same.
game_command() {
	local command=$1 last since fix probe status
	[ -z "${GAME_BLOCKED:-}" ] || return 1
	if ! last=$(device_count "SELECT COALESCE(MAX(atcommand_id), 0) FROM atcommandlog") || ! since=$(device_now); then
		GAME_BLOCKED=1
		return 1
	fi
	for fix in none enter death-menu; do
		if ! game_in_front; then
			echo "the game left the front: no more keys sent" >&2
			GAME_BLOCKED=1
			return 1
		fi
		case "$fix" in
			enter) adb shell input keyevent KEYCODE_ENTER; sleep 0.5 ;;
			death-menu) adb shell input tap 958 620; sleep 3 ;;
		esac
		game_chat "$command"
		game_logged "$command" "$last" "$since"
		status=$?
		[ "$status" = 0 ] && return 0
		[ "$status" = 2 ] && break
		probe="@me probe-$RANDOM$RANDOM"
		game_chat "$probe"
		game_logged "$probe" "$last" "$since"
		status=$?
		if [ "$status" = 0 ]; then
			echo "the server refused $command" >&2
			return 1
		fi
		[ "$status" = 2 ] && break
	done
	[ "$status" = 2 ] || echo "the game never received $command (chat blocked by a window?)" >&2
	GAME_BLOCKED=1
	return 1
}

# Monster immunity (@monsterignore, alias @battleignore) is a toggle that resets when a character is selected, so its
# state is the number of toggles since then: on (odd) or off (even). Toggles only when it is not already as wanted;
# returns 1 when the state cannot be read.
set_immunity() {
	local want=$1 name since toggles
	name=$(game_character) && [ -n "$name" ] || { echo "no character in game" >&2; return 1; }
	since=$(device_sql "SELECT time FROM charlog WHERE char_msg = 'char select' AND name = $(sql_text "$name") ORDER BY id DESC LIMIT 1") && [ -n "$since" ] || return 1
	toggles=$(device_count "SELECT COUNT(*) FROM atcommandlog WHERE char_name = $(sql_text "$name") AND command IN ('@monsterignore', '@battleignore') AND atcommand_date >= $(sql_text "$since")") || return 1
	if { [ "$want" = on ] && [ $((toggles % 2)) = 0 ]; } || { [ "$want" = off ] && [ $((toggles % 2)) = 1 ]; }; then
		game_command '@monsterignore'
	fi
}
