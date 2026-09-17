#!/usr/bin/env python3
"""Répare un pnpm-lock.yaml contenant des clés RÉELLEMENT dupliquées dans une même
section (bug upstream, ex. semver@7.8.5 présent deux fois dans 'packages:').

ATTENTION : les mêmes clés apparaissent légitimement dans 'packages:' ET dans
'snapshots:'. Le registre doit donc être réinitialisé à chaque section, sinon on
supprime des entrées valides.
"""
import re, sys, collections

p = sys.argv[1] if len(sys.argv) > 1 else "pnpm-lock.yaml"
src = open(p, encoding="utf-8").read()
lines = src.split("\n")

KEY = re.compile(r"^  (\S+):")
SECTIONS = ("packages:", "snapshots:")

out = []
seen = set()
section = None
i = 0
removed = collections.Counter()

while i < len(lines):
    l = lines[i]

    if l in SECTIONS:
        section = l
        seen = set()                       # <- reset par section (le bug de la v1)
        out.append(l)
        i += 1
        continue

    if l and not l.startswith(" "):         # toute autre ligne racine ferme la section
        section = None

    if section and l.startswith("  ") and not l.startswith("    "):
        m = KEY.match(l)
        if m and m.group(1) in seen:
            removed[m.group(1)] += 1
            i += 1
            while i < len(lines) and lines[i].startswith("    "):
                i += 1
            if i < len(lines) and lines[i].strip() == "":
                i += 1
            continue
        if m:
            seen.add(m.group(1))

    out.append(l)
    i += 1

new = "\n".join(out)
open(p, "w", encoding="utf-8").write(new)
print("clés dupliquées supprimées : %d" % sum(removed.values()))
print("octets : %d -> %d (delta %+d)" % (len(src), len(new), len(new) - len(src)))
