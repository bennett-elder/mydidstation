#!/usr/bin/env python3
"""Render the launcher icon from parameters instead of from a mystery bitmap.

The design is the DID monogram over a photo-studio laser backdrop: a slate
field, a cyan bundle entering from the mid-left, a pink bundle climbing out of
the lower left, a bright core inside each beam, and a soft scrim behind the
letters so the glyph keeps its contrast.

    python3 android/art/ic_launcher.py            # rewrite the res/ assets
    python3 android/art/ic_launcher.py out/       # also write 512 and 256

Needs Pillow (`python3 -m pip install --user Pillow`) and one heavy sans font.
Nothing else. Output is deterministic for a given Pillow and font.

Read this before changing anything: the icon is seen at 48px far more often
than at 512, and the three constants below are what decide whether it survives
there. BEAM_WIDTH and BEAM_BLUR set how solid the lines are. CORE_MIX sets how
much colour the centre of a beam keeps instead of washing to white. Thin beams
with a wide blur become a pink haze at launcher size, which is the mistake
this file exists to stop repeating.
"""

import math
import os
import shutil
import subprocess
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont
except ImportError:
    sys.exit("Pillow is required: python3 -m pip install --user Pillow")

HERE = Path(__file__).resolve()
RES = HERE.parents[1] / "app" / "src" / "main" / "res"

# Palette, sampled by eye from the reference photographs.
FIELD_TOP = (42, 38, 88)
FIELD_BOTTOM = (60, 78, 120)
PINK = (255, 64, 180)
BLUE = (34, 166, 255)
SCRIM = (10, 10, 34)

# The three that matter at 48px. See the module docstring.
BEAM_WIDTH = 42.0     # in dp, on the 108dp adaptive canvas
BEAM_BLUR = 6.0
CORE_MIX = 0.65       # share of white blended into the beam's centre line

# Everything else, in dp on the same canvas.
CORE_WIDTH = 0.28     # core line, as a fraction of BEAM_WIDTH
SCRIM_ALPHA = 80
SCRIM_RADIUS = 176
GLYPH_WIDTH = 200
GLYPH_TRACK = -4
LETTERS = "DID"

# (origin x, origin y, degrees, colour) with the origin as a fraction of the
# canvas, so a beam starts off the left edge and travels in. Angles increase
# downward, so negative climbs and positive descends. Both blues leave the left
# edge close together, which reads as a single hub fanning out, the way the
# backdrop does.
BEAMS = [
    ((-0.30, 1.05), -44.0, PINK),
    ((-0.30, 1.05), -28.0, PINK),
    ((-0.30, 0.30), 0.0, BLUE),
    ((-0.30, 0.40), 20.0, BLUE),
]

# Alternatives kept for a later switch. Replace the two blue rows above with
# either pair, rerun this script, and rebuild.
#   c1  blue hub wider apart, one steep descent and one low horizontal
#       ((-0.30, 0.00), 20.0, BLUE), ((-0.30, 0.70), 0.0, BLUE)
#   c3  descent from high up plus a low horizontal, the most even spread
#       ((-0.30, 0.10), 20.0, BLUE), ((-0.30, 0.80), 0.0, BLUE)
#   single blue, the original three beam version
#       ((-0.30, 0.50), -6.0, BLUE)

SOURCE_PX = 864       # the source render: 108dp at 8x. Every output is a resize of this.
DENSITIES = (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192))
LEGACY = 576          # 72dp, the part of the canvas a launcher actually shows

# The monogram is outlined from a heavy face. Arial Black is proprietary, so
# shipping outlines derived from it is a licensing question, and the committed
# PNGs were rendered with it on macOS. The clean fix is an SIL OFL face such as
# Archivo Black, or hand-drawn D and I paths, which are simple geometry.
#
# Lookup order is an explicit ICON_FONT, then these paths, then fontconfig on
# Linux. If the face that turns up is not Arial Black the monogram will not
# match the committed PNGs, so main() says so out loud instead of quietly
# writing a different icon.
FONT_ENV = "ICON_FONT"
FONT_NAME = "Arial Black"
FONT_CANDIDATES = (
    "/System/Library/Fonts/Supplemental/Arial Black.ttf",
    "/Library/Fonts/Arial Black.ttf",
    "~/Library/Fonts/Arial Black.ttf",
    "C:/Windows/Fonts/ariblk.ttf",
    "%LOCALAPPDATA%/Microsoft/Windows/Fonts/ariblk.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
)


def resolve_font():
    """Path to the monogram face, or exit with a message saying what we tried."""
    override = os.environ.get(FONT_ENV)
    if override:
        if not Path(override).exists():
            sys.exit(f"{FONT_ENV} points at {override}, which does not exist")
        return override

    for candidate in FONT_CANDIDATES:
        path = Path(os.path.expandvars(candidate)).expanduser()
        if path.exists():
            return str(path)

    fc_match = shutil.which("fc-match")
    if fc_match:
        result = subprocess.run(
            [fc_match, "-f", "%{file}", FONT_NAME],
            capture_output=True, text=True, check=False,
        )
        found = result.stdout.strip()
        if found and Path(found).exists():
            return found

    sys.exit(
        f"no {FONT_NAME} found. Install it, or set {FONT_ENV} to a heavy sans "
        f"font file. Searched: {', '.join(FONT_CANDIDATES)}, then fc-match."
    )


