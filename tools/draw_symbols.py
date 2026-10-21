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


def focus(out):
    # Four corner brackets around a dot (Material's "center_focus_strong").
    img, d = canvas(148, 148)
    k = S
    t, L = 14, 46
    for (x, y, sx, sy) in ((4, 4, 1, 1), (144, 4, -1, 1), (4, 144, 1, -1), (144, 144, -1, -1)):
        d.rounded_rectangle((min(x, x + sx * L) * k, min(y, y + sy * t) * k, max(x, x + sx * L) * k, max(y, y + sy * t) * k), radius=7 * k, fill=255)
        d.rounded_rectangle((min(x, x + sx * t) * k, min(y, y + sy * L) * k, max(x, x + sx * t) * k, max(y, y + sy * L) * k), radius=7 * k, fill=255)
    d.ellipse((50 * k, 50 * k, 98 * k, 98 * k), fill=255)
    save(img, out, "focus")


def wallpaper(out):
    # A picture: a rounded frame with a mountain and a sun (Material's "wallpaper"/"image").
    img, d = canvas(160, 136)
    k = S
    d.rounded_rectangle((4 * k, 4 * k, 156 * k, 132 * k), radius=20 * k, fill=255)
    d.rounded_rectangle((18 * k, 18 * k, 142 * k, 118 * k), radius=10 * k, fill=0)
    d.polygon([(26 * k, 110 * k), (66 * k, 58 * k), (92 * k, 90 * k), (106 * k, 74 * k), (134 * k, 110 * k)], fill=255)
    d.ellipse((98 * k, 30 * k, 122 * k, 54 * k), fill=255)
    save(img, out, "wallpaper")


def dice(out):
    # A die showing five (Material's "casino").
    img, d = canvas(148, 148)
    k = S
    d.rounded_rectangle((4 * k, 4 * k, 144 * k, 144 * k), radius=30 * k, fill=255)
    for (x, y) in ((40, 40), (108, 40), (74, 74), (40, 108), (108, 108)):
        d.ellipse(((x - 12) * k, (y - 12) * k, (x + 12) * k, (y + 12) * k), fill=0)
    save(img, out, "dice")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    music(out)
    movie(out)
    thermometer(out)
    focus(out)
    wallpaper(out)
    dice(out)
    print("ok")


if __name__ == "__main__":
    main()
