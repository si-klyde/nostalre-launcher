"""The kRO effect ids nostalRE plays past its own tables, read from the client's source so no list is typed twice:
the classes its code ports by hand (the `Kro<id>` its class files name: lib/effects/src/effects/kro_classes.rs,
kro_spheres.rs, spr_aliases.rs) and the older effects its primitive port draws (kro_prim/generated.rs).
`effect-id.py` names them in EffectId (`EF_KRO_<id>`), and skills' uses route to them."""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EFFECTS = ROOT / "client" / "lib" / "effects" / "src"
# the files that play kRO's classes by hand
CODE_FILES = ("effects/kro_classes.rs", "effects/kro_spheres.rs", "spr_aliases.rs")
# EffectId's first own number (effect-id.py): the client's own effects from here, past every server id
INTERNAL = 10000


def code_effects():
    """The ids of kRO's classes ported by hand"""
    return {int(n) for f in CODE_FILES for n in re.findall(r"\bKro(\d+)\b", (EFFECTS / f).read_text())}


def prim_ported():
    """The ids the primitive port draws"""
    return {int(n) for n in re.findall(r"EffectRecipe\s*\{\s*id:\s*(\d+),",
                                       (EFFECTS / "kro_prim" / "generated.rs").read_text())}
