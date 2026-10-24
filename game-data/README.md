# Game data

For playing nostalRE on the desktop (`scripts/run-nostalre.sh`), put your own game files here, laid out like the
Android app's game folder. They are not part of this repository and are never committed.

What goes in, where it comes from and what is lost without each piece: see
[Prerequisites](../README.md#prerequisites) and [Installation](../README.md#installation) in the main README. In
short, the kRO 2025-07-16 client folder as it is ([WARP0716](https://github.com/CrazyBebop/WARP0716)'s WARPGATE
downloader fetches it), plus ROenglishRE's `official_data.grf` and the English text:

```
game-data/
├── DATA.INI            optional: 1=data.grf, 2=official_data.grf
├── data.grf
├── official_data.grf
├── System/
├── SystemEN/
├── BGM/
└── data/               kRO's loose files, then the English text over them
```

`scripts/import-english.py <ROenglishRE checkout>` writes the English text into `game-data/data/` by default.
