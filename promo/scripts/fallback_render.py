#!/usr/bin/env python3
"""
Renders docs/media/shoku-demo.mp4 without a browser: the same timeline as src/Showcase.tsx (scenes,
timings, captions, the app's fonts and the same spring physics), drawn frame by frame with Pillow and
encoded with ffmpeg.

Use it where Remotion's headless Chrome can't run; otherwise prefer `npm run render`. Small differences
from the Remotion render: no letter-spacing on titles and no scale on the phone, only movement and fades.

Usage: python3 scripts/fallback_render.py   (needs Pillow and ffmpeg; FFMPEG=/path/to/ffmpeg to override)
"""
import math
import os
import shutil
import subprocess
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SHOTS = os.path.join(ROOT, "docs", "screenshots")
OUT = os.path.join(ROOT, "docs", "media", "shoku-demo.mp4")
FONTS = os.path.join(ROOT, "android", "app", "src", "main", "res", "font")

W, H, FPS = 1080, 1920, 30
INK, MUTED, TINT, LINE, PAPER = (9, 9, 11), (107, 107, 116), (244, 244, 245), (228, 228, 231), (255, 255, 255)

# Keep in step with src/Showcase.tsx.
SCENES = [
    ("Log in seconds", "Your usual meals are one tap away.", ["01-today.png", "03-add.png"]),
    ("Describe it, or photograph it", "AI turns words or a plate photo into entries.", ["04-describe.png"]),
    ("Scan Indian packs", "Barcodes through Open Food Facts.", ["05-scan.png"]),
    ("Batches and stock", "Cook once, eat all week. Know what is at home.", ["11-batch.png", "09-library-stock.png"]),
    ("Plan your week", "Meal templates by weekday, and supplements.", ["12-plan-meals.png", "13-plan-supplements.png"]),
    ("Search everything", "Log, library, stock and plan in one box.", ["07-search-everything.png"]),
    ("Bring your own Claude key", "Calls go from your phone to Anthropic, under a monthly cap.", ["15-settings-ai.png"]),
]
INTRO, STEP, SCENE_LEN, OUTRO = 66, 100, 116, 104
OUTRO_START = INTRO - 6 + STEP * len(SCENES)
TOTAL = OUTRO_START + OUTRO
CALM = dict(damping=26, stiffness=140, mass=1)
SOFT = dict(damping=16, stiffness=100, mass=1)

PHONE_W, BEZEL = 610, 16
SCREEN_W = PHONE_W - 2 * BEZEL
SCREEN_H = round(SCREEN_W * 2400 / 1080)
PHONE_H = SCREEN_H + 2 * BEZEL
PHONE_X, PHONE_Y = (W - PHONE_W) // 2, 470
SWITCH_AT = 56


def spring(frame, damping, stiffness, mass):
    """A spring from 0 to 1 starting at rest, sampled at [frame] (same model as Remotion's spring())."""
    if frame <= 0:
        return 0.0
    t = frame / FPS
    w0 = math.sqrt(stiffness / mass)
    z = damping / (2 * math.sqrt(stiffness * mass))
    if z < 1:
        wd = w0 * math.sqrt(1 - z * z)
        return 1 - math.exp(-z * w0 * t) * (math.cos(wd * t) + (z * w0 / wd) * math.sin(wd * t))
    if z == 1:
        return 1 - math.exp(-w0 * t) * (1 + w0 * t)
    r1 = -w0 * (z - math.sqrt(z * z - 1))
    r2 = -w0 * (z + math.sqrt(z * z - 1))
    return 1 - (r2 * math.exp(r1 * t) - r1 * math.exp(r2 * t)) / (r2 - r1)


def ramp(x, x0, x1):
    return min(1.0, max(0.0, (x - x0) / (x1 - x0)))


def edges(frame, length, fade_in=14, fade_out=16):
    return min(ramp(frame, 0, fade_in), 1 - ramp(frame, length - fade_out, length))


