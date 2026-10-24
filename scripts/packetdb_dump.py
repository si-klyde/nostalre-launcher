#!/usr/bin/env python3
"""rAthena's packet table at one packet version, for nostalRE (scripts/packetdb-dump.sh runs it).

Compiles small programs against rAthena's headers with the map server's own flags and writes:
- <out>: every id the servers send (length from the packet's struct, -1 variable) and every id they parse (length
  and field offsets from clif_packetdb.hpp and clif_shuffle.hpp);
- <out with -layout.tsv>: the fields of each struct those ids are sent or parsed with, flattened as the packet crate's
  `layout()` is (nested structs as `outer.inner`, arrays of structs per element, an open array's first element as
  `list[]`), with their offsets and sizes as the compiler lays them out.

Usage: packetdb_dump.py RATHENA_SRC BUILD_NINJA WORK_DIR OUT_TSV PACKETVER
"""
import os, re, subprocess, sys

src, ninja, work, out, packetver = sys.argv[1:]

# the compile flags of clif.cpp, as the map server was built with them
text = open(ninja).read()
block = text[text.index("map-server.dir/clif.cpp.o:"):]
block = block[:block.index("\n\n")]
flags = []
for key in ("DEFINES", "FLAGS", "INCLUDES"):
    m = re.search(rf"^\s*{key} = (.*)$", block, re.M)
    if m:
        flags += m.group(1).split()
flags = [f for f in flags if not f.startswith("-O")]
cxx = ["g++", "-w"] + flags

# which headers the servers send: the struct-per-header packets of one version can name an id twice (an old and a
# new layout); the one the sources use wins
sources = ""
for d in ("map", "char", "login"):
    for f in os.listdir(os.path.join(src, d)):
        if f.endswith(".cpp"):
            sources += open(os.path.join(src, d, f), errors="replace").read()

def split_top(body, sep):
    """Split at `sep` outside braces and brackets."""
    parts, depth, cur = [], 0, ""
    for ch in body:
        depth += (ch in "{[") - (ch in "}]")
        if ch == sep and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    return parts + [cur]

def parse_members(owner, body, structs):
    """(type, name, array) per member: array None, "" for an open array, else its size expression. Anonymous inner
    structs are registered as `owner.name`. None when the struct holds bit fields or several names in one
    declaration: it is then one opaque field."""
    members = []
    for decl in (d.strip() for d in split_top(body, ";")):
        if not decl:
            continue
        inner = re.fullmatch(r"(?:struct|union)\s*\{(.*)\}\s*(\w+)\s*(?:\[([^\]]*)\])?", decl, re.S)
        if inner:
            type_ = f"{owner}.{inner.group(2)}"
            structs[type_] = (False, False, parse_members(type_, inner.group(1), structs))
            members.append((type_, inner.group(2), inner.group(3)))
            continue
        m = re.fullmatch(r"(?:const\s+)?(?:struct\s+)?([\w\s]*?\w)\s+(\w+)\s*((?:\[[^\]]*\])*)", decl)
        if not m or ":" in decl or "," in decl:
            return None
        dims = re.findall(r"\[([^\]]*)\]", m.group(3))  # names[10][24] is one array field
        members.append((m.group(1).split()[-1], m.group(2), dims[0] if dims else None))
    return members

opaque = set()  # packet structs parse_members cannot lay out: a packet's layout is then one field, the checks see less
# the ones that are right to be opaque: bit fields
OPAQUE_OK = {"EQUIPITEM_INFO.Flag", "NORMALITEM_INFO.Flag", "packet_maptypeproperty2.flag"}

