# Game data

For playing nostalRE on the desktop (`scripts/run-nostalre.sh`), put your own game files here. They are not part
of this repository and are never committed. On Android the app asks for the same folder on the device instead.

What nostalRE reads:

- `DATA.INI` with the GRFs it lists, in that order (the first archive holding a file wins):
  - kRO's 2025-07-16 full client `data.grf` (GRF version 0x300, unencrypted), nostalRE's packet version
  - for now also ROenglishRE's `official_data.grf`, the art of other servers' monsters, NPCs and items that rAthena's
    database uses and kRO lacks (https://llchrisll.github.io/ROTPDocs/misc/custom_officials/)

  ```ini
  [Data]
  1=data.grf
  2=official_data.grf
  ```
- `BGM/*.mp3`, optional
- `data/`, optional loose files over the GRFs: the English text written by `scripts/import-english.py` from a
  ROenglishRE checkout (needs PyYAML, and `luac5.1` for the English item table)