def font(name, size, weight):
    """A variable font at [weight]; other axes (Inter's optical size) stay at their defaults."""
    f = ImageFont.truetype(os.path.join(FONTS, name), size)
    values = []
    for axis in f.get_variation_axes():
        n = axis.get("name")
        n = n.decode() if isinstance(n, bytes) else str(n)
        values.append(weight if n.lower().startswith("weight") else axis.get("default", axis["minimum"]))
    f.set_variation_by_axes(values)
    return f


def text_block(lines_of_text, fnt, color, width, line_height):
    """Wrapped text as a transparent image."""
    words, lines, cur = lines_of_text.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if fnt.getlength(trial) <= width or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    img = Image.new("RGBA", (width, int(line_height * len(lines)) + 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for i, ln in enumerate(lines):
        d.text((0, i * line_height), ln, font=fnt, fill=color + (255,))
    return img


def centered_text(text, fnt, color):
    l, t, r, b = fnt.getbbox(text)
    img = Image.new("RGBA", (r - l + 4, b + 10), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((-l + 2, 0), text, font=fnt, fill=color + (255,))
    return img


def faded(img, alpha):
    if alpha >= 0.999:
        return img
    out = img.copy()
    out.putalpha(img.getchannel("A").point(lambda a: int(a * max(0.0, alpha))))
    return out


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return m


SCREEN_MASK = rounded_mask((SCREEN_W, SCREEN_H), 70)


def load_screen(name):
    return Image.open(os.path.join(SHOTS, name)).convert("RGBA").resize((SCREEN_W, SCREEN_H), Image.LANCZOS)


def phone(screens, frame):
    """The phone with its screens; later screens rise in on CALM springs every SWITCH_AT frames."""
    screen = screens[0].copy()
    for i, s in enumerate(screens[1:], start=1):
        t = spring(frame - i * SWITCH_AT, **CALM)
        if t <= 0:
            continue
        layer = Image.new("RGBA", (SCREEN_W, SCREEN_H), (0, 0, 0, 0))
        layer.paste(s, (0, int(round((1 - t) * 40))))
        screen = Image.alpha_composite(screen, faded(layer, t))
    body = Image.new("RGBA", (PHONE_W, PHONE_H), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, PHONE_W - 1, PHONE_H - 1), radius=86, fill=INK + (255,))
    body.paste(screen, (BEZEL, BEZEL), SCREEN_MASK)
    ImageDraw.Draw(body).ellipse((PHONE_W / 2 - 11, BEZEL + 14, PHONE_W / 2 + 11, BEZEL + 36), fill=INK + (255,))
    return body


def shadow():
    pad = 160
    s = Image.new("RGBA", (PHONE_W + 2 * pad, PHONE_H + 2 * pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(s)
    d.rounded_rectangle((pad, pad + 50, pad + PHONE_W, pad + PHONE_H + 50), radius=86, fill=INK + (46,))
    far = s.filter(ImageFilter.GaussianBlur(45))
    s2 = Image.new("RGBA", s.size, (0, 0, 0, 0))
    ImageDraw.Draw(s2).rounded_rectangle((pad, pad + 12, pad + PHONE_W, pad + PHONE_H + 12), radius=86, fill=INK + (26,))
    return Image.alpha_composite(far, s2.filter(ImageFilter.GaussianBlur(12))), pad


def dots(active):
    widths = [40 if i == active else 12 for i in range(len(SCENES))]
    total = sum(widths) + 14 * (len(widths) - 1)
    img = Image.new("RGBA", (total, 12), (0, 0, 0, 0))
    d, x = ImageDraw.Draw(img), 0
    for i, w in enumerate(widths):
        d.rounded_rectangle((x, 0, x + w - 1, 11), radius=6, fill=(INK if i == active else LINE) + (255,))
        x += w + 14
    return img


def main():
    ffmpeg = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
    title_f = font("space_grotesk_variable.ttf", 84, 600)
    line_f = font("inter_variable.ttf", 40, 400)
    mark_big, mark_small = font("space_grotesk_variable.ttf", 168, 600), font("space_grotesk_variable.ttf", 150, 600)
    tag_f, outro_f = font("inter_variable.ttf", 44, 400), font("inter_variable.ttf", 42, 400)

    scenes = []
    for i, (title, line, names) in enumerate(SCENES):
        t_img = text_block(title, title_f, INK, 900, 88)
        l_img = text_block(line, line_f, MUTED, 900, 52)
        scenes.append(dict(start=INTRO - 6 + i * STEP, title=t_img, line=l_img, screens=[load_screen(n) for n in names], dots=dots(i), idx=i))
    shadow_img, pad = shadow()
    intro_mark, intro_tag = centered_text("Shoku", mark_big, INK), centered_text("A calm food log for Android", tag_f, MUTED)
    outro_mark = centered_text("Shoku", mark_small, INK)
    outro_lines = [centered_text("Local-first. Your log stays on your phone.", outro_f, MUTED), centered_text("Open source, MIT.", outro_f, MUTED)]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    enc = subprocess.Popen(
        [ffmpeg, "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
         "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", OUT],
        stdin=subprocess.PIPE,
    )
    for f in range(TOTAL):
        frame = Image.new("RGBA", (W, H), TINT + (255,))

        if f < INTRO + 10:
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            rise, sub = spring(f, **SOFT), spring(f - 10, **CALM)
            block_h = intro_mark.height + 34 + intro_tag.height
            y0 = (H - block_h) // 2
            layer.alpha_composite(intro_mark, ((W - intro_mark.width) // 2, int(y0 + (1 - rise) * 60)))
            layer.alpha_composite(faded(intro_tag, sub), ((W - intro_tag.width) // 2, int(y0 + intro_mark.height + 34 + (1 - sub) * 24)))
            frame = Image.alpha_composite(frame, faded(layer, edges(f, INTRO + 10, 8, 18)))

        for s in scenes:
            lf = f - s["start"]
            if not 0 <= lf < SCENE_LEN:
                continue
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            title, line, ph = spring(lf, **CALM), spring(lf - 6, **CALM), spring(lf - 3, **SOFT)
            leave = ramp(lf, SCENE_LEN - 16, SCENE_LEN)
            py = int(PHONE_Y + (1 - ph) * 160 - leave * 30)
            layer.alpha_composite(shadow_img, (PHONE_X - pad, py - pad))
            layer.alpha_composite(phone(s["screens"], lf), (PHONE_X, py))
            cy = 150 - leave * 24
            layer.alpha_composite(faded(s["title"], title), (90, int(cy + (1 - title) * 36)))
            layer.alpha_composite(faded(s["line"], line), (90, int(cy + s["title"].height - 20 + 22 + (1 - line) * 28)))
            layer.alpha_composite(s["dots"], ((W - s["dots"].width) // 2, H - 74 - 12))
            frame = Image.alpha_composite(frame, faded(layer, edges(lf, SCENE_LEN)))

        if f >= OUTRO_START:
            lf = f - OUTRO_START
            layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
            rise, sub = spring(lf, **SOFT), spring(lf - 8, **CALM)
            block_h = outro_mark.height + 30 + 2 * 57
            y0 = int((H - block_h) // 2 + (1 - rise) * 50)
            layer.alpha_composite(outro_mark, ((W - outro_mark.width) // 2, y0))
            for k, ln in enumerate(outro_lines):
                layer.alpha_composite(faded(ln, sub), ((W - ln.width) // 2, y0 + outro_mark.height + 30 + k * 57))
            frame = Image.alpha_composite(frame, faded(layer, ramp(lf, 0, 14)))

        enc.stdin.write(frame.convert("RGB").tobytes())
        if f % 60 == 0:
            print(f"frame {f}/{TOTAL}", file=sys.stderr)
    enc.stdin.close()
    sys.exit(enc.wait())


if __name__ == "__main__":
    main()