def structs_of(pre):
    """Structs of preprocessed headers -> (variable length: the second field is the packet length, open-ended: the
    last field is an array without a size or, in a variable packet, an array of structs cut to length, members)."""
    structs = {}
    for m in re.finditer(r"\bstruct (\w+)\s*\{", pre):
        depth, i = 1, m.end()
        while depth and i < len(pre):
            depth += (pre[i] == "{") - (pre[i] == "}")
            i += 1
        if not re.match(r"\s*(?:__attribute__\(\(packed\)\))?\s*;", pre[i:]):
            continue
        name, body = m.group(1), pre[m.end():i - 1]
        decls = [d.strip() for d in split_top(body, ";") if d.strip()]
        second = decls[1].split()[-1] if len(decls) > 1 else ""
        variable = bool(re.fullmatch(r"(?i)packet_?(length|len|size)", second))
        structs[name] = (variable, False, parse_members(name, body, structs))
    for name, (variable, _, members) in list(structs.items()):
        last = members[-1] if members else None
        open_ended = last is not None and (last[2] == "" or (variable and last[2] is not None and last[0] in structs))
        structs[name] = (variable, open_ended, members)
    todo = [n for n in structs if n.lower().startswith("packet_")]
    seen = set(todo)
    while todo:
        members = structs[todo.pop()][2]
        if members is None:
            continue
        for type_ in (t for t, _, _ in members if t in structs and t not in seen):
            seen.add(type_)
            todo.append(type_)
    opaque.update(n for n in seen if structs[n][2] is None)
    return structs

def preprocessed_clif():
    """clif.cpp as compiled at this version (#if branches resolved), with the packet reads and writes renamed so
    they survive preprocessing: RFIFOB(fd, x) stays PKTREAD_B(fd, x), RFIFOP stays PKTPTR, WFIFOW and WBUFW stay
    PKTWRITE_W and PKTBUF_W."""
    text = open(os.path.join(src, "map", "clif.cpp"), errors="replace").read()
    text = re.sub(r"\bRFIFO([BWLQ])\(", r"PKTREAD_\1(", text).replace("RFIFOP(", "PKTPTR(")
    text = re.sub(r"\bWFIFOW\(", "PKTWRITE_W(", text)
    text = re.sub(r"\bWBUFW\(", "PKTBUF_W(", text)
    copy = os.path.join(work, "clif-reads.cpp")
    open(copy, "w").write(text)
    out = subprocess.run(cxx + ["-iquote", os.path.join(src, "map"), "-E", copy], capture_output=True, text=True,
                         check=True).stdout
    # only clif.cpp's own lines (the line markers name the file each run of lines comes from), not its headers
    own, lines = False, []
    for line in out.splitlines():
        marker = re.match(r'# \d+ "([^"]*)"', line)
        if marker:
            own = marker.group(1) == copy
        elif own:
            lines.append(line)
    return "\n".join(lines)

def parse_functions(text):
    """The map server's parse functions: name -> (the struct it reads the packet as, if it casts RFIFOP(fd, 0); the
    width it reads at each of its packet_db offsets, {pos index: bytes}, from RFIFOB/W/L/Q)."""
    cast = re.compile(r"(?:reinterpret_cast\s*<\s*(?:const\s+)?(?:struct\s+)?(\w+)\s*\*\s*>\s*\(\s*PKTPTR\(\s*fd\s*,\s*0\s*\)"
                      r"|\(\s*(?:const\s+)?(?:struct\s+)?(\w+)\s*\*\s*\)\s*PKTPTR\(\s*fd\s*,\s*0\s*\))")
    read = re.compile(r"PKTREAD_([BWLQ])\(\s*fd\s*,\s*(?:info->|packet_db\[[^\]]*\]\.)pos\[(\d+)\]\s*\)")
    width = {"B": 1, "W": 2, "L": 4, "Q": 8}
    functions = {}
    for m in re.finditer(r"\nvoid\s+(clif_parse_\w+)\s*\([^)]*\)\s*\{", text):
        body = text[m.end():text.find("\n}", m.end())]
        c = cast.search(body)
        reads = {}
        for w, i in read.findall(body):
            reads.setdefault(int(i), width[w])
        functions[m.group(1)] = (c and (c.group(1) or c.group(2)), reads)
    return functions

