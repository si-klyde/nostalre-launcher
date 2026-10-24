#!/usr/bin/env python3
"""Turns a MariaDB general query log into distinct statements for sql-compat-test --prepare.

Output lines: "general.log:<line>\t<sql>"; statements are deduplicated after collapsing
whitespace, prepared statements are taken in their '?' form.
Usage: general_log_queries.py <general.log>
"""

import re
import sys

# "<time>\t<id> <Command>\t<argument>" or "\t\t<id> <Command>\t<argument>"
ENTRY = re.compile(r"^(?:\d{6}\s+[\d:]+|\s*)\s+\d+\s+(\w[\w ]*?)\t(.*)$")
KEEP = {"Query", "Prepare"}
SKIP = re.compile(r"^\s*(SET|SHOW|SELECT @@|SELECT 1$|COMMIT|ROLLBACK|START TRANSACTION)", re.I)


def main():
    statements, current = [], None
    with open(sys.argv[1], encoding="utf-8", errors="replace") as log:
        for number, line in enumerate(log, 1):
            m = ENTRY.match(line.rstrip("\n"))
            if m:
                current = [number, m.group(2)] if m.group(1) in KEEP else None
                if current:
                    statements.append(current)
            elif current:
                current[1] += " " + line.rstrip("\n")

    seen = set()
    for number, sql in statements:
        sql = " ".join(sql.split())
        if sql and sql not in seen and not SKIP.match(sql):
            seen.add(sql)
            print(f"general.log:{number}\t{sql}")


if __name__ == "__main__":
    main()
