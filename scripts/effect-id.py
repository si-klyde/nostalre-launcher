#!/usr/bin/env python3
"""nostalre-client's `EffectId` (rust-ro/models/src/enums/effect_id.rs) from rAthena's `e_special_effects`.

The server's effect ids (0x01f3, 0x0284) and a map's effect objects (.rsw) are rAthena's numbers, so every entry of
its enum keeps its own. The client's own effects that enum lacks (level up, footprints, cast circles, the code
effects roBrowser numbers from 1000) keep their names but move to INTERNAL + their old number, where no server id
reaches them: at their old numbers, rAthena's 4th-job effects (708 and up) would have drawn them.

Usage: effect-id.py [rathena dir] [output file]
"""

import re
import sys
from collections import Counter
from pathlib import Path

from kro_ids import INTERNAL, code_effects, prim_ported

ROOT = Path(__file__).resolve().parent.parent
RATHENA = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "server"
OUT = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "client" / "rust-ro" / "models" / "src" / "enums" / "effect_id.rs"

# The client's own effects, by the numbers they had in rust-ro's enum (roBrowser's EffectConst); from 3000 nostalRE's,
# drawn from a rule (body effects: a Rune Knight's shields, a Soul Reaper's souls, the user's own)
CLIENT_EFFECTS = """
703 EF_FOOT5
704 EF_FOOT6
705 EF_AIRTEXTURE2
706 EF_AIRTEXTURE3
707 EF_AIRTEXTURE4
1000 EF_CODE_EFFECT_BEGIN EF_MAKEBLUR3 EF_MAKEBLUR4 EF_BLOOD_FLY EF_HIT7 EF_TEIHIT1REVERSE EF_TEIHIT2REVERSE
     EF_MAKEBLUR5 EF_ENCHANTPOISON_FLOW EF_ARROW_YELLOW EF_ARROW_RED EF_SIGHT3 EF_TEIHIT3REVERSE EF_BEGINSPELLWHITE
     EF_BEGINSPELL8 EF_ENDURE_ZHAN EF_ENDURE_SOU EF_ENDURE_SHAN EF_ENDURE_JING EF_WIND_BUFF EF_BEGINSPELLRED
     EF_GREEN_POP EF_BEGINSPELL_N EF_ARROW_DOWN EF_CODE_EFFECT_END
1030 EF_PROCESS2_BEGIN EF_TEXTURE_FALLING EF_SPHEREWIND3 EF_PROCESS2_END
1100 EF_FILE_EFFECT_BEGIN EF_SHAKE EF_LEVELUP EF_JOBLEVELUP EF_NPCDEAD EF_CLAW_ATK EF_SWORD_LIGHT EF_RING4 EF_HIT8
     EF_CAST_MAGIC_RED EF_CAST_MAGIC_RED2 EF_CAST_MAGIC_BLUE EF_CAST_MAGIC_BLUE2 EF_CAST_MAGIC_WHITE
     EF_CAST_MAGIC_WHITE2 EF_CAST_MAGIC_YELLOW EF_CAST_MAGIC_YELLOW2 EF_FLAMMULE EF_BLINGLINE EF_BLINGLINE2
     EF_GROUNDIMAGE EF_GROUNDIMAGE3 EF_GROUNDIMAGE5 EF_GROUNDIMAGE7 EF_GROUNDIMAGE9
1200 EF_CODE2_EFFECT_BEGIN EF_CASTFLOWER EF_ROTATEFLOWER EF_FLYUP EF_ACTOR_COLOR EF_LIGHT_SWORD EF_LIGHT_BODY
     EF_LIGHT_RIDE EF_PRINT_FOOT EF_COLOR_SWORD EF_COLOR_BODY EF_COLOR_RIDE EF_MOVE_TO_SPRITE EF_GET_ITEM
     EF_LIGHT_ROLESHIELD EF_LIGHT_HEAD1 EF_LIGHT_HEAD2 EF_LIGHT_HEAD3 EF_COLOR_HEAD1 EF_COLOR_HEAD2 EF_COLOR_HEAD3
1300 EF_CODE_EFFECT_BEGIN2 EF_RIPPLE_YELLOW EF_RIPPLE_BLACKK EF_RIPPLE_WHITE EF_RIPPLE_RED EF_RIPPLE_PURPLE
     EF_AGGREGATION_YELLOW EF_AGGREGATION_BLACKK EF_AGGREGATION_WHITE EF_AGGREGATION_RED EF_AGGREGATION_PURPLE
     EF_CODE_EFFECT_END2
2000 EF_TEST_EFFECT_BEGIN EF_SELECTRING EF_TESTEFFECT EF_TESTBODYLIGHT EF_ZOOM_IN EF_ZOOM_OUT EF_BLOW_LINE
     EF_LIGHT_SHIELD EF_TYPING EF_SMATK1 EF_SMATK2 EF_SMATK3 EF_SMATK4 EF_SMDEF EF_MGATTACK1 EF_MGATTACK2 EF_ALATTACK1
     EF_ALATTACK2 EF_ALATTACK3 EF_ALATTACK4 EF_ALDEF2 EF_ALDEF3 EF_MGDEF1 EF_MGDEF2 EF_MGDEF3 EF_MGDEF4 EF_DEVIL_RED
     EF_DECAGILITYBUF
3000 EF_BODY_AURA
"""


