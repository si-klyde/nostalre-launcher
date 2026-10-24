"""Reading rAthena's data for the table generators (skill-enum.py, item-tables.py, job-tables.py, skill-tree.py)."""

import re

import yaml

# libyaml's loader when PyYAML has it: the item db takes 2 s instead of 13
_LOADER = getattr(yaml, "CSafeLoader", yaml.SafeLoader)


def load_body(path):
    """The `Body` of one of rAthena's YAML databases."""
    with open(path) as f:
        return yaml.load(f, Loader=_LOADER)["Body"]


def parse_enum(header, opening, prefix, stop="};"):
    """NAME -> value of the C++ enum in `header` that starts at `opening`, up to `stop`: entries named `prefix...`,
    each the one before plus one unless it says `= N`, `= 0xN` or `= OTHER_NAME`."""
    text = header.read_text()
    # the declaration, not a comment naming the enum ("@see enum e_skill_unit_id")
    start = re.search(rf"{re.escape(opening)}\b[^;{{/]*\{{", text).end()
    body = text[start : text.index(stop, start)]
    values, value = {}, -1
    for line in body.splitlines():
        # every entry counts, whatever its name: one off the prefix moves the next value too
        m = re.match(r"\s*([A-Za-z_]\w*)\s*(?:=\s*(\w+))?\s*,?\s*(?://.*)?$", line)
        if not m:
            continue
        given = m.group(2)
        if given is None:
            value += 1
        elif given[0].isdigit():
            value = int(given, 0)
        else:
            value = values[given]
        values[m.group(1)] = value
    return {name: v for name, v in values.items() if name.startswith(prefix)}


def skill_variant(name):
    """A skill's `SkillEnum` variant: its aegis name in PascalCase (NV_BASIC is NvBasic)."""
    return "".join(part.capitalize() for part in name.lower().split("_") if part)
