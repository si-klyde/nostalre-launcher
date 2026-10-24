# nostalRE launcher

Everything that turns [nostalRE](https://github.com/si-klyde/nostalre) into a game you can play offline, on a PC
or an Android handheld: rAthena's servers, the Android app around the client, and the scripts that build and run
them together.

| Path | What it is |
| --- | --- |
| `client/` | nostalRE, the Rust/wgpu client (submodule, [si-klyde/nostalre](https://github.com/si-klyde/nostalre)) |
| `server/` | rAthena with an embedded SQLite backend and Android support (submodule, [si-klyde/rathena-nostalre](https://github.com/si-klyde/rathena-nostalre)) |
| `android/` | The Android app: setup, the character hub and creator, the servers as background processes |
| `bridge/` | `grf-server`: the game files over local HTTP, for the hub's character pictures and map names |
| `scripts/` | Build and run scripts, and the generators of the client's rAthena-derived tables |
| `game-data/` | Your own game files for desktop play (see its README; never committed) |

No game files are included: bring your own (see [game-data/README.md](game-data/README.md)).

```sh
git clone --recurse-submodules https://github.com/si-klyde/nostalre-launcher.git
cd nostalre-launcher
```

## Play on Android

Download the APK from [Releases](https://github.com/si-klyde/nostalre-launcher/releases) (Android 11+, 64-bit
ARM), copy your game folder to the device, open the app and choose that folder. The app runs the servers itself:
no computer or network needed.

## Play on PC

Needs Rust (through rustup), CMake, Ninja, a C++17 compiler, zlib, SQLite 3 and Python 3; on Debian/Ubuntu also
nostalRE's own packages (see client/README.md).

```sh
scripts/run-nostalre.sh
```

The first run builds the servers and makes a database with a test account (`tester` / `tester`); every run
builds the client, starts the servers and the client, and stops the servers when the client quits.
`scripts/run-servers.sh` runs the servers alone.

## Build the APK

Needs the Android SDK and NDK (`ANDROID_NDK_HOME`, r30), `cargo-ndk` and the `aarch64-linux-android` Rust target.

```sh
scripts/build-apk.sh          # out/nostalre.apk
adb install -r out/nostalre.apk
```

`--skip-native` reuses the last Android builds of the servers, the bridge and the client. `--release` builds the
public, non-debuggable APK signed with the release key, which the builder keeps in `~/.gradle/gradle.properties`
(see the script); without the key, debug builds use the Android debug key.

## Tables from rAthena

Parts of the client and the app are generated from rAthena's data: skills, effects, items, jobs, skill trees,
job names. After changing `server/`, rerun the generator and commit its output where it lands;
`scripts/check-generated.sh` fails while any of them is stale.

| Script | Writes |
| --- | --- |
| `skill-enum.py` | `client/rust-ro/models/src/enums/skill_enums.rs` |
| `effect-id.py` | `client/rust-ro/models/src/enums/effect_id.rs` |
| `item-tables.py` | `client/lib/game/src/item_tables.rs` |
| `job-tables.py` | `client/lib/game/src/job_tables.rs` |
| `skill-tree.py` | `client/lib/game/src/skill_tree_tables.rs` |
| `job-names.py` | `android/app/src/main/java/io/github/siklyde/nostalre/JobNames.kt` |
| `packetdb-dump.sh` | `client/lib/network/data/rathena-20250716.tsv` (with its layout file) |

## License

GNU General Public License version 3 ([LICENSE](LICENSE)); [NOTICE](NOTICE) lists what this builds on.
nostalRE and rAthena keep their own licenses in their repositories.

## AI use disclosure

AI tools assist with implementation, debugging, tests and documentation in this repository, as in nostalRE.
Changes are built, tested and checked on a device before they are released.
