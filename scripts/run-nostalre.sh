#!/usr/bin/env bash
# Desktop trial of nostalre-client (native Rust/wgpu RO client) against our servers, built for its packet
# version. Builds the servers and a database (account tester/tester, GM) on first use, points the client's
# config.json at game-data/ and the local login server, then runs both. Quitting the client stops the servers it
# started; servers already running for that version are used as they are.
# The client speaks one packet version (PACKETVER, common.sh) to a Renewal server.
# Needs: nostalre-client checked out (the client is built incrementally before each run).
# Usage: scripts/run-nostalre.sh   (NOSTALRE_DIR=<checkout>, default client/;
#   NOSTALRE_TRACE=0 to trace only unhandled packets;
#   NOSTALRE_SCRIPT=<file> NOSTALRE_CHAR_ID=<id>: log in as tester, enter that character and run the script,
#   see scripts/run-nostalre-script.sh)
set -euo pipefail
source "$(dirname "$0")/common.sh"
source "$(dirname "$0")/common.sh"

client="$CLIENT"
client_info="$(native_client_info "$client")"
mapfile -t client_parts <<< "$client_info"
exe="${client_parts[2]}"
# always build: a no-op when nothing changed, and a run never shows a client older than the code
( cd "$client" && cargo build -q --release --bin "${exe##*/}" )
[ -x "$OUT/host/bin/map-server" ] || "$ROOT/scripts/build-server.sh" host
[ -f "$OUT/db/ragnarok.db" ] || "$ROOT/scripts/make-db.sh" --test-account

# GRFs in DATA.INI order (data.grf alone without one), then official_data.grf when it is there and DATA.INI does not
# name it, as the Android app does (GameDir.archives); the client's first archive wins too, game-data/data over
# them; everything else the client keeps as it wrote it
python3 -I - "$client" "$ROOT/game-data" "$OUT" <<'EOF'
import configparser, json, os, shutil, sys
client, data, out = sys.argv[1], sys.argv[2], sys.argv[3]
path = os.path.join(client, "config.json")
sample = os.path.join(client, "config.sample.json")
config = json.load(open(path if os.path.exists(path) else sample))
ini = configparser.ConfigParser()
ini.read(os.path.join(data, "DATA.INI"))
listed = [(k, v) for k, v in ini["Data"].items() if k.isdigit()] if ini.has_section("Data") else []
names = [name for _, name in sorted(listed, key=lambda kv: int(kv[0]))]
names = names or ["data.grf"]
if os.path.isfile(os.path.join(data, "official_data.grf")) and "official_data.grf" not in (n.lower() for n in names):
    names.append("official_data.grf")
config["grf_paths"] = [os.path.join(data, name) for name in names]
config["bgm_path"] = os.path.join(data, "BGM")
# the pinned client's folder, the first archive's: its System and SystemEN tables (map titles, special pop-ups)
config["install_dir"] = os.path.dirname(os.path.normpath(config["grf_paths"][0])) if config["grf_paths"] else None
# loose files over the GRFs, as the Android app gives them: the English text (scripts/import-english.py).
# Their names are CP949 bytes spelled as Latin-1 (grf-server's convention); the client wants the Korean
# itself, so it reads a mirror of symlinks under decoded names, rebuilt each run so removed files go too.
loose = os.path.join(data, "data")
mirror = os.path.join(out, "nostalre-data")
shutil.rmtree(mirror, ignore_errors=True)
if os.path.isdir(loose):
    for root, _, files in os.walk(loose):
        for name in files:
            source = os.path.join(root, name)
            rel = os.path.relpath(source, loose)
            try:
                rel = rel.encode("latin-1").decode("cp949")
            except (UnicodeEncodeError, UnicodeDecodeError):
                pass  # already plain or real Unicode
            link = os.path.join(mirror, rel)
            os.makedirs(os.path.dirname(link), exist_ok=True)
            os.symlink(source, link)
config["data_dir"] = mirror if os.path.isdir(loose) else None
config["login_servers"] = [{"name": "nostalRE", "host": "127.0.0.1", "port": 6900}]
# a scripted run logs in and enters its character by itself (the hub's solo launch)
char_id = os.environ.get("NOSTALRE_CHAR_ID")
user, password = os.environ.get("NOSTALRE_USER", "tester"), os.environ.get("NOSTALRE_PASS", "tester")
config["solo"] = {"username": user, "password": password, "char_id": int(char_id)} if char_id else None
# every packet both ways goes to out/logs/nostalre-client.log, so a problem can be read back at once;
# NOSTALRE_TRACE=0 logs only the unhandled ones
trace = os.environ.get("NOSTALRE_TRACE") != "0"
config.setdefault("debug", {})["trace_packet"] = "all" if trace else "unhandled"
config["trace_packets_send"] = config["trace_packets_recv"] = trace
json.dump(config, open(path, "w"), indent=2)
EOF

logs="$OUT/logs"
if ss -ltn | grep -q '127.0.0.1:6900 '; then
	echo "servers already running: using them"
else
	rm -f "$logs/map.log" # the wait below must not find the last run's line
	"$ROOT/scripts/run-servers.sh" &
	servers=$!
	trap 'kill $servers 2>/dev/null; wait' EXIT INT TERM
	timeout 180 bash -c "until grep -aq 'Map-server connected to char-server' '$logs/map.log' 2>/dev/null; do sleep 1; done"
fi

# the client reads config.json from the working directory; NOSTALRE_LOG gives a second client its own log
( cd "$client" && "$exe" ) 2>&1 | tee "${NOSTALRE_LOG:-$logs/nostalre-client.log}"