def layout_code(top, structs, tag, id_expr, members=None, path="", adjust="0", label="", args=(), depth=0):
    """C++ printing one `lay` row per leaf field of struct `top`: tag, id, flattened name, offset, size, is array.
    Offsets follow the member path from `top` (inner structs may be anonymous); `adjust` adds the element offsets
    of the arrays the path runs through. In a variable-length packet an array of structs is a list cut to length
    (declared with its maximum count): only its first element is laid out, as `list[]`."""
    code = []
    variable = structs[top][0]
    t = f"struct {top}"
    for type_, name, array in (structs[top][2] if members is None else members):
        member = f"{path}{name}"
        inner = structs.get(type_, (None, None, None))[2]
        if inner is not None and array is None:
            code += layout_code(top, structs, tag, id_expr, inner, f"{member}.", adjust, f"{label}{name}.", args, depth)
        elif inner is not None and (array == "" or variable):
            code += layout_code(top, structs, tag, id_expr, inner, f"{member}[0].", adjust, f"{label}{name}[].",
                                args, depth)
        elif inner is not None:
            i = f"i{depth}"
            element = f"sizeof((({t}*)0)->{member}[0])"
            code.append(f"for (int {i} = 0; {i} < (int)(sizeof((({t}*)0)->{member}) / {element}); {i}++) {{\n")
            code += layout_code(top, structs, tag, id_expr, inner, f"{member}[0].", f"{adjust} + {i} * {element}",
                                f"{label}{name}[%d].", args + (i,), depth + 1)
            code.append("}\n")
        else:
            size = "0" if array == "" else f"sizeof((({t}*)0)->{member})"
            fmt = f"lay\\t{tag}\\t%04x\\t{label}{name}\\t%d\\t%d\\t{0 if array is None else 1}\\n"
            values = ", ".join([id_expr, *args, f"(int)(offsetof({t}, {member}) + {adjust})", f"(int)({size})"])
            code.append(f'printf("{fmt}", {values});\n')
    return code

def struct_for_type(t, structs, aliases):
    """The struct sent with packet type constant t: by name, else the type of the variable `= t;` is assigned to (its
    nearest declaration, through `#define PACKET_X PACKET_X2` aliases), else the PACKET_ struct named last before."""
    base = t[:-4] if t.endswith("Type") else t
    for c in ("packet_" + base, "PACKET_" + base.upper()):
        if c in structs:
            return c
    # `= t;`, or t among others (`= type == MAIL_ATT_ZENY ? rodexgetzeny : rodexgetitem;`)
    for m in re.finditer(rf"(\w+)\s*(?:\.|->)\s*\w+\s*=(?!=)[^;]*\b{t}\b[^;]*;", sources):
        before = sources[max(0, m.start() - 6000):m.start()]
        declared = re.findall(rf"\b(?:struct\s+)?(\w+)\s*\*?\s*{m.group(1)}\b\s*(?:[;={{(]|\[)", before)
        known = [aliases.get(d, d) for d in declared if aliases.get(d, d) in structs]
        if known:
            return known[-1]
    for m in re.finditer(rf"=\s*{t}\s*;", sources):
        before = re.findall(r"\b(PACKET_\w+|packet_\w+)\b", sources[max(0, m.start() - 600):m.start()])
        if before and aliases.get(before[-1], before[-1]) in structs:
            return aliases.get(before[-1], before[-1])
    return None

def length_expr(struct, structs):
    """Wire length, and the struct size of a variable packet that always has one size (it declares its length but
    ends in no open array: rAthena sends sizeof)."""
    variable, open_ended, _ = structs[struct]
    size = f"(int)sizeof(struct {struct})"
    if not variable:
        return f"{size}, -1"
    return f"-1, {'-1' if open_ended else size}"

