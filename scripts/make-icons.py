#!/usr/bin/env python3
"""Genere les ressources d'icones de KeepClaw depuis assets/logo-source.png.

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

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ONLY_WEB = "--web" in sys.argv
SRC = ARGS[0] if ARGS else os.path.join(ROOT, "assets", "logo-source.png")
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
if not ONLY_WEB:
    os.makedirs(os.path.join(ROOT, "assets"), exist_ok=True)
    art.resize((ASSET, ASSET), Image.LANCZOS).save(os.path.join(ROOT, "assets", "logo.png"))

DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# 4) marque de l'en-tete (32 dp, fond transparent)
for name, f in ({} if ONLY_WEB else DENS).items():
    d = os.path.join(RES, "drawable-" + name)
    os.makedirs(d, exist_ok=True)
    t = int(32 * f)
    art.resize((t, t), Image.LANCZOS).save(os.path.join(d, "logo.png"))

# 5) icones de lancement : logo blanc sur le fond de l'app
for name, f in ({} if ONLY_WEB else DENS).items():
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

if not ONLY_WEB:
    print("ecrit: assets/logo.png + drawable-*/logo.png + mipmap-*")

# 6) assets de la console web (branding) : logo avec texte, favicons, manifeste.
#    Generes ici (et non patchees en binaire) car ils derivent du meme logo source.
WEB = os.path.join(ROOT, "native", "picoclaw", "web", "frontend", "public")
if os.path.isdir(WEB):
    def on_bg_square(img, side, ratio, radius=0):
        c = Image.new("RGBA", (side, side), BG)
        t = int(side * ratio)
        r = img.resize((t, t), Image.LANCZOS)
        c.paste(r, ((side - t) // 2, (side - t) // 2), r)
        if radius > 0:
            mask = Image.new("L", (side, side), 0)
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, side - 1, side - 1), radius=radius, fill=255)
            c.putalpha(mask)
        return c

    # wordmark : glyphe + "KeepClaw" (comme l'en-tete natif)
    font = None
    for fp in ("/System/Library/Fonts/Supplemental/Arial Bold.ttf",
               "/System/Library/Fonts/Helvetica.ttc",
               "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        try:
            font = ImageFont.truetype(fp, 58)
            break
        except Exception:
            continue
    H2 = 104
    glyph = art.resize((H2 - 8, H2 - 8), Image.LANCZOS)
    if font is not None:
        label = "KeepClaw"
        tmp = Image.new("RGBA", (10, 10), (0, 0, 0, 0))
        tw = ImageDraw.Draw(tmp).textlength(label, font=font)
        mark = Image.new("RGBA", (int(glyph.width + 14 + tw + 6), H2), (0, 0, 0, 0))
        mark.paste(glyph, (0, 4), glyph)
        ImageDraw.Draw(mark).text((glyph.width + 14, H2 // 2), label, font=font,
                                  fill=(232, 232, 239, 255), anchor="lm")
        mark.save(os.path.join(WEB, "logo_with_text.png"))
    else:
        mark = Image.new("RGBA", (500, H2), (0, 0, 0, 0))
        g = glyph.resize((H2 - 8, H2 - 8), Image.LANCZOS)
        mark.paste(g, (0, 4), g)
        mark.save(os.path.join(WEB, "logo_with_text.png"))

    # favicons / icones PWA (glyphe blanc sur fond app)
    on_bg_square(art, 180, 0.80).save(os.path.join(WEB, "apple-touch-icon.png"))
    on_bg_square(art, 96, 0.80).save(os.path.join(WEB, "favicon-96x96.png"))
    on_bg_square(art, 192, 0.62).save(os.path.join(WEB, "web-app-manifest-192x192.png"))
    on_bg_square(art, 512, 0.62).save(os.path.join(WEB, "web-app-manifest-512x512.png"))
    ico = [on_bg_square(art, s, 0.80) for s in (16, 32, 48)]
    ico[0].save(os.path.join(WEB, "favicon.ico"), sizes=[(s, s) for s in (16, 32, 48)])

    # favicon.svg : PNG embarque (auto-suffisant, evite le logo upstream)
    import base64
    import io
    buf = io.BytesIO()
    on_bg_square(art, 64, 0.80).save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    with open(os.path.join(WEB, "favicon.svg"), "w") as fh:
        fh.write(
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
            '<image width="64" height="64" href="data:image/png;base64,%s"/></svg>\n' % b64)

    import json
    with open(os.path.join(WEB, "site.webmanifest"), "w") as fh:
        json.dump({
            "name": "KeepClaw",
            "short_name": "KeepClaw",
            "icons": [
                {"src": "/web-app-manifest-192x192.png", "sizes": "192x192", "type": "image/png", "purpose": "maskable"},
                {"src": "/web-app-manifest-512x512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
            ],
            "theme_color": "#0A0A0E",
            "background_color": "#0A0A0E",
            "display": "standalone",
        }, fh, indent=2)
        fh.write("\n")
    print("ecrit: web/frontend/public (logo_with_text, favicons, webmanifest)")
