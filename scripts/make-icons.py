#!/usr/bin/env python3
"""Genere les ressources d'icones d'AndroClaw depuis assets/logo-source.png.

Le logo officiel est un glyphe BLANC sur fond NOIR opaque. On rend transparent le
seul fond EXTERIEUR (le noir relie aux bords de l'image), ce qui conserve intactes
les formes noires internes du logo : l'oeuf casse, les yeux, les griffes.

Sorties (toutes versionnees sauf mention) :
  assets/logo.png        logo transparent (512 px), source des ressources Android
  drawable-*/logo.png    marque affichee dans l'en-tete de l'app (32 dp)
  mipmap-*               icones de lancement (adaptative + heritee)

Usage : python3 scripts/make-icons.py [chemin-du-logo-source]
"""
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "assets", "logo-source.png")
BG = (10, 10, 14, 255)      # fond de l'app (#0A0A0E)
DARK = 52                   # seuil : en dessous = fond
ASSET = 512                 # taille du logo transparent du depot

im = Image.open(SRC).convert("RGBA")
W, H = im.size

# 1) fond exterieur : noir relie aux bords (inondation depuis les 4 coins)
dark = im.convert("L").point(lambda v: 255 if v < DARK else 0).convert("RGB")
flood = dark.copy()
for seed in ((0, 0), (W - 1, 0), (0, H - 1), (W - 1, H - 1)):
    ImageDraw.floodfill(flood, seed, (128, 128, 128), thresh=0)
outside = flood.getchannel("R").point(lambda v: 255 if v == 128 else 0)

# 2) opacite : tout est opaque sauf le fond exterieur
alpha = outside.point(lambda v: 0 if v == 255 else 255)
art = im.copy()
art.putalpha(alpha)
bbox = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
if bbox:
    art = art.crop(bbox)


def square(img, ratio):
    """Centre l'image dans un carre transparent (ratio = part occupee)."""
    side = max(64, int(max(img.size) / ratio))
    c = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    c.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return c


art = square(art, 0.97)
print("source %dx%d -> logo transparent %dx%d" % (W, H, art.width, art.height))

# 3) logo transparent du depot
os.makedirs(os.path.join(ROOT, "assets"), exist_ok=True)
art.resize((ASSET, ASSET), Image.LANCZOS).save(os.path.join(ROOT, "assets", "logo.png"))

DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# 4) marque de l'en-tete (32 dp, fond transparent)
for name, f in DENS.items():
    d = os.path.join(RES, "drawable-" + name)
    os.makedirs(d, exist_ok=True)
    t = int(32 * f)
    art.resize((t, t), Image.LANCZOS).save(os.path.join(d, "logo.png"))

# 5) icones de lancement : logo blanc sur le fond de l'app
for name, f in DENS.items():
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    leg, fg = int(48 * f), int(108 * f)

    def on_bg(box, ratio):
        c = Image.new("RGBA", (box, box), BG)
        t = int(box * ratio)
        r = art.resize((t, t), Image.LANCZOS)
        c.paste(r, ((box - t) // 2, (box - t) // 2), r)
        return c

    on_bg(leg, 0.78).save(os.path.join(d, "ic_launcher.png"))

    disc = Image.new("RGBA", (leg, leg), (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse((0, 0, leg - 1, leg - 1), fill=BG)
    lay = art.resize((int(leg * 0.70), int(leg * 0.70)), Image.LANCZOS)
    disc.paste(lay, ((leg - lay.width) // 2, (leg - lay.height) // 2), lay)
    disc.save(os.path.join(d, "ic_launcher_round.png"))

    c = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
    t = int(fg * 0.62)
    r = art.resize((t, t), Image.LANCZOS)
    c.paste(r, ((fg - t) // 2, (fg - t) // 2), r)
    c.save(os.path.join(d, "ic_launcher_foreground.png"))

print("ecrit: assets/logo.png + drawable-*/logo.png + mipmap-*")
