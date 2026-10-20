"""Icons not in the Material Symbols set we render (drawn as shapes in the same style: white, rounded, ~150 px).
Usage: python3 -I draw_symbols.py OUT_DIR"""
import os
import sys

from PIL import Image, ImageDraw

S = 8  # drawn 8x larger, then scaled down (smooth edges)


def canvas(w, h):
    img = Image.new("L", (w * S, h * S), 0)
    return img, ImageDraw.Draw(img)


def save(img, out, name):
    img = img.resize((img.width // S, img.height // S), Image.LANCZOS)
    rgba = Image.new("RGBA", img.size, (255, 255, 255, 0))
    rgba.putalpha(img)
    rgba.save(os.path.join(out, name + ".png"), optimize=True)


def music(out):
    # Two eighth notes joined by a beam (Material's "music_note"-like, filled).
    img, d = canvas(136, 152)
    k = S
    d.ellipse((4 * k, 104 * k, 52 * k, 148 * k), fill=255)
    d.ellipse((84 * k, 88 * k, 132 * k, 132 * k), fill=255)
    d.rounded_rectangle((38 * k, 16 * k, 52 * k, 126 * k), radius=7 * k, fill=255)
    d.rounded_rectangle((118 * k, 0, 132 * k, 110 * k), radius=7 * k, fill=255)
    d.polygon([(38 * k, 16 * k), (132 * k, 0), (132 * k, 30 * k), (38 * k, 46 * k)], fill=255)
    save(img, out, "music")


def movie(out):
    # A clapperboard: the board with a slanted striped top (Material's "movie").
    img, d = canvas(152, 140)
    k = S
    d.rounded_rectangle((4 * k, 52 * k, 148 * k, 136 * k), radius=14 * k, fill=255)
    top = Image.new("L", img.size, 0)
    t = ImageDraw.Draw(top)
    t.rounded_rectangle((4 * k, 14 * k, 148 * k, 42 * k), radius=8 * k, fill=255)
    for x in (22, 62, 102, 142):   # the clapper's stripes, cut out
        t.polygon([(x * k, 14 * k), ((x + 20) * k, 14 * k), ((x + 8) * k, 42 * k), ((x - 12) * k, 42 * k)], fill=0)
    top = top.rotate(-8, center=(4 * k, 42 * k), resample=Image.BICUBIC)
    img.paste(255, mask=top)
    save(img, out, "movie")


def thermometer(out):
    img, d = canvas(84, 152)
    k = S
    d.ellipse((6 * k, 80 * k, 78 * k, 152 * k), fill=255)
    d.rounded_rectangle((22 * k, 0, 62 * k, 110 * k), radius=20 * k, fill=255)
    d.rounded_rectangle((33 * k, 12 * k, 51 * k, 100 * k), radius=9 * k, fill=0)
    d.rectangle((33 * k, 56 * k, 51 * k, 110 * k), fill=255)
    save(img, out, "thermometer")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    music(out)
    movie(out)
    thermometer(out)
    print("ok")


if __name__ == "__main__":
    main()