def program(header_file, with_db):
    """C++ that prints the ids, lengths and names of one header set (map and common share an include guard)."""
    include = f'#include "{header_file}"\n'
    # -P: no line markers, which land inside a struct after a long skipped #if branch and hide its members
    pre = subprocess.run(cxx + ["-E", "-P", "-x", "c++", "-"], input=include, capture_output=True, text=True,
                         check=True).stdout
    structs = structs_of(pre)
    macros = subprocess.run(cxx + ["-E", "-dM", "-x", "c++", "-"], input=include, capture_output=True, text=True,
                            check=True).stdout
    # one level of `#define PACKET_X PACKET_Y` (all rAthena has: no chains, typedefs or `struct` in the macro)
    aliases = dict(re.findall(r"^#define ((?i:packet_)\w+) ((?i:packet_)\w+)$", macros, re.M))
    lines = [include, "#include <cstdio>\n", "#include <cstddef>\n"]
    if with_db:
        lines += ["static void db(int id, int len, const char* name, const char* func, const char* offs)"
                  ' { printf("db\\t%04x\\t%d\\t%s\\t%s\\t%s\\n", id & 0xffff, len, name, func, offs); }\n',
                  "#define packetdb_addpacket(cmd, len, func, ...) db(cmd, len, #cmd, #func, #__VA_ARGS__)\n"]
    lines.append("int main() {\n")
    if with_db:
        lines += ['#include "map/clif_packetdb.hpp"\n', '#include "map/clif_shuffle.hpp"\n']
    for n in re.findall(r"const int16 HEADER_(\w+) = ", pre):
        s = "PACKET_" + n
        used = 1 if re.search(rf"\bHEADER_{n}\b", compiled) else 0
        length = length_expr(s, structs) if s in structs else "-2, -1"
        lines.append(f'printf("hdr\\t%04x\\t%d\\t%d\\t{n}\\t{used}\\n", HEADER_{n} & 0xffff, {length});\n')
        if s in structs and structs[s][2] is not None:
            lines += layout_code(s, structs, n, f"HEADER_{n} & 0xffff")
    if with_db:
        for func, (struct, _) in functions.items():
            if struct in structs and structs[struct][2] is not None:
                lines += layout_code(struct, structs, f"fn:{func}", "0")
    if "enum packet_headers" in pre:
        enum = pre[pre.index("enum packet_headers"):]
        # every name, not only the `...Type` ones: partyinfo, guildLeave, rodexicon... are send ids too
        for t in re.findall(r"(\w+)\s*=", enum[:enum.index("}")]):
            s = struct_for_type(t, structs, aliases)
            used = 1 if re.search(rf"\b{t}\b", compiled) else 0
            length = length_expr(s, structs) if s else "-2, -1"
            lines.append(f'printf("type\\t%04x\\t%d\\t%d\\t{t}\\t{used}\\n", (int){t} & 0xffff, {length});\n')
            if s and structs[s][2] is not None:
                lines += layout_code(s, structs, t, f"(int){t} & 0xffff")
    lines.append("return 0; }\n")
    name = os.path.basename(os.path.dirname(header_file))
    cpp, exe = os.path.join(work, f"dump-{name}.cpp"), os.path.join(work, f"dump-{name}")
    open(cpp, "w").write("".join(lines))
    subprocess.run(cxx + [cpp, "-o", exe], check=True)
    return subprocess.run([exe], capture_output=True, text=True, check=True).stdout

clif_pre = preprocessed_clif()
functions = parse_functions(clif_pre)
# the sources as compiled at this version, without comments, decide whether an id is used: clif.cpp preprocessed
# (its #if branches resolved), the other files with their comments stripped. `sources` (raw) stays for finding a
# send's struct and hardcoded lengths, whose results the compile and the audit check.
compiled = clif_pre
for d in ("map", "char", "login"):
    for f in os.listdir(os.path.join(src, d)):
        if f.endswith(".cpp") and f != "clif.cpp":
            text = open(os.path.join(src, d, f), errors="replace").read()
            compiled += re.sub(r"//[^\n]*|/\*.*?\*/", "", text, flags=re.S)
raw = program("map/packets.hpp", True) + program("common/packets.hpp", False)

# clif_packetdb.hpp order: a later registration of an id replaces the earlier one
parsed, db_len = {}, {}
sent = {}  # id -> (len, name, from, used, struct size of a variable packet that has one size, else -1)
client_hdr = {}  # login and char server requests: id -> (len, name), parsed by struct size
# what the login and char servers parse: headers they register (`this->add( HEADER_X, ...)`, names without a CA_/CH_
# prefix such as PING among them) and raw ids in the char server's switch (`case 0x65: next=chclif_parse_...`)
server_files = [open(os.path.join(src, f), errors="replace").read() for f in ("char/char_clif.cpp", "login/loginclif.cpp")]
registered = {m for text in server_files for m in re.findall(r"this->add\(\s*HEADER_(\w+)", text)}
raw_parsed = {int(i, 16): func for i, func in re.findall(r"case 0x([0-9a-fA-F]+):\s*next\s*=\s*(chclif_parse_\w+)", server_files[0])
              if int(i, 16) < 0x2700}  # 0x2700 on: messages between the servers
