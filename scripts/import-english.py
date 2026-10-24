#!/usr/bin/env python3
"""Copies the English fan translation (ROenglishRE) into game-data/data/ as loose files.

grf-server serves loose files before GRF contents, so these replace the Korean texts and
UI textures. Only text tables, books, textures and the world map labels are taken (no other Lua,
no map data). The data comes as the translation's ClientGenerator.bat stages a 2022-04-06 Renewal client:
Translation/Renewal, then each dated compatibility folder up to that date (read from git: the clone is
sparse), so art kRO moved after 2017 (the stylist's buttons) is the English one.
The translation has no license: it stays in the git-ignored game-data/ folder.

Item names and descriptions come from the translation's itemInfo.lua (Renewal, overlaid by
Pre-Renewal) and are written as the data/*itemdisplaynametable.txt / *itemdesctable.txt tables
roBrowser reads; an item the translation lacks takes its name from rAthena's Renewal item db (the
newer Renewal items). Resource (sprite) names are left to the GRF.

For nostalre-client (Renewal) it also writes:
- data/luafiles514/lua files/datainfo/iteminfo_en.lub: the Renewal itemInfo.lua compiled with luac5.1
  (skipped without it); the client takes from it the items kRO's own iteminfo lacks
- data/shieldviewtable.txt and headgearviewtable.txt: rAthena's views of shields and headgear, for a
  shield's look and a headgear's icon when iteminfo has neither; data/rathenacardtable.txt: rAthena's
  cards, which take kRO's blank card icon when iteminfo has none
- data/skillnametable.txt, skilldesctable_renewal.txt and leveluseskillspamount.txt from the Renewal skillinfoz
  lubs (write_skill_table)

Usage: import-english.py <ROenglishRE checkout> [game-data dir]
  git clone --depth 1 --filter=blob:none --sparse https://github.com/llchrisll/ROenglishRE.git
  git -C ROenglishRE sparse-checkout set Translation/Renewal/data Translation/Renewal/SystemEN \\
      Translation/Pre-Renewal/SystemEN "Translation/Pre-Renewal/data/lua files/skillinfoz"
"""

import os
import re
import shutil
import subprocess
import sys
import io
import tarfile
import tempfile
from pathlib import Path

KEEP_DIRS = ("texture", "book")
KEEP_EXT = (".txt", ".xml", ".bmp", ".tga", ".jpg", ".png", ".spr", ".act")
# ClientGenerator.bat's dated folders for a client of 2022-04-06, in its order (it skips 2020-01-22)
COMPATIBILITY = ("2017-06-14", "2017-12-13", "2018-06-20", "2019-06-05", "2020-09-02", "2021-10-28", "2022-03-30",
                 "2022-04-06")
RATHENA_RE_DB = Path(__file__).resolve().parent.parent / "server" / "db" / "re"


def lua_table(path, name):
    """Top-level Lua table <name> = {...} as dicts/lists; strings are bytes, [A.B] keys become "B" and A.B values b"B"."""
    data = open(path, "rb").read()
    token = re.compile(rb'\s+|--[^\n]*|"((?:[^"\\]|\\.)*)"|(-?\d+)|([A-Za-z_]\w*)|(.)', re.S)
    tokens = [m for m in token.finditer(data) if m.group(1) is not None or m.group(2) or m.group(3) or m.group(4)]
    pos = 0

    def unescape(raw):
        return re.sub(rb'\\(.)', lambda m: {b"n": b"\n", b"t": b"\t"}.get(m.group(1), m.group(1)), raw)

    def value():
        nonlocal pos
        m = tokens[pos]
        pos += 1
        if m.group(1) is not None:
            return unescape(m.group(1))
        if m.group(2):
            return int(m.group(2))
        if m.group(3):
            if m.group(3) in (b"true", b"false"):
                return m.group(3) == b"true"
            name = m.group(3)
            while tokens[pos].group(4) == b"." and tokens[pos + 1].group(3):  # SKID.NV_BASIC reads as NV_BASIC
                name = tokens[pos + 1].group(3)
                pos += 2
            return name
        if m.group(4) == b"{":
            return table()
        raise ValueError(f"unexpected {m.group(0)!r} at {m.start()}")

    def table():
        nonlocal pos
        result, items = {}, []
        while tokens[pos].group(4) != b"}":
            if tokens[pos].group(4) == b"[":  # [key] = value, key may be a constant like SKID.NV_BASIC
                pos += 1
                parts = []
                while tokens[pos].group(4) != b"]":
                    parts.append(tokens[pos])
                    pos += 1
                pos += 2  # ] =
                last = parts[-1]
                key = int(last.group(2)) if last.group(2) else last.group(1) if last.group(1) is not None else last.group(3).decode()
                result[key] = value()
            elif tokens[pos].group(3) and tokens[pos + 1].group(4) == b"=":
                key = tokens[pos].group(3).decode()
                pos += 2
                result[key] = value()
            else:
                items.append(value())
            if tokens[pos].group(4) in (b",", b";"):  # Lua accepts both separators
                pos += 1
        pos += 1
        return result if result else items

    while not (tokens[pos].group(3) == name.encode() and tokens[pos + 1].group(4) == b"="):
        pos += 1
    pos += 3  # name = {
    return table()


