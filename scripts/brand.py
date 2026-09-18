#!/usr/bin/env python3
"""KeepClaw — branding.

Applique notre marque (KeepClaw) sur les sources du sous-module picoclaw, puis
recompile : le sous-module reste épinglé sur son commit upstream, notre branding
est explicite, versionné et reproductible.

Idempotent : relancer le script ne change rien (compte 0 substitution).

Portee volontairement limitee :
  - les chemins d'import Go (github.com/sipeed/picoclaw/...) ne sont JAMAIS touches ;
  - la doc upstream (docs/, workspace/, *.md, README) n'est pas touchee : elle n'est
    pas embarquee dans l'application ;
  - l'endpoint /api/update est neutralise : il installe les binaires *upstream*, ce
    qui ecraserait nos .so.

Usage : python3 scripts/brand.py [--check]
"""
from __future__ import annotations

import os
import sys

SUBMODULE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "native", "picoclaw")

# --- remplacements appliques aux fichiers Go (jamais d'import path) -------------
GO_RULES = [
    ('"https://docs.picoclaw.io/docs/"', '"https://github.com/leakedd/KeepClaw"'),
    ('"https://docs.picoclaw.io/zh-Hans/docs/"', '"https://github.com/leakedd/KeepClaw"'),
    ('"https://github.com/sipeed/picoclaw"', '"https://github.com/leakedd/KeepClaw"'),
    ("PICOCLAW_", "KEEPCLAW_"),
    ("picoclaw_launcher_auth", "keepclaw_launcher_auth"),
    ("PicoClaw", "KeepClaw"),
    ("You are picoclaw", "You are KeepClaw"),
    (".picoclaw_history", ".keepclaw_history"),
    (".picoclaw.pid", ".keepclaw.pid"),
    ('".picoclaw"', '".keepclaw"'),
    ('"picoclaw"', '"keepclaw"'),
    ("io.picoclaw.launcher", "io.keepclaw.host"),
    ("PicoClawLauncher", "KeepClawHost"),
    ("PicoClaw Web", "KeepClaw Host"),
    ("~/.picoclaw", "~/.keepclaw"),
    ('".picoclaw/config', '".keepclaw/config'),
    (".picoclaw/workspace", ".keepclaw/workspace"),
    ("# picoclaw \U0001F99E", "# KeepClaw"),
    ("Core picoclaw identity", "Core KeepClaw identity"),
    ("picoclaw identity", "keepclaw identity"),
    ("Run: picoclaw auth login", "Run: keepclaw auth login"),
    ("process is not picoclaw", "process is not keepclaw"),
    ("via picoclaw CLI", "via keepclaw CLI"),
    ("picoclaw-workspace", "keepclaw-workspace"),
    ("picoclaw --version", "keepclaw --version"),
    (r"""	banner    = "\r\n" +
		colorBlue + "██████╗ ██╗ ██████╗ ██████╗ " + colorRed + " ██████╗██╗      █████╗ ██╗    ██╗\n" +
		colorBlue + "██╔══██╗██║██╔════╝██╔═══██╗" + colorRed + "██╔════╝██║     ██╔══██╗██║    ██║\n" +
		colorBlue + "██████╔╝██║██║     ██║   ██║" + colorRed + "██║     ██║     ███████║██║ █╗ ██║\n" +
		colorBlue + "██╔═══╝ ██║██║     ██║   ██║" + colorRed + "██║     ██║     ██╔══██║██║███╗██║\n" +
		colorBlue + "██║     ██║╚██████╗╚██████╔╝" + colorRed + "╚██████╗███████╗██║  ██║╚███╔███╔╝\n" +
		colorBlue + "╚═╝     ╚═╝ ╚═════╝ ╚═════╝ " + colorRed + " ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝\n " +
		"\033[0m\r\n"
	plainBanner = "\r\n" +
		"██████╗ ██╗ ██████╗ ██████╗  ██████╗██╗      █████╗ ██╗    ██╗\n" +
		"██╔══██╗██║██╔════╝██╔═══██╗██╔════╝██║     ██╔══██╗██║    ██║\n" +
		"██████╔╝██║██║     ██║   ██║██║     ██║     ███████║██║ █╗ ██║\n" +
		"██╔═══╝ ██║██║     ██║   ██║██║     ██║     ██╔══██║██║███╗██║\n" +
		"██║     ██║╚██████╗╚██████╔╝╚██████╗███████╗██║  ██║╚███╔███╔╝\n" +
		"╚═╝     ╚═╝ ╚═════╝ ╚═════╝  ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝\n " +
		"\r\n"
""", r"""	banner    = "\r\n" + colorBlue + "  KeepClaw" + "\033[0m" + colorRed + "  \u00b7  agent gateway" + "\033[0m\r\n\r\n"
	plainBanner = "\r\n  KeepClaw \u00b7 agent gateway\r\n\r\n"
"""),
]