type_ids = {}  # packet_headers name -> id
layouts = {}  # (header or type name, id) -> [(field, offset, size, is array)]
for row in raw.splitlines():
    kind, rest = row.split("\t", 1)
    if kind == "lay":
        tag, id_, field, offset, size, array = rest.split("\t")
        layouts.setdefault((tag, int(id_, 16)), []).append((field, int(offset), int(size), array == "1"))
    elif kind == "db":
        id_, length, name, func, offs = rest.split("\t")
        id_, length = int(id_, 16), int(length)
        offs = ",".join(o.strip() for o in offs.split(",")[:-1])  # the macro appends ",0"
        db_len[id_] = (length, name)
        parsed.pop(id_, None)  # re-insert: dict order is registration order
        if func not in ("nullptr", "NULL", "0"):
            parsed[id_] = (length, name, func, offs)
    else:
        id_, length, size, name, used = rest.split("\t")
        id_, used = int(id_, 16), used == "1"
        if kind == "type":
            type_ids[name] = id_
        length = None if length == "-2" else int(length)
        # a request: parsed only where a server registers it (the login and char servers here, the map server through
        # clif_packetdb.hpp below); a declared header alone is not (0x0217 is, and the map server drops the client)
        if re.match(r"C[AHZ]_", name) or name in registered:
            if length is not None and name in registered:
                client_hdr[id_] = (length, name)
            continue
        # an id named twice: prefer the name the sources send, then the one with a struct
        rank = (used, length is not None)
        prev = sent.get(id_)
        if prev is None or rank > (prev[3], prev[0] is not None):
            sent[id_] = (length, name, kind, used, int(size))

# ids without a struct (sent with WFIFOW and packet_len) take the packet table's length
rows, unknown = [], []
no_length = {}  # used names with no struct or table length: id -> name, unless a pass below finds the length
# the ids the sources write at offset 0 at this version (clif.cpp preprocessed, the other map, char and login
# sources without comments): sent, whatever their names say; the audit below wants each in the table
written = {int(v, 16) for v in re.findall(r"PKT(?:WRITE|BUF)_W\(\s*\w+\s*,\s*0\s*\)\s*=\s*0x([0-9a-fA-F]+)", clif_pre)}
# an id written through a variable must trace to a literal assigned to it in the same function
for body in clif_pre.split("\n}"):
    for var in set(re.findall(r"PKT(?:WRITE|BUF)_W\(\s*\w+\s*,\s*0\s*\)\s*=\s*([A-Za-z_]\w*)\s*;", body)):
        values = re.findall(rf"\b{var}\s*=\s*0x([0-9a-fA-F]{{2,4}})\s*;", body)
        if not values:
            unknown.append(f"an id written through {var} that traces to no literal")
        written |= {int(v, 16) for v in values}
# struct sends: the id assigned to the struct's packet type, literals and packet_headers names (HEADER_ constants are
# their own rows), one or several (`= type == MAIL_ATT_ZENY ? rodexgetzeny : rodexgetitem;`)
type_names = set()
for rhs in re.findall(r"(?:\.|->)\s*[Pp]acket[Tt]ype\s*=\s*([^;]+);", clif_pre):
    tokens = re.findall(r"\b(0x[0-9a-fA-F]+|[A-Za-z_]\w*)\b", rhs)
    literals_here = [int(t, 16) for t in tokens if t.startswith("0x")]
    names_here = [t for t in tokens if t in type_ids or t.startswith("HEADER_")]
    written.update(literals_here)
    type_names.update(t for t in names_here if not t.startswith("HEADER_"))
    if not literals_here and not names_here:
        unknown.append(f"a packet type the audit cannot read: {rhs.strip()}")
