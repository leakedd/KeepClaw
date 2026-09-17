#!/usr/bin/env python3
"""Genere les ressources d'icones d'AndroClaw depuis la capture du logo original.

Le logo est un robot clair (avec son oeuf fissure) pose sur un fond sombre, dans une
capture d'ecran qui contient aussi un leger cadre parasite. Methode :

  1. on retire la bordure externe de la capture ;
  2. on isole la plus grande forme CLAIRE (= le robot) -> masque "strict" ;
  3. on dilate ce masque : la zone du logo inclut alors ses contours noirs et ses
     details internes (yeux, oeuf) sans aspirer le fond ni les restes de cadre ;
  4. on ne garde que cette zone comme opacite ; tout le reste devient transparent.

Sortie : assets/logo.png (logo transparent du depot) + drawable-*/logo.png (en-tete de
l'app, 32dp) + mipmap-* (icone de lancement, adaptative et heritee).
"""
import collections
import os

from PIL import Image, ImageDraw, ImageFilter

SRC = "/Users/mac/Library/Application Support/Hermes/composer-images/Capture_d_e_cran_2026-09-17_a_02.57.28_01b32e.png"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
BG = (10, 10, 14, 255)      # fond de l'app (#0A0A0E)
DARK = 52                   # luminance en dessous de laquelle un pixel est « fond »
BRIGHT = 110                # luminance a partir de laquelle un pixel est « logo »
GROW = 5                    # dilatation (px) autour de la forme claire

im = Image.open(SRC).convert("RGBA")
W, H = im.size
m = max(4, int(min(W, H) * 0.055))
core = im.crop((m, m, W - m, H - m))
CW, CH = core.size


def largest_blob(mask_img):
    """Retourne un masque ne gardant que la plus grande tache connexe."""
    w, h = mask_img.size
    px = mask_img.load()
    seen = bytearray(w * h)
    best = []
    for y in range(h):
        for x in range(w):
            if seen[y * w + x] or px[x, y] == 0:
                continue
            comp, dq = [], collections.deque([(x, y)])
            seen[y * w + x] = 1
            while dq:
                cx, cy = dq.popleft()
                comp.append((cx, cy))
                for nx, ny in ((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1)):
                    if 0 <= nx < w and 0 <= ny < h:
                        j = ny * w + nx
                        if not seen[j] and px[nx, ny]:
                            seen[j] = 1
                            dq.append((nx, ny))
            if len(comp) > len(best):
                best = comp
    keep = Image.new("L", (w, h), 0)
    kp = keep.load()
    for (x, y) in best:
        kp[x, y] = 255
    return keep


# 1) + 2) forme claire la plus grande = le robot
strict = largest_blob(core.convert("L").point(lambda v: 255 if v > BRIGHT else 0))

# 3) dilatation -> zone du logo (contours et details internes compris)
zone = strict.filter(ImageFilter.MaxFilter(2 * GROW + 1))

# 4) opacite : on garde la zone, mais on rend transparent ce qui, dans la zone,
#    appartient au FOND atteignable depuis les bords (sinon un lisere sombre subsiste).
dark = core.convert("L").point(lambda v: 255 if v < DARK else 0).convert("RGB")
flood = dark.copy()
for seed in ((0, 0), (CW - 1, 0), (0, CH - 1), (CW - 1, CH - 1)):
    ImageDraw.floodfill(flood, seed, (128, 128, 128), thresh=0)
outside = flood.getchannel("R").point(lambda v: 255 if v == 128 else 0)

alpha = Image.composite(Image.new("L", zone.size, 255), Image.new("L", zone.size, 0), zone)
alpha = Image.composite(Image.new("L", zone.size, 0), alpha, outside)   # fond -> transparent
alpha = alpha.filter(ImageFilter.GaussianBlur(0.5))

art = core.copy()
art.putalpha(alpha)
bbox = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
art = art.crop(bbox)


def square(img, ratio):
    """Centre l'image dans un carre transparent (ratio = part occupee)."""
    side = int(max(img.size) / ratio)
    c = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    c.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return c


art = square(art, 0.97)
print("source %dx%d -> logo transparent %dx%d (dilatation %dpx)" % (W, H, art.width, art.height, GROW))

# Asset du depot : reproductible par quiconque clone le projet.
os.makedirs(os.path.join(ROOT, "assets"), exist_ok=True)
side = max(art.size)
art.resize((side, side), Image.LANCZOS).save(os.path.join(ROOT, "assets", "logo.png"))

DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# Logo de l'en-tete (32dp, fond transparent)
for name, f in DENS.items():
    d = os.path.join(RES, "drawable-" + name)
    os.makedirs(d, exist_ok=True)
    t = int(32 * f)
    art.resize((t, t), Image.LANCZOS).save(os.path.join(d, "logo.png"))

# Icone de lancement (adaptative + heritee) sur le fond de l'app
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

    on_bg(leg, 0.76).save(os.path.join(d, "ic_launcher.png"))

    disc = Image.new("RGBA", (leg, leg), (0, 0, 0, 0))
    ImageDraw.Draw(disc).ellipse((0, 0, leg - 1, leg - 1), fill=BG)
    lay = art.resize((int(leg * 0.68), int(leg * 0.68)), Image.LANCZOS)
    disc.paste(lay, ((leg - lay.width) // 2, (leg - lay.height) // 2), lay)
    disc.save(os.path.join(d, "ic_launcher_round.png"))

    c = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
    t = int(fg * 0.62)
    r = art.resize((t, t), Image.LANCZOS)
    c.paste(r, ((fg - t) // 2, (fg - t) // 2), r)
    c.save(os.path.join(d, "ic_launcher_foreground.png"))

print("ecrit: assets/logo.png + drawable-*/logo.png + mipmap-*")