# --- remplacements appliques a la console web (tsx/ts/html/json d'i18n) ---------
WEB_RULES = [
    ("https://docs.picoclaw.io", "https://github.com/leakedd/KeepClaw"),
    ("PicoClaw", "KeepClaw"),
    ("picoclaw", "keepclaw"),
]

# Neutralisation de l'endpoint de mise a jour upstream.
UPDATE_RULE = (
    '\tmux.HandleFunc("/api/update", h.handleUpdate)',
    "\t// branding: endpoint de mise a jour upstream desactive (ecraserait nos binaires)\n"
    '\t// mux.HandleFunc("/api/update", h.handleUpdate)',
)

SKIP_DIRS = {"node_modules", "dist", ".git", "docs", "workspace", "testdata", "vendor"}
WEB_EXT = {".ts", ".tsx", ".html", ".json"}
WEB_ROOTS = ("web/frontend/src", "web/frontend/index.html")


def iter_go_files(root: str):
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(".go"):
                yield os.path.join(base, f)


def iter_web_files(root: str):
    for rel in WEB_ROOTS:
        p = os.path.join(root, rel)
        if os.path.isfile(p):
            yield p
            continue
        for base, dirs, files in os.walk(p):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for f in files:
                if os.path.splitext(f)[1] in WEB_EXT:
                    yield os.path.join(base, f)


def apply(path: str, rules, report: dict, check: bool) -> int:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            src = fh.read()
    except (UnicodeDecodeError, OSError):
        return 0
    out = src
    for old, new in rules:
        if old in out:
            n = out.count(old)
            out = out.replace(old, new)
            report[os.path.relpath(path, SUBMODULE)] = report.get(os.path.relpath(path, SUBMODULE), 0) + n
    if out != src and not check:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(out)
    return 0 if out == src else 1


def main() -> int:
    check = "--check" in sys.argv
    root = os.path.normpath(SUBMODULE)
    if not os.path.isdir(root):
        print("sous-module absent : git submodule update --init --recursive", file=sys.stderr)
        return 1

    report: dict[str, int] = {}
    touched = 0
    for p in iter_go_files(root):
        touched += apply(p, GO_RULES, report, check)
    for p in iter_web_files(root):
        touched += apply(p, WEB_RULES, report, check)

    upd = os.path.join(root, "web/backend/api/update.go")
    if os.path.isfile(upd):
        with open(upd, "r", encoding="utf-8") as fh:
            src = fh.read()
        if UPDATE_RULE[0] in src:
            if not check:
                with open(upd, "w", encoding="utf-8") as fh:
                    fh.write(src.replace(*UPDATE_RULE))
            report["web/backend/api/update.go (route desactivee)"] = 1
            touched += 1

    total = sum(report.values())
    if total == 0:
        print("branding: deja applique (0 substitution)")
        return 0
    print(f"branding: {total} substitutions dans {len(report)} fichiers"
          + (" [mode --check, rien ecrit]" if check else ""))
    for path, n in sorted(report.items(), key=lambda kv: -kv[1])[:12]:
        print(f"  {n:4d}  {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
