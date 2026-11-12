#!/usr/bin/env python3
"""Renders the Play Store graphics in this folder from the captures in raw/.

    python3 play/graphics/render.py

Each image is an HTML page screenshotted by headless Chrome (CHROME overrides the path), then
saved as a 24-bit PNG without alpha, as Play asks. Fonts come from Google Fonts, so it needs
the network. Captions sit in the top 20% or less of each image (Play's limit for taglines).
"""
import html
import os
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
PREP = Path(tempfile.mkdtemp())  # cropped or masked copies of captures; raw/ stays unaltered
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

BG, ACCENT, TEXT, MUTED, EDGE = "#131418", "#FF9A62", "#F2EEEA", "#B9B4AF", "#2A2D35"

# rex's pixel mark and wordmark, as in app/src/main/res/drawable/tv_banner.xml.
MARK = ("M14 6h10v1h-10zM10 7h16v1h-16zM8 8h11v1h-11zM22 8h5v1h-5zM8 9h11v1h-11zM22 9h6v1h-6zM7 10h21v1h-21z"
        "M7 11h21v1h-21zM6 12h22v1h-22zM6 13h21v1h-21zM6 14h12v1h-12zM20 14h1v1h-1zM23 14h1v1h-1zM25 14h1v1h-1z"
        "M6 15h10v1h-10zM6 16h8v1h-8zM5 17h8v1h-8zM18 17h8v1h-8zM5 18h7v1h-7zM15 18h11v1h-11zM5 19h7v1h-7z"
        "M14 19h10v1h-10zM5 20h7v1h-7zM14 20h7v1h-7zM5 21h8v1h-8zM14 21h4v1h-4zM5 22h8v1h-8zM14 22h1v1h-1z"
        "M5 23h8v1h-8zM5 24h8v1h-8zM4 25h14v1h-14zM4 26h14v1h-14zM4 27h10v1h-10zM17 27h1v1h-1zM4 28h10v1h-10z")
WORD = ("M70,60h16v60h-16z M86,60h24v14h-24z M122,60h56v12h-56z M122,60h14v60h-14z M122,84h56v12h-56z "
        "M164,60h14v36h-14z M122,108h56v12h-56z M190,60h16l18,24 18,-24h16l-26,30 26,30h-16l-18,-24 -18,24h-16l26,-30z")


def mark(size):
    """The mark alone, `size` px wide, crisp pixels (glyph spans x 4..28, y 6..29 of 32)."""
    return (f'<svg width="{size}" height="{size * 23 // 24}" viewBox="4 6 24 23" shape-rendering="crispEdges">'
            f'<path fill="{ACCENT}" d="{MARK}"/></svg>')


def word(height):
    """The "rex" wordmark, `height` px tall (path spans x 70..258, y 60..120)."""
    return (f'<svg height="{height}" width="{height * 188 // 60}" viewBox="70 60 188 60" shape-rendering="crispEdges">'
            f'<path fill="{ACCENT}" d="{WORD}"/></svg>')


PHONE = [  # capture, headline, subline, last capture row shown (the tab bar starts at 2185)
    ("phone-status", "Your phone, now a media server", "Pick it by name in any of the Plex apps.", 2100),
    ("phone-plex-film", "Watch it in the Plex apps", "Public domain classics come built in.", 2180),
    ("phone-plex-playing", "Silent classics to film noir", "Streamed from the Internet Archive.", None),
    ("phone-addons", "Addons bring in what to watch", "Turn them on or off, or sync them now.", 2180),
    ("phone-addon-settings", "Your own server, too", "Type its address and pick a quality.", 1440),
    ("phone-addon-log", "See what each addon did", "Each one keeps its own log.", 2085),
    ("phone-setup", "Set up in two steps", "Name it, start it, sign in once.", 2180),
]
TV = [
    ("tv-plex-playing", "Watch on the big screen", "rex runs on this TV. The Plex app plays from it."),
    ("tv-status", "Your TV, now a media server", "It keeps running in the background."),
    ("tv-plex-library", "Classic films in your library", "Public domain films from the Internet Archive."),
    ("tv-signin-code", "Sign in with a code", "Enter it on your phone. No typing on the remote."),
    ("tv-addon-settings", "Made for the remote", "Every screen and setting works with the D-pad."),
    ("tv-addons", "Addons bring in what to watch", "Turn them on or off, or sync them now."),
]

CSS = f"""
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@500;700;800&display=block');
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
html, body {{ width: 100%; height: 100%; overflow: hidden; background: {BG}; color: {TEXT};
  font-family: Inter, -apple-system, 'Helvetica Neue', Arial, sans-serif; }}
body {{ position: relative; background:
  radial-gradient(ellipse 80% 45% at 50% 0%, rgba(255,154,98,.16), rgba(255,154,98,0) 70%), {BG}; }}
h1 {{ text-wrap: balance; font-weight: 800; letter-spacing: -0.02em; line-height: 1.08; }}
p {{ font-weight: 500; color: {MUTED}; line-height: 1.3; }}
.shot {{ position: absolute; flex: none; overflow: hidden; border: 2px solid {EDGE};
  box-shadow: 0 30px 80px rgba(0,0,0,.55), 0 0 0 1px rgba(255,255,255,.03); background: #1C1E24; }}
.shot img {{ display: block; width: 100%; }}
.card {{ display: block; border: 2px solid {EDGE}; border-radius: 40px; max-height: 100%;
  box-shadow: 0 30px 80px rgba(0,0,0,.55), 0 0 0 1px rgba(255,255,255,.03); }}
.fill {{ flex: 1; min-height: 0; display: flex; justify-content: center; align-items: center; }}
"""


def page(body, w, h):
    return f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>{body}</body></html>'