def server_effects():
    """(name, number) of every entry of rAthena's enum, without EF_NONE and EF_MAX."""
    source = (RATHENA / "src" / "map" / "script.hpp").read_text()
    body = source[source.index("enum e_special_effects") :]
    body = body[: body.index("};")]
    effects, number = [], -1
    for name, value in re.findall(r"^\s*(EF_\w+)\s*(?:=\s*(-?\d+))?\s*,?\s*$", body, re.M):
        number = int(value) if value else number + 1
        if name not in ("EF_NONE", "EF_MAX"):
            effects.append((name, number))
    return effects


# kRO's effects rAthena's enum does not name (past its end, and gaps within it) that nostalRE plays, at kRO's own
# numbers (the server's id space: rAthena names them as it reaches them), named by number: the exe registers them by
# id alone. They are the STR classes of the client's `kro_str` table; the ones rAthena's enum already has keep
# its names
KRO_STR_TABLE = ROOT / "client" / "lib" / "effects" / "src" / "kro_str" / "generated.rs"
# and kRO's older effects its primitive port draws and the classes its code ports (scripts/kro_ids.py)


def kro_effects(named):
    ids = sorted({int(n) for n in re.findall(r"StrClass\s*\{\s*id:\s*(\d+),", KRO_STR_TABLE.read_text())}
                 | prim_ported() | code_effects())
    return [(f"EF_KRO_{number}", number) for number in ids if number not in named]


def client_effects():
    effects = []
    for block in re.split(r"\n(?=\d)", CLIENT_EFFECTS.strip()):
        start, *names = block.split()
        effects += [(name, INTERNAL + int(start) + i) for i, name in enumerate(names)]
    return effects


def variant(name):
    pascal = "".join(part.capitalize() for part in name[3:].lower().split("_") if part)
    return "Ef" + pascal if pascal[0].isdigit() else pascal


def main():
    server = server_effects()
    effects = server + kro_effects({number for _, number in server}) + client_effects()
    for what, seen in (("name", [n for n, _ in effects]), ("variant", [variant(n) for n, _ in effects]), ("number", [v for _, v in effects])):
        twice = [k for k, c in Counter(seen).items() if c > 1]
        if twice:
            sys.exit(f"one {what} twice: {twice}")
    lines = [
        "// Generated by scripts/effect-id.py from rAthena's `e_special_effects` (src/map/script.hpp), then the",
        f"// kRO's own effects past it that our tables play, then the client's own from {INTERNAL}; do not edit.",
        "#![allow(dead_code, non_camel_case_types, clippy::enum_clike_unportable_variant)]",
        "",
        "use crate::enums::*;",
        "",
        "#[derive(WithNumberValue, WithStringValue, Debug, Copy, Clone, PartialEq, Eq, Hash)]",
        "pub enum EffectId {",
    ]
    for name, number in effects:
        lines += [f"    /// `{name}`", f'    #[value_string = "{name}"]', f"    #[value = {number}]", f"    {variant(name)},"]
    lines += ["}", "", "impl EffectId {", f"    pub const ALL: [EffectId; {len(effects)}] = ["]
    lines += [f"        Self::{variant(name)}," for name, _ in effects]
    lines += ["    ];", "}", ""]
    OUT.write_text("\n".join(lines))
    print(f"wrote {OUT} ({len(effects)} effects, {len(client_effects())} the client's own)")


if __name__ == "__main__":
    main()
