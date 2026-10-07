#!/usr/bin/env python3
"""
Makes the app's launcher icon from the Owner's brand PNGs in branding/.

Run from the repository root:

    python3 tools/branding/make-assets.py

It needs Pillow (`pip install pillow`). It reads branding/ and never writes
there: the 1024 x 1024 originals stay exactly as the Owner uploaded them. It
writes only the PNGs listed in `make_icons` below, under app/src/main/res/,
and running it again gives the same files.

Every file is the artwork scaled down, and placed on a plain #F7F4FF shape for
the older launchers. Nothing is redrawn, recoloured or cropped.
"""
import math
import os
import sys

from PIL import Image, ImageDraw

BRANDING = "branding"
RES = os.path.join("app", "src", "main", "res")

MARK = os.path.join(BRANDING, "QD_icon_mark_1024.png")
MONOCHROME = os.path.join(BRANDING, "QD_icon_monochrome_1024.png")

# The icon background, #F7F4FF (the Owner, N5.12b).
BACKGROUND = (0xF7, 0xF4, 0xFF, 0xFF)

DENSITIES = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]

# Adaptive icon (Android 8 and later). The layers are 108dp square; a launcher
# shows the middle 72dp through its mask, and only a 66dp circle in the middle
# is safe from every mask. The mark's farthest visible pixel - the tip of the
# Q's tail, at the bottom-right corner - is 65.6% of the image's width from
# its centre, so its 1024 square drawn at 44dp ends 28.9dp out, and even the
# faintest fringe the resampling leaves is within 30.2dp at every density:
# inside the 33dp safe radius with 2.8dp to spare. (At 48dp a fringe pixel
# reached 33.01dp at hdpi; `layer` refuses anything past the radius.)
CANVAS_DP = 108
MARK_DP = 44
SAFE_RADIUS_DP = 33

# Legacy icon (Android 6 to 7.1): 48dp, a 44dp #F7F4FF shape with 2dp clear
# round it, the mark at 28dp in the middle - its tail 18.4dp from the centre,
# inside the shape's 22dp.
LEGACY_DP = 48
LEGACY_SHAPE_DP = 44
LEGACY_MARK_DP = 28
LEGACY_CORNER_DP = 8

SUPERSAMPLE = 8


def px(dp, scale):
    value = dp * scale
    if value != int(value):
        sys.exit(f"{dp}dp at {scale}x is not a whole pixel")
    return int(value)


def load(path):
    image = Image.open(path)
    if image.size != (1024, 1024) or image.mode != "RGBA":
        sys.exit(f"{path}: expected a 1024 x 1024 RGBA PNG, found {image.size} {image.mode}")
    return image


def scaled(source, size):
    # Pillow resamples RGBA with premultiplied alpha, so no fringe of the
    # transparent pixels' colour bleeds into the edge.
    return source.resize((size, size), Image.LANCZOS)


def farthest_visible(image):
    """Distance from the image's centre to the farthest corner of any pixel that is not fully transparent."""
    alpha = image.getchannel("A").load()
    width, height = image.size
    cx, cy = width / 2, height / 2
    farthest = 0.0
    for y in range(height):
        for x in range(width):
            if alpha[x, y]:
                dx = max(abs(x - cx), abs(x + 1 - cx))
                dy = max(abs(y - cy), abs(y + 1 - cy))
                farthest = max(farthest, math.hypot(dx, dy))
    return farthest


def layer(source, scale):
    canvas = Image.new("RGBA", (px(CANVAS_DP, scale),) * 2, (0, 0, 0, 0))
    mark = scaled(source, px(MARK_DP, scale))
    offset = px((CANVAS_DP - MARK_DP) / 2, scale)
    canvas.alpha_composite(mark, (offset, offset))
    reach = farthest_visible(canvas)
    if reach > SAFE_RADIUS_DP * scale:
        sys.exit(f"the mark reaches {reach:.1f}px, past the safe zone's {SAFE_RADIUS_DP * scale}px")
    return canvas


def legacy(source, scale, round_shape):
    big = px(LEGACY_DP, scale) * SUPERSAMPLE
    inset = px((LEGACY_DP - LEGACY_SHAPE_DP) / 2, scale) * SUPERSAMPLE
    shape = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(shape)
    box = (inset, inset, big - inset - 1, big - inset - 1)
    if round_shape:
        draw.ellipse(box, fill=BACKGROUND)
    else:
        draw.rounded_rectangle(box, radius=px(LEGACY_CORNER_DP, scale) * SUPERSAMPLE, fill=BACKGROUND)
    icon = shape.resize((px(LEGACY_DP, scale),) * 2, Image.LANCZOS)
    mark = scaled(source, px(LEGACY_MARK_DP, scale))
    offset = px((LEGACY_DP - LEGACY_MARK_DP) / 2, scale)
    icon.alpha_composite(mark, (offset, offset))
    return icon


def save(image, folder, name):
    os.makedirs(os.path.join(RES, folder), exist_ok=True)
    path = os.path.join(RES, folder, name)
    image.save(path, optimize=True)
    print(f"{path}  {image.size[0]}x{image.size[1]}")


def make_icons():
    mark = load(MARK)
    monochrome = load(MONOCHROME)
    for name, scale in DENSITIES:
        folder = f"mipmap-{name}"
        save(layer(mark, scale), folder, "ic_launcher_foreground.png")
        save(layer(monochrome, scale), folder, "ic_launcher_monochrome.png")
        save(legacy(mark, scale, round_shape=False), folder, "ic_launcher.png")
        save(legacy(mark, scale, round_shape=True), folder, "ic_launcher_round.png")


if __name__ == "__main__":
    if not os.path.isdir(BRANDING) or not os.path.isdir(RES):
        sys.exit("run this from the repository root")
    make_icons()
