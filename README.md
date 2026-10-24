<a id="readme-top"></a>

[![Release][release-shield]][release-url]
[![Downloads][downloads-shield]][release-url]
[![Issues][issues-shield]][issues-url]
[![License][license-shield]][license-url]
[![Android][android-shield]][release-url]

<br />
<div align="center">
  <h3 align="center">nostalRE launcher</h3>

  <p align="center">
    Play nostalRE offline on an Android handheld or a PC: the game, its servers and everything that runs them.
    <br />
    <a href="https://github.com/si-klyde/nostalre-launcher/releases/latest"><strong>Download the APK »</strong></a>
    <br />
    <br />
    <a href="#installation">Setup guide</a>
    &middot;
    <a href="https://github.com/si-klyde/nostalre-launcher/issues/new">Report a bug</a>
    &middot;
    <a href="https://github.com/si-klyde/nostalre">nostalRE client</a>
  </p>
</div>

<details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
        <li><a href="#english-text">English text</a></li>
        <li><a href="#updating">Updating</a></li>
        <li><a href="#troubleshooting">Troubleshooting</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#play-on-pc">Play on PC</a></li>
    <li><a href="#build-from-source">Build from source</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

## About The Project

[![nostalRE on an Android handheld][product-screenshot]][release-url]

[nostalRE](https://github.com/si-klyde/nostalre) is a native Renewal client written in Rust. This repository
turns it into a game you can carry around: one Android app holds the client, rAthena's login, char and map
servers and a database, so you play alone, offline, with no computer or network. The same scripts run nostalRE
and its servers on a PC.

* Built for handhelds: touch controls sized for a thumb, and full controller support down to NPC dialogs and shops
* Everything local: the servers run on the device; your characters live in the app
* Bring your own game files: nothing copyrighted ships with the app

| Path | What it is |
| --- | --- |
| `client/` | nostalRE, the client (submodule, [si-klyde/nostalre](https://github.com/si-klyde/nostalre)) |
| `server/` | rAthena with an embedded SQLite backend and Android support (submodule, [si-klyde/rathena-nostalre](https://github.com/si-klyde/rathena-nostalre)) |
| `android/` | The Android app: setup, the character hub and creator, the servers as background processes |
| `bridge/` | `grf-server`: the game files over local HTTP, for the hub's character pictures and map names |
| `scripts/` | Build and run scripts, and the generators of the client's rAthena-derived tables |
| `game-data/` | Your own game files for PC play (never committed) |

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built With

* [![Rust][rust-shield]][rust-url]
* [![Kotlin][kotlin-shield]][kotlin-url]
* [![C++][cpp-shield]][rathena-url]
* [![SQLite][sqlite-shield]][sqlite-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting Started

### Prerequisites

* An Android 11+ device with a 64-bit ARM processor (`arm64-v8a`), for example a Retroid Pocket, an AYN Odin or
  most phones from the last few years
* About 5 GB of free storage for the game files
* Your own game files:
  * kRO's 2025-07-16 full client `data.grf` (GRF version 0x300, unencrypted). nostalRE speaks that client's
    packet version; older or encrypted archives do not work
  * ROenglishRE's
    [`official_data.grf`](https://llchrisll.github.io/ROTPDocs/misc/custom_officials/): the art of monsters, NPCs
    and items that the servers use and kRO's data lacks
  * Optional: the client's `BGM/` folder for music

### Installation

1. **Download** `nostalre-v….-arm64.apk` from the
   [latest release](https://github.com/si-klyde/nostalre-launcher/releases/latest) (under **Assets**).
2. **Make a game folder** on the device, for example `nostalRE` in the internal storage, and copy into it:
   ```
   nostalRE/
   ├── DATA.INI
   ├── data.grf
   ├── official_data.grf
   ├── BGM/            (optional)
   └── data/           (optional: English text, see below)
   ```
   `DATA.INI` lists the archives in the order they are read, the first one holding a file wins:
   ```ini
   [Data]
   1=data.grf
   2=official_data.grf
   ```
3. **Install** the APK: open it on the device and allow installs from your file manager or browser when Android
   asks.
4. **Open nostalRE** and follow the setup:
   1. *Allow access*: the app reads the game files where they are, so it needs access to all files.
   2. *Choose folder*: pick the game folder from step 2 (the folder itself, the one holding `DATA.INI`).
   3. The app unpacks its servers; this takes a moment the first time.
   4. *Username*: 4 to 23 letters, digits or `_`. There is no password to remember: the account lives on the
      device.
5. **Make a character**: choose *New character*, then a race (Human or Doram), a body, a hair style and colour,
   and a name, and *Create*.
6. **Play**: pick the character and press *Play*. The servers start in the background and the game logs in by
   itself.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### English text

kRO's data is in Korean. For English item, skill and map names, build an English `data/` folder on a PC once and
copy it into the game folder:

```sh
git clone https://github.com/llchrisll/ROenglishRE.git
git clone --recurse-submodules https://github.com/si-klyde/nostalre-launcher.git
python3 nostalre-launcher/scripts/import-english.py ROenglishRE   # writes nostalre-launcher/game-data/data/
```

It needs Python 3 with PyYAML, and `luac5.1` for the English item table. Copy the resulting `game-data/data/`
folder into the game folder on the device as `data/`.

### Updating

Download the new APK and install it over the old one: characters and settings stay. Version 0.1.1 was signed with
a development key, so 0.1.2 and later cannot install over it; uninstall 0.1.1 first (its characters go with it).

### Troubleshooting

| Message | What to do |
| --- | --- |
| *No DATA.INI in …* | Choose the game folder itself, not its parent or a GRF |
| *Port … did not open* | A server failed to start: the message shows where its logs are. Restart the app, and [report it](https://github.com/si-klyde/nostalre-launcher/issues/new) with the log if it repeats |
| Characters or monsters drawn as nothing | An archive is missing or out of order: check `DATA.INI` lists `data.grf` before `official_data.grf` |
| Korean text in game | Add the English `data/` folder ([English text](#english-text)) |

When reporting a problem, include the app version, the device and Android version, and the steps that lead to it.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Usage

**Touch**: the stick bottom left walks; the arrows above it turn the camera, and + / − beside the minimap zoom;
*Attack* attacks the nearest
monster; the round buttons around it use your hotbar's skills and items; *Talk* appears while an NPC is near and
talks to it. Tap the world to walk there, tap a monster to attack it, tap an NPC to talk.

**Controller**:

| Button | In the field | In a dialog or shop |
| --- | --- | --- |
| Left stick | Walk | |
| Right stick | Move the cursor | |
| A | Click | Next, choose, add to the basket |
| B | Right click | Cancel, close |
| X | Attack the nearest monster | |
| Y | Pick up an item, or talk to the nearest NPC | |
| L1 / R1 + face button | Use a hotbar slot | |
| Select + L1 / R1 | Switch hotbar set | |
| L2 / R2 | Turn the camera | |
| Select + Up / Down | Zoom | |
| R3 | Reset the camera | |
| D-pad | Arrow keys | Move between choices, items and buttons |
| Start | Enter | Buy or sell what is in the basket |

The game's menu (inventory, skills, equipment, settings) opens with the device's Back button, or by tapping *Menu*
in the bottom right corner. The UI scale is in *Settings*.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Play on PC

Needs Rust (through rustup), CMake, Ninja, a C++17 compiler, zlib, SQLite 3 and Python 3; on Debian/Ubuntu also
nostalRE's own packages (see [client/README.md](https://github.com/si-klyde/nostalre#readme)).

1. Clone with the submodules:
   ```sh
   git clone --recurse-submodules https://github.com/si-klyde/nostalre-launcher.git
   cd nostalre-launcher
   ```
2. Put your game files in `game-data/`, laid out like the device's game folder
   ([game-data/README.md](game-data/README.md)).
3. Run:
   ```sh
   scripts/run-nostalre.sh
   ```
   The first run builds the servers and makes a database with a test account (`tester` / `tester`). Every run
   builds the client, starts the servers and the client, and stops the servers when the client quits.
   `scripts/run-servers.sh` runs the servers alone.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Build from source

Needs the Android SDK and NDK (`ANDROID_NDK_HOME`, r30), `cargo-ndk` and the `aarch64-linux-android` Rust target.

```sh
scripts/build-apk.sh          # out/nostalre.apk
adb install -r out/nostalre.apk
```

`--skip-native` reuses the last Android builds of the servers, the bridge and the client. `--release` builds the
public, non-debuggable APK signed with the release key, which the builder keeps in `~/.gradle/gradle.properties`
(see the script); without the key, debug builds use the Android debug key.

### Tables from rAthena

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

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Roadmap

- [x] One Android app with the client and the servers, offline
- [x] Only kRO's 2025 data needed (no older archives)
- [x] Controller in NPC dialogs, deal popups and shops; talk to the nearest NPC
- [x] Release-signed APKs
- [ ] Continuous integration for this repository
- [ ] English text set up inside the app, without a PC
- [ ] Check every archive `DATA.INI` lists while choosing the folder
- [ ] Larger dialogs and shop windows on handhelds
- [ ] No `official_data.grf` needed
- [ ] A desktop launcher

See the [open issues](https://github.com/si-klyde/nostalre-launcher/issues) for known problems.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contributing

Contributions are welcome. Changes to the game itself belong in
[nostalRE](https://github.com/si-klyde/nostalre), server changes in
[rathena-nostalre](https://github.com/si-klyde/rathena-nostalre); this repository takes them as submodules.

1. Fork the project
2. Create a branch (`git checkout -b feat/your-change`)
3. Commit your changes, saying what changed and how you tested it
4. Push the branch (`git push origin feat/your-change`)
5. Open a pull request

Never commit game files, extracted game assets, keys or personal configuration.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## License

GNU General Public License version 3: see [LICENSE](LICENSE). [NOTICE](NOTICE) lists what this builds on.
nostalRE and rAthena keep their own licenses in their repositories; every release names the tagged sources its
APK was built from.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contact

si-klyde: [github.com/si-klyde](https://github.com/si-klyde)

Project link: [https://github.com/si-klyde/nostalre-launcher](https://github.com/si-klyde/nostalre-launcher)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Acknowledgments

* [nostalro-client](https://github.com/nmeylan/nostalro-client) and [rust-ro](https://github.com/nmeylan/rust-ro) by Nicolas Meylan, which nostalRE grew from
* [rAthena](https://github.com/rathena/rathena), the servers
* [roBrowserLegacy](https://github.com/MrAntares/roBrowserLegacy), whose sprite reading and file protocol the hub and bridge follow
* [ROenglishRE](https://github.com/llchrisll/ROenglishRE), the English translation and `official_data.grf`
* [Noto Sans](https://fonts.google.com/noto/specimen/Noto+Sans), the hub's font
* [Best-README-Template](https://github.com/othneildrew/Best-README-Template), this README's layout

AI tools assist with implementation, debugging, tests and documentation here, as in nostalRE. Changes are built,
tested and checked on a device before they are released.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

<!-- MARKDOWN LINKS & IMAGES -->
[release-shield]: https://img.shields.io/github/v/release/si-klyde/nostalre-launcher?style=for-the-badge
[release-url]: https://github.com/si-klyde/nostalre-launcher/releases/latest
[downloads-shield]: https://img.shields.io/github/downloads/si-klyde/nostalre-launcher/total?style=for-the-badge
[issues-shield]: https://img.shields.io/github/issues/si-klyde/nostalre-launcher?style=for-the-badge
[issues-url]: https://github.com/si-klyde/nostalre-launcher/issues
[license-shield]: https://img.shields.io/github/license/si-klyde/nostalre-launcher?style=for-the-badge
[license-url]: https://github.com/si-klyde/nostalre-launcher/blob/main/LICENSE
[android-shield]: https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white
[product-screenshot]: https://raw.githubusercontent.com/si-klyde/nostalre/main/docs/images/android-handheld.webp
[rust-shield]: https://img.shields.io/badge/Rust-000000?style=for-the-badge&logo=rust&logoColor=white
[rust-url]: https://www.rust-lang.org/
[kotlin-shield]: https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white
[kotlin-url]: https://kotlinlang.org/
[cpp-shield]: https://img.shields.io/badge/C%2B%2B-00599C?style=for-the-badge&logo=cplusplus&logoColor=white
[rathena-url]: https://github.com/rathena/rathena
[sqlite-shield]: https://img.shields.io/badge/SQLite-003B57?style=for-the-badge&logo=sqlite&logoColor=white
[sqlite-url]: https://www.sqlite.org/