for d in ("map", "char", "login"):
    for fn in os.listdir(os.path.join(src, d)):
        if fn.endswith(".cpp") and fn != "clif.cpp":
            text = re.sub(r"//[^\n]*|/\*.*?\*/", "", open(os.path.join(src, d, fn), errors="replace").read(), flags=re.S)
            written |= {int(v, 16) for v in re.findall(r"W(?:FIFO|BUF)W\(\s*\w+\s*,\s*0\s*\)\s*=\s*0x([0-9a-fA-F]+)", text)}
for id_, (length, name, kind, used, size) in list(sent.items()):
    if not used and id_ in written:  # sent through a variable, e.g. `int16 cmd = 0x99b; WBUFW(buf, 0) = cmd;`
        sent[id_] = (length, name, kind, True, size)
for id_, func in raw_parsed.items():
    if id_ in db_len:
        client_hdr[id_] = (db_len[id_][0], func)
        sent.pop(id_, None)
    else:
        unknown.append(f"0x{id_:04x} {func}")
for id_ in [i for i, (_, _, _, used, _) in sent.items() if not used and i in parsed]:
    # a send name no code uses for an id the server parses: a request (enum packet_headers mixes them, e.g.
    # bgqueue_registerType, equipitemType)
    del sent[id_]
for id_, (length, name, kind, used, size) in sorted(sent.items()):
    if length is None and id_ in db_len:
        length = db_len[id_][0]
    if length is None:
        # no struct (a packet_headers name whose id is sent through a variable, rodexmailList's 0xac2): the passes below may
        # find its length
        if used:
            no_length[id_] = name
        del sent[id_]
        continue
    rows.append(f"0x{id_:04x}\t{length}\tzc\t{name}\t{kind}{'' if used else ' unused'}\t{size if size >= 0 else ''}")
# ids sent with a hardcoded length and no struct or table entry: WFIFOHEAD(fd, N) just before WFIFOW(fd, 0) = id
for m in re.finditer(r"WFIFOHEAD\(\s*fd\s*,\s*(\d+)\s*\);\s*\n\s*WFIFOW\(\s*fd\s*,\s*0\s*\)\s*=\s*0x([0-9a-fA-F]+)\s*;", sources):
    id_ = int(m.group(2), 16)
    if id_ < 0x2700 and id_ not in sent and id_ not in db_len:  # 0x2700 on: messages between the servers
        rows.append(f"0x{id_:04x}\t{m.group(1)}\tzc\t0x{id_:04x}\twfifo\t")
        sent[id_] = (int(m.group(1)), "", "wfifo", True, -1)
# ids sent through a variable (`int32 cmd = 0xac2; ... WFIFOW(fd, 0) = cmd;`), at this version: variable length when
# the same function writes a length at 2
for body in clif_pre.split("\n}"):
    for var, value in re.findall(r"\b(\w+)\s*=\s*0x([0-9a-fA-F]{2,4})\s*;", body):
        id_ = int(value, 16)
        if id_ >= 0x2700 or id_ in sent or id_ in db_len or id_ in parsed:
            continue
        if re.search(rf"PKT(?:WRITE|BUF)_W\(\s*\w+\s*,\s*0\s*\)\s*=\s*{var}\s*;", body):
            if re.search(r"PKT(?:WRITE|BUF)_W\(\s*\w+\s*,\s*2\s*\)", body):
                rows.append(f"0x{id_:04x}\t-1\tzc\t0x{id_:04x}\tvariable\t")
                sent[id_] = (-1, "", "variable", True, -1)
            else:
                unknown.append(f"0x{id_:04x} (sent through {var})")
# the packet table lists ids no code sends any more; an id written nowhere in the sources is marked unused
literals = {int(v, 16) for v in re.findall(r"\b0x([0-9a-fA-F]{2,4})\b", compiled)}
for id_, (length, name) in sorted(db_len.items()):
    if id_ not in sent and id_ not in parsed and id_ not in client_hdr:
        rows.append(f"0x{id_:04x}\t{length}\tzc\t{name}\tdb{'' if id_ in literals else ' unused'}\t")