def load_font(size):
    return ImageFont.truetype(resolve_font(), size)


def field():
    """Diagonal slate gradient, drawn coarse then resized so it stays smooth."""
    steps = 64
    small = Image.new("RGB", (steps, steps))
    px = small.load()
    for y in range(steps):
        for x in range(steps):
            t = min(1.0, max(0.0, 0.5 * y / steps + 0.4 * x / steps))
            px[x, y] = tuple(
                int(FIELD_TOP[i] + (FIELD_BOTTOM[i] - FIELD_TOP[i]) * t) for i in range(3)
            )
    return small.resize((SOURCE_PX, SOURCE_PX), Image.BICUBIC).convert("RGBA")


def beam(layer, origin, degrees, width, colour):
    """A constant-width shaft from origin, long enough to leave the canvas."""
    angle = math.radians(degrees)
    dx, dy = math.cos(angle), math.sin(angle)
    nx, ny = -dy, dx
    ox, oy = origin
    length = 1.9 * SOURCE_PX
    half = width / 2.0

    def corner(t, w):
        return (ox + dx * t + nx * w, oy + dy * t + ny * w)

    ImageDraw.Draw(layer).polygon(
        [
            corner(-0.1 * SOURCE_PX, half),
            corner(length, half),
            corner(length, -half),
            corner(-0.1 * SOURCE_PX, -half),
        ],
        fill=colour,
    )


def monogram(width_dp):
    """LETTERS, scaled to width_dp and centred, as a transparent layer."""
    scale = SOURCE_PX / 432.0
    layer = Image.new("RGBA", (SOURCE_PX, SOURCE_PX), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    size = 40
    while True:
        font = load_font(size)
        box = font.getbbox(LETTERS)
        if box[2] - box[0] >= width_dp * scale:
            break
        size += 1

    advances = [font.getbbox(c)[2] - font.getbbox(c)[0] for c in LETTERS]
    track = int(GLYPH_TRACK * scale)
    total = sum(advances) + track * (len(LETTERS) - 1)
    box = font.getbbox(LETTERS)
    x = SOURCE_PX / 2 - total / 2
    y = SOURCE_PX / 2 - (box[3] - box[1]) / 2 - box[1]
    for glyph, advance in zip(LETTERS, advances):
        draw.text((x, y), glyph, font=font, fill=(255, 255, 255, 255))
        x += advance + track
    return layer


def render():
    scale = SOURCE_PX / 432.0
    canvas = field()

    halo = Image.new("RGBA", (SOURCE_PX, SOURCE_PX), (0, 0, 0, 0))
    for (ox, oy), degrees, colour in BEAMS:
        beam(halo, (ox * SOURCE_PX, oy * SOURCE_PX), degrees, BEAM_WIDTH * scale, colour + (255,))
    canvas.alpha_composite(halo.filter(ImageFilter.GaussianBlur(BEAM_BLUR * scale)))

    core = Image.new("RGBA", (SOURCE_PX, SOURCE_PX), (0, 0, 0, 0))
    for (ox, oy), degrees, colour in BEAMS:
        tint = tuple(min(255, int(c * (1 - CORE_MIX) + 255 * CORE_MIX)) for c in colour)
        width = BEAM_WIDTH * CORE_WIDTH * scale
        beam(core, (ox * SOURCE_PX, oy * SOURCE_PX), degrees, width, tint + (255,))
    canvas.alpha_composite(core.filter(ImageFilter.GaussianBlur(BEAM_BLUR * 0.25 * scale)))

    scrim = Image.new("RGBA", (SOURCE_PX, SOURCE_PX), (0, 0, 0, 0))
    r = SCRIM_RADIUS * scale
    mid = SOURCE_PX / 2
    ImageDraw.Draw(scrim).ellipse(
        (mid - r, mid - r * 0.8, mid + r, mid + r * 0.8), fill=SCRIM + (SCRIM_ALPHA,)
    )
    canvas.alpha_composite(scrim.filter(ImageFilter.GaussianBlur(56 * scale)))

    canvas.alpha_composite(monogram(GLYPH_WIDTH))
    return canvas.convert("RGB")


def main(argv):
    path = resolve_font()
    print(f"font: {path}")
    if "arial black" not in Path(path).name.lower() and "ariblk" not in Path(path).name.lower():
        print("note: not Arial Black, so this monogram will differ from the committed PNGs")
    art = render()

    (RES / "mipmap-xxxhdpi").mkdir(parents=True, exist_ok=True)
    art.resize((432, 432), Image.LANCZOS).save(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png")

    offset = (SOURCE_PX - LEGACY) // 2
    shown = art.crop((offset, offset, offset + LEGACY, offset + LEGACY))
    for bucket, px in DENSITIES:
        (RES / f"mipmap-{bucket}").mkdir(parents=True, exist_ok=True)
        shown.resize((px, px), Image.LANCZOS).save(RES / f"mipmap-{bucket}" / "ic_launcher.png")
    print(f"wrote the adaptive foreground and {len(DENSITIES)} legacy PNGs into {RES}")

    if len(argv) > 1:
        out = Path(argv[1])
        out.mkdir(parents=True, exist_ok=True)
        for px in (512, 256):
            art.resize((px, px), Image.LANCZOS).save(out / f"ic_launcher-{px}.png")
        print(f"wrote listing renders into {out}")


if __name__ == "__main__":
    main(sys.argv)