def rathena_item_names():
    """Id -> English name of every item in rAthena's Renewal item db, as bytes like the translation's."""
    names = {}
    for path in sorted(RATHENA_RE_DB.glob("item_db_*.yml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for item_id, name in re.findall(r"^  - Id: (\d+)[^\n]*\n(?:    .*\n)*?    Name: (.+)$", text, re.M):
            name = name.split("#")[0].strip().strip('"')
            names[int(item_id)] = name.encode("cp949", errors="replace")
    return names


def write_rathena_tables(data_dir):
    """data/shieldviewtable.txt and headgearviewtable.txt: the view of every shield and headgear in rAthena's Renewal
    item db (id#view#); data/rathenacardtable.txt: its cards (id#). nostalre-client takes them for the items its
    iteminfo lacks or gives no ClassNum (those the translation took from other servers, and the newer ones): a
    shield's look, a headgear's icon, a card's icon."""
    tables = {"shieldviewtable.txt": {}, "headgearviewtable.txt": {}}
    cards = []
    for path in sorted(RATHENA_RE_DB.glob("item_db_*.yml")):
        for entry in path.read_text(encoding="utf-8", errors="replace").split("\n  - Id: ")[1:]:
            item_id = int(entry.split("\n", 1)[0].split()[0])
            if re.search(r"^    Type: Card$", entry, re.M):
                cards.append(item_id)
            view = re.search(r"^    View: (\d+)$", entry, re.M)
            if not view or not re.search(r"^    Type: Armor$", entry, re.M):
                continue
            if re.search(r"^      Left_Hand: true$", entry, re.M):
                tables["shieldviewtable.txt"][item_id] = int(view.group(1))
            elif re.search(r"^      (Costume_)?Head_(Top|Mid|Low): true$", entry, re.M):
                tables["headgearviewtable.txt"][item_id] = int(view.group(1))
    for name, views in tables.items():
        with open(Path(data_dir) / name, "w") as out:
            for item_id in sorted(views):
                out.write(f"{item_id}#{views[item_id]}#\n")
        print(f"wrote {name} ({len(views)} items)")
    with open(Path(data_dir) / "rathenacardtable.txt", "w") as out:
        out.writelines(f"{item_id}#\n" for item_id in sorted(cards))
    print(f"wrote rathenacardtable.txt ({len(cards)} cards)")


def write_item_info_lub(checkout, data_dir):
    """data/luafiles514/lua files/datainfo/iteminfo_en.lub from the Renewal itemInfo.lua, with luac5.1 (Lua 5.1
    bytecode is what the client reads)."""
    source = Path(checkout) / "Translation" / "Renewal" / "SystemEN" / "LuaFiles514" / "itemInfo.lua"
    luac = shutil.which("luac5.1")
    if not source.exists() or not luac:
        print("no Renewal itemInfo.lua or luac5.1, iteminfo_en.lub skipped")
        return
    target = Path(data_dir) / "luafiles514" / "lua files" / "datainfo" / "iteminfo_en.lub"
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([luac, "-s", "-o", str(target), str(source)], check=True)
    print(f"compiled {target.name}")


def write_item_tables(checkout, data_dir):
    """data/*itemdisplaynametable.txt and *itemdesctable.txt from the English itemInfo.lua files."""
    items = {}
    for mode in ("Renewal", "Pre-Renewal"):  # the server is pre-renewal: its texts win
        path = Path(checkout) / "Translation" / mode / "SystemEN" / "LuaFiles514" / "itemInfo.lua"
        if path.exists():
            items.update(lua_table(path, "tbl"))
    if not items:
        print("no itemInfo.lua found, item tables skipped")
        return
    # the server knows items newer than the translation (Renewal ids above 65535): their rAthena names
    added = 0
    for item_id, name in rathena_item_names().items():
        if item_id not in items:
            items[item_id] = {"identifiedDisplayName": name, "unidentifiedDisplayName": name}
            added += 1
    print(f"{added} item names from rAthena's item db")

    def line(text):
        return text.replace(b"#", b"_")

    tables = {
        "idnum2itemdisplaynametable.txt": lambda i, it: line(it.get("identifiedDisplayName", b"")) + b"#",
        "num2itemdisplaynametable.txt": lambda i, it: line(it.get("unidentifiedDisplayName", b"")) + b"#",
        "idnum2itemdesctable.txt": lambda i, it: b"\n" + b"\n".join(line(d) for d in it.get("identifiedDescriptionName", [])) + b"\n#",
        "num2itemdesctable.txt": lambda i, it: b"\n" + b"\n".join(line(d) for d in it.get("unidentifiedDescriptionName", [])) + b"\n#",
    }
    for name, fmt in tables.items():
        with open(Path(data_dir) / name, "wb") as out:
            for item_id in sorted(i for i in items if isinstance(i, int)):
                out.write(str(item_id).encode() + b"#" + fmt(item_id, items[item_id]) + b"\n")
    print(f"wrote item tables for {len(items)} items")


def rathena_skills():
    """Aegis name -> (English name as bytes, SP per level) of every skill in rAthena's Renewal skill db."""
    import yaml

    skills = {}
    for entry in yaml.safe_load(open(RATHENA_RE_DB / "skill_db.yml"))["Body"]:
        levels = range(1, entry.get("MaxLevel", 1) + 1)
        cost = (entry.get("Requires") or {}).get("SpCost", 0)
        per_level = {e["Level"]: e["Amount"] for e in cost} if isinstance(cost, list) else {}
        sp = [per_level.get(level, 0) if per_level else cost for level in levels]
        skills[entry["Name"]] = (entry.get("Description", entry["Name"]).encode("cp949", errors="replace"), sp)
    return skills


def write_skill_table(checkout, data_dir):
    """The skill tables nostalre-client reads, from the English skillinfoz lubs (Renewal over Pre-Renewal):
    - data/skillnametable.txt (code#Name#): the Renewal SkillName, else the Pre-Renewal table's, else rAthena's
      description (the server's own sub-skills, `DK_SERVANTWEAPON_ATK`, have no name in kRO's tables);
    - data/skilldesctable_renewal.txt (code#lines#): the Renewal descriptions, else the Pre-Renewal ones;
    - data/leveluseskillspamount.txt (code#, SP per level#, @): the skills kRO lets pick a level (`bSeperateLv`),
      with the SP rAthena charges at each (kRO's own table disagrees for 45 of them).
    data/skilldesctable.txt stays the Pre-Renewal descriptions: roBrowser (the Classic server) reads it."""
    pre = Path(checkout) / "Translation" / "Pre-Renewal" / "data"
    renewal = Path(checkout) / "Translation" / "Renewal" / "data" / "luafiles514" / "lua files" / "skillinfoz"
    pre_descriptions = pre / "lua files" / "skillinfoz" / "skilldescript.lub"
    if not pre_descriptions.exists() or not (renewal / "skillinfolist.lub").exists():
        print("no skilldescript.lub or Renewal skillinfolist.lub found, skill tables skipped")
        return

    def write_descriptions(name, skills):
        with open(Path(data_dir) / name, "wb") as out:
            for code, lines in skills.items():
                out.write(code.encode() + b"#\n" + b"\n".join(l.replace(b"#", b"_") for l in lines) + b"\n#\n")
        print(f"wrote {name} ({len(skills)} skills)")

    classic = lua_table(pre_descriptions, "SKILL_DESCRIPT")
    write_descriptions("skilldesctable.txt", classic)
    write_descriptions("skilldesctable_renewal.txt", {**classic, **lua_table(renewal / "skilldescript.lub", "SKILL_DESCRIPT")})

    info = lua_table(renewal / "skillinfolist.lub", "SKILL_INFO_LIST")
    server = rathena_skills()
    names = {code: name for code, (name, _) in server.items()}
    old_names = pre / "skillnametable.txt"
    if old_names.exists():
        for line in old_names.read_bytes().splitlines():
            parts = line.split(b"#")
            if len(parts) >= 2 and parts[0].strip() and parts[1].strip():
                names[parts[0].strip().decode()] = parts[1].strip().replace(b"_", b" ")
    names.update({code: entry["SkillName"] for code, entry in info.items() if entry.get("SkillName")})
    with open(Path(data_dir) / "skillnametable.txt", "wb") as out:
        for code in sorted(names):
            out.write(code.encode() + b"#" + names[code].replace(b"#", b"_").replace(b" ", b"_") + b"#\n")
    print(f"wrote skillnametable.txt ({len(names)} skills)")

    with open(Path(data_dir) / "leveluseskillspamount.txt", "wb") as out:
        picked = 0
        for code, entry in info.items():
            if not entry.get("bSeperateLv"):
                continue
            sp = server[code][1] if code in server else entry.get("SpAmount", [])
            out.write(code.encode() + b"#\n" + b"".join(f"{amount}#\n".encode() for amount in sp) + b"@\n")
            picked += 1
    print(f"wrote leveluseskillspamount.txt ({picked} skills)")


def copy_data(source, target):
    """The files of a translation `data` folder the client takes, copied into `target`; how many."""
    root = str(target.resolve())
    copied = 0
    for path in sorted(source.rglob("*")):
        rel = path.relative_to(source)
        top_level_text = len(rel.parts) == 1 and rel.suffix.lower() in (".txt", ".xml")
        # roBrowser always loads the world map labels through its Lua engine
        world_map = [p.lower() for p in rel.parts[:3]] == ["luafiles514", "lua files", "worldviewdata"]
        if not path.is_file() or not (top_level_text or world_map or (rel.parts[0] in KEEP_DIRS and rel.suffix.lower() in KEEP_EXT)):
            continue

        # Korean names stay spelled as the repo has them (CP949 bytes read as Latin-1, in UTF-8): Android's
        # shared storage only takes UTF-8 names, grf-server turns them back into the GRF's bytes
        dest = os.path.join(root, *rel.parts)
        if not os.path.realpath(dest).startswith(root + "/"):
            raise SystemExit(f"refusing path outside {target}: {rel}")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.copyfile(path, dest)
        copied += 1
    return copied


def copy_compatibility(checkout, target):
    """Each dated compatibility folder's `data` over the Renewal one, as ClientGenerator.bat's :CopyFD takes it (the
    date's Renewal/ when it has one); from git, the sparse clone holds none of them on disk."""
    copied = 0
    with tempfile.TemporaryDirectory() as tmp:
        for date in COMPATIBILITY:
            folder = f"Translation/Compatibility/{date}"
            archive = subprocess.run(["git", "-C", str(checkout), "archive", "HEAD", folder], capture_output=True,
                                     check=True).stdout
            with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
                tar.extractall(tmp, filter="data")
            dated = Path(tmp) / folder
            data = (dated / "Renewal" if (dated / "Renewal").is_dir() else dated) / "data"
            if data.is_dir():
                copied += copy_data(data, target)
    return copied


def main():
    checkout = Path(sys.argv[1])
    target = Path(sys.argv[2] if len(sys.argv) > 2 else Path(__file__).resolve().parent.parent / "game-data") / "data"
    copied = copy_data(checkout / "Translation" / "Renewal" / "data", target)
    copied += copy_compatibility(checkout, target)
    print(f"copied {copied} files into {target}")
    write_item_tables(sys.argv[1], target)
    write_rathena_tables(target)
    write_item_info_lub(sys.argv[1], target)
    write_skill_table(sys.argv[1], target)


if __name__ == "__main__":
    main()
