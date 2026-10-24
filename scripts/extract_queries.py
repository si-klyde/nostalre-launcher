#!/usr/bin/env python3
"""Extracts every SQL query template from the rAthena sources and fills in sample values.

Table names (`%s` inside backticks) are resolved from the conf/*.conf defaults through the
argument expression, other placeholders get typed sample values. Queries that cannot be
resolved statically are reported on stderr with the reason.

Output: one JSON object per line: {"where": "file:line", "kind": "query"|"stmt", "sql": ...}
Usage: extract_queries.py <server dir>
"""

import json
import re
import sys
from pathlib import Path

CALLS = {
    "Sql_Query": ("query", 1),  # (kind, index of the format argument)
    "Sql_QueryStr": ("query", 1),
    ".Prepare": ("stmt", 0),
    ".PrepareStr": ("stmt", 0),
    "SqlStmt_Prepare": ("stmt", 1),
    "SqlStmt_PrepareStr": ("stmt", 1),
}
MACROS = {
    "PRIu64": '"llu"', "PRId64": '"lld"', "PRIu32": '"u"', "PRId32": '"d"', "PRIuPTR": '"zu"',
    "LOG_QUERY": '"INSERT"', "PRtf": '"lld"',  # t_tick
}
# argument expressions whose table name has a different config key
ALIASES = {
    "account_db": "login_server_account_db",
    "buyingstores_table": "buyingstore_table",
    "vendings_table": "vending_table",
    "login_table": "login_server_account_db",
    "party_table": "party_db",
}
# web server tables (web.sql) have no config defaults
WEB_TABLES = {
    "user_configs_table": "user_configs", "merchant_configs_table": "merchant_configs",
    "char_configs_table": "char_configs", "guild_emblems_table": "guild_emblems",
    "partybookings_table": "party_bookings",
}
SPEC = re.compile(r"%[-+ #0]*\d*(?:\.\d+)?(hh|h|ll|l|z|j|t)?([diouxXcsfgeEp%])")


def config_tables(server):
    tables = {}
    for conf in server.glob("conf/**/*.conf"):
        for line in conf.read_text(errors="replace").splitlines():
            m = re.match(r"\s*([a-z_]+)\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*$", line)
            if m and re.search(r"(_db|_table|_log)$", m.group(1)):
                tables[m.group(1)] = m.group(2)
    return tables


def split_args(text):
    """Splits a call's argument text on top-level commas."""
    args, depth, cur, quote = [], 0, [], None
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            cur.append(ch)
            if ch == "\\":
                cur.append(text[i + 1])
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
            cur.append(ch)
        elif ch in "([{":
            depth += 1
            cur.append(ch)
        elif ch in ")]}":
            depth -= 1
            cur.append(ch)
        elif ch == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        i += 1
    if "".join(cur).strip():
        args.append("".join(cur).strip())
    return args


def call_text(src, start):
    """Returns the argument text of the call whose '(' is at start."""
    depth, quote, i = 0, None, start
    while i < len(src):
        ch = src[i]
        if quote:
            if ch == "\\":
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return src[start + 1:i]
        i += 1
    return None


def literal(expr):
    """Concatenated C string literal value, or None if expr is not only literals."""
    for name, value in MACROS.items():
        expr = re.sub(rf"\b{name}\b", value, expr)
    expr = re.sub(r"\bPRI([diouxX])\w*\b", r'"l\1"', expr)  # remaining <cinttypes> macros
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', expr)
    rest = re.sub(r'"((?:[^"\\]|\\.)*)"', "", expr).strip()
    if not parts or rest:
        return None
    return bytes("".join(parts), "utf-8").decode("unicode_escape")


def sample(conv, in_quotes):
    if conv in "diouxX":
        return "1"
    if conv in "fgeE":
        return "1.5"
    if conv == "c":
        return "M"
    return "x"


def fill(fmt, args, tables):
    """Substitutes placeholders; returns (sql, problem)."""
    out, pos, argi = [], 0, 0
    for m in SPEC.finditer(fmt):
        out.append(fmt[pos:m.start()])
        pos = m.end()
        conv = m.group(2)
        if conv == "%":
            out.append("%")
            continue
        before = "".join(out)
        in_backticks = before.count("`") % 2 == 1
        in_quotes = before.count("'") % 2 == 1
        arg = args[argi] if argi < len(args) else ""
        argi += 1
        if conv == "s" and in_backticks:
            expr = re.sub(r"\.c_str\(\)$", "", arg.strip())
            key = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", expr)
            key = key[-1] if key else ""
            if re.match(r"log_config\.log_\w+$", expr):
                key += "_db"  # log_config.log_pick -> log_pick_db
            name = tables.get(ALIASES.get(key, key)) or tables.get(key) or WEB_TABLES.get(key)
            if name is None:
                return None, f"unresolved table argument '{arg}'"
            out.append(name)
        elif conv == "s" and not in_quotes:
            return None, f"SQL fragment argument '{arg}'"
        else:
            out.append(sample(conv, in_quotes))
    out.append(fmt[pos:])
    return "".join(out), None


def main():
    server = Path(sys.argv[1])
    tables = config_tables(server)
    found = skipped = 0

    for path in sorted(server.glob("src/**/*.cpp")):
        rel = path.relative_to(server)
        if rel.parts[1] in ("tool",) or rel.name.startswith("sql"):
            continue
        src = path.read_text(errors="replace")
        for name, (kind, fmt_index) in CALLS.items():
            pattern = re.escape(name) + r"\s*\(" if name.startswith(".") else r"\b" + re.escape(name) + r"\s*\("
            for m in re.finditer(pattern, src):
                where = f"{rel}:{src.count(chr(10), 0, m.start()) + 1}"
                text = call_text(src, m.end() - 1)
                args = split_args(text or "")
                if len(args) <= fmt_index:
                    continue
                fmt = literal(args[fmt_index])
                if fmt is None:
                    print(f"{where}: format is not a literal: {args[fmt_index][:80]}", file=sys.stderr)
                    skipped += 1
                    continue
                sql, problem = fill(fmt, args[fmt_index + 1:], tables)
                if problem:
                    print(f"{where}: {problem}", file=sys.stderr)
                    skipped += 1
                    continue
                print(json.dumps({"where": where, "kind": kind, "sql": sql}))
                found += 1

    print(f"extracted {found}, skipped {skipped}", file=sys.stderr)


if __name__ == "__main__":
    main()