def prepared(name, box=None, blank=None):
    """A copy of a capture, cropped to `box` (left, top, right, bottom) and with the circle `blank`
    (x, y, r) painted over, as a file URI."""
    image = Image.open(RAW / f"{name}.png").convert("RGB")
    if blank:
        x, y, r = blank
        draw = ImageDraw.Draw(image)
        draw.ellipse((x - r - 12, y - r - 12, x + r + 12, y + r + 12), fill=image.getpixel((x - r - 14, y)))
        draw.ellipse((x - r, y - r, x + r, y + r), fill="#4A4D55")
    if box:
        image = image.crop(box)
    out = PREP / f"{name}-{abs(hash((box, blank)))}.png"
    image.save(out)
    return out.as_uri()


def phone_body(name, title, sub, bottom):
    caption = (f'<div style="text-align:center;flex:none"><h1 style="font-size:74px">{html.escape(title)}</h1>'
               f'<p style="font-size:38px;margin-top:22px">{html.escape(sub)}</p></div>')
    if bottom is None:
        # Playback is landscape: shown upright across the frame, with the film's page under it.
        playing = prepared(name)
        film = prepared("phone-plex-film", (0, 65, 1080, 2070))
        shots = (f'<img class="card" src="{playing}" style="width:960px;flex:none;border-radius:28px">'
                 f'<div class="fill"><img class="card" src="{film}" style="max-width:780px"></div>')
    else:
        # Status bar and tab bar cropped off; the whole card fits, shrinking if the caption is tall.
        # A short page is shown larger, so it fills the frame instead of leaving an empty card.
        width = 780 if bottom > 1600 else 940
        shots = f'<div class="fill"><img class="card" src="{prepared(name, (0, 65, 1080, bottom))}" style="max-width:{width}px"></div>'
    return (f'<div style="position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;'
            f'padding:92px 60px 64px;gap:56px">{caption}{shots}</div>')


# The Plex account's initial on Plex's side rail, blanked to a plain circle (capture x, y, radius).
AVATAR = {"tv-plex-library": (64, 467, 22)}


def tv_body(name, title, sub):
    src = prepared(name, blank=AVATAR.get(name))
    return (f'<div style="position:absolute;left:0;right:0;top:54px;text-align:center">'
            f'<h1 style="font-size:64px">{html.escape(title)}</h1>'
            f'<p style="font-size:32px;margin-top:14px">{html.escape(sub)}</p></div>'
            f'<div class="shot" style="left:256px;top:236px;width:1408px;height:796px;border-radius:22px">'
            f'<img src="{src}"></div>')


def feature_body():
    tv = (RAW / "tv-status.png").as_uri()
    return (f'<div style="position:absolute;left:64px;top:74px;width:430px">'
            f'<div style="display:flex;align-items:center;gap:22px">{mark(92)}{word(58)}</div>'
            f'<h1 style="font-size:44px;margin-top:40px">A media server for your phone or TV</h1>'
            f'<p style="font-size:23px;margin-top:20px">Watch in the Plex apps. Addons bring in what to watch.</p></div>'
            # The app on a TV, its top-left part (capture x 0..1100, y 0..825).
            f'<div class="shot" style="left:548px;top:96px;width:412px;height:309px;border-radius:16px">'
            f'<img src="{tv}" style="width:719px"></div>')


def icon_body():
    # Same proportion as the launcher icon: the mark spans 40% of the square.
    return f'<div style="position:absolute;inset:0;display:grid;place-items:center;background:{BG}">{mark(216)}</div>'


def banner_body():
    return (f'<div style="position:absolute;inset:0;display:flex;align-items:center;justify-content:center;gap:56px;'
            f'background:{BG}">{mark(300)}{word(176)}</div>')


def render(body, w, h, out):
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "page.html"
        src.write_text(page(body, w, h))
        shot = Path(tmp) / "shot.png"
        subprocess.run([CHROME, "--headless=new", f"--user-data-dir={tmp}/profile", "--hide-scrollbars",
                        "--allow-file-access-from-files", "--force-device-scale-factor=1",
                        "--virtual-time-budget=8000", f"--window-size={w},{h}", f"--screenshot={shot}",
                        src.as_uri()], check=True, capture_output=True)
        image = Image.open(shot).convert("RGB")
    assert image.size == (w, h), (out, image.size)
    image.save(out, optimize=True)


def main():
    for i, (name, title, sub, bottom) in enumerate(PHONE, 1):
        render(phone_body(name, title, sub, bottom), 1080, 1920, HERE / "phone" / f"{i:02d}-{name.removeprefix('phone-')}.png")
    for i, (name, title, sub) in enumerate(TV, 1):
        render(tv_body(name, title, sub), 1920, 1080, HERE / "tv" / f"{i:02d}-{name.removeprefix('tv-')}.png")
    render(feature_body(), 1024, 500, HERE / "feature-graphic.png")
    render(icon_body(), 512, 512, HERE / "icon-512.png")
    render(banner_body(), 1280, 720, HERE / "tv-banner.png")
    # Unaltered TV screenshots (Play's TV review wants at least one), only without the alpha channel.
    (HERE / "tv" / "unaltered").mkdir(parents=True, exist_ok=True)
    for name in ("tv-plex-playing", "tv-status", "tv-addons", "tv-addon-settings"):
        Image.open(RAW / f"{name}.png").convert("RGB").save(HERE / "tv" / "unaltered" / f"{name}.png", optimize=True)
    for out in sorted(HERE.rglob("*.png")):
        if out.parent.name != "raw":
            with Image.open(out) as im:
                print(f"{out.relative_to(HERE)}  {im.size[0]}x{im.size[1]}  {im.mode}")


if __name__ == "__main__":
    sys.exit(main())