# the client of this version sends a request under the id registered last for its handler; older ids still parse
latest = {func: id_ for id_, (_, _, func, _) in parsed.items()}
for id_, (length, name, func, offs) in sorted(parsed.items()):
    source = name if latest[func] == id_ else f"{name} older"
    rows.append(f"0x{id_:04x}\t{length}\tcz\t{func}\t{source}\t{offs}")
for id_, (length, name) in sorted(client_hdr.items()):
    if id_ not in parsed:
        rows.append(f"0x{id_:04x}\t{length}\tcz\t{name}\thdr\t")

# audit: a written id missing from the table is a packet the client could not frame
in_table = {int(r.split("\t", 1)[0], 16) for r in rows}
sent_ids = {int(r.split("\t", 1)[0], 16) for r in rows if "\tzc\t" in r}
unknown += [f"0x{i:04x} {n}" for i, n in sorted(no_length.items()) if i not in sent_ids]
for t in sorted(type_names):
    if t not in type_ids:
        unknown.append(f"{t} (a packet type not in packet_headers)")
    elif type_ids[t] not in sent_ids:
        unknown.append(f"0x{type_ids[t]:04x} {t} (a packet type the table does not send)")
missed = sorted(i for i in written if i < 0x2700 and i not in in_table)
if missed or unknown:
    sys.exit("packetdb_dump: ids the sources write that the table does not have: "
             + ", ".join([f"0x{i:04x}" for i in missed] + unknown))

with open(out, "w") as f:
    f.write(f"# rAthena packet table at PACKETVER={packetver} (scripts/packetdb-dump.sh); generated, do not edit\n")
    f.write("# id\tlength (-1 variable)\tdirection (zc: server sends, cz: server parses)\tname or handler\tsource (\"older\": not the id this client sends)\toffsets (cz) or the one size of a variable packet (zc)\n")
    f.write("\n".join(rows) + "\n")
# the layout of the struct each id is sent or parsed with
chosen = [(id_, "zc", name) for id_, (_, name, _, _, _) in sent.items()]
chosen += [(id_, "cz", name) for id_, (_, name) in client_hdr.items()]
by_function = []  # (id, function, [(field, offset, size, is array)])
for id_, (length, name, func, offs) in parsed.items():
    if id_ in client_hdr or func not in functions:
        continue
    struct, reads = functions[func]
    if (f"fn:{func}", 0) in layouts:
        by_function.append((id_, func, layouts[(f"fn:{func}", 0)]))
    elif reads:
        positions = [int(o) for o in offs.split(",") if o.strip()]
        fields = [(f"pos[{i}]", positions[i], w, False) for i, w in sorted(reads.items()) if i < len(positions)]
        by_function.append((id_, func, fields))
layout_out = out[:-len(".tsv")] + "-layout.tsv"
with open(layout_out, "w") as f:
    f.write(f"# rAthena packet layouts at PACKETVER={packetver} (scripts/packetdb-dump.sh); generated, do not edit\n")
    f.write("# id\tdirection\tstruct's header or type name\tfield\toffset\tsize (0: open array)\tarray\n")
    for id_, direction, name in sorted(chosen):
        for field, offset, size, array in layouts.get((name, id_), []):
            f.write(f"0x{id_:04x}\t{direction}\t{name}\t{field}\t{offset}\t{size}\t{int(array)}\n")
    # requests rAthena registers by raw id: the struct their parse function reads, else the widths it reads at
    # its offsets (`pos[n]` fields: only those, strings and unread bytes have none)
    for id_, func, fields in sorted(by_function):
        for field, offset, size, array in fields:
            f.write(f"0x{id_:04x}\tcz\t{func}\t{field}\t{offset}\t{size}\t{int(array)}\n")
zc = sum(1 for r in rows if "\tzc\t" in r)
print(f"{out}: {zc} sent, {len(rows) - zc} parsed; no length for {len(unknown)}: {', '.join(unknown)}")
print(f"opaque structs (laid out as one field) {len(opaque)}: {', '.join(sorted(opaque))}")
if opaque - OPAQUE_OK:
    sys.exit(f"the dump cannot lay out {', '.join(sorted(opaque - OPAQUE_OK))}: their packets' layouts go unchecked")
