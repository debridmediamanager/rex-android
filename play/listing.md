# Store listing draft

**App name** (30 max): `rex: home media server`

**Short description** (80 max):
`Turn a phone, tablet or TV box into a media server the Plex apps can play from.`

**Full description** (4,000 max):

> rex turns this device into a media server. The Plex apps on your phone, TV and computer find
> it like any other server and play from it, at home and, once it's set up, away from home too.
>
> What it plays comes from addons. rex starts with one: the Internet Archive's public domain
> films, from silent classics to film noir. Add more from addon repositories you trust.
>
> - Sign in once with your Plex account. On a TV, enter the code at plex.tv/link.
> - Addons sync on their own. Turn them on or off, sync now, update them or change their settings.
> - Before you install an addon, rex shows which websites it can reach.
> - An addon that reads from your own server asks for its address and reaches only that.
> - Settings show their choices as buttons, with a line of help under each.
> - Each addon keeps its own log, so you can see what it did.
> - Converts what a player can't play, using the device's own video encoder.
> - Runs in the background with a notification you can stop it from, and can start when the
>   device turns on.
> - Works with a remote on Android TV, Google TV and Fire TV.
> - Free and without ads. No account with us.
>
> rex is not made by or affiliated with Plex, Inc. A free Plex account is enough.

**Category:** Video Players & Editors. **Tags:** media server, home media, streaming.
**Contact email:** support@debridmediamanager.com. **Website:** `https://notes.debridmediamanager.com/rex/`.

## Graphics

All in `play/graphics/`, 24-bit PNG without alpha, rebuilt by `python3 play/graphics/render.py`
from the captures in `raw/` (rex 0.2.0 from the GitHub release, 2026-11-12: phone shots on an
Android 15 emulator, TV shots on Google's Android TV 14 emulator; Plex shots on a lab Plex
account, no names shown). Sizes checked against Google's help pages that day.

| Asset | File | Play's rule |
|---|---|---|
| App icon | `icon-512.png` | 512 x 512, 32-bit PNG (alpha allowed), up to 1 MB |
| Feature graphic | `feature-graphic.png` | 1024 x 500, JPEG or 24-bit PNG, no alpha. Required |
| Phone screenshots | `phone/01..07-*.png`, 1080 x 1920 | 2 to 8, sides 320 to 3840 px, long side at most twice the short. Captions stay in the top 20% |
| TV banner | `tv-banner.png`, 1280 x 720 | Required for an Android TV app, no alpha |
| TV screenshots | `tv/01..06-*.png`, 1920 x 1080 | At least one; Android TV review also wants at least one unaltered shot of the current app: upload one from `tv/unaltered/` first (none shows the Plex account; in `tv/03` its initial is blanked) |

Phone order: Status, a film in the Plex app, playback, Addons, an addon's settings, its log, setup.
Phone shots 04 to 06, TV shot 05 (and the README's addon shots) show **Home Server**, an example addon written for
them (source in `graphics/home-server-addon/`) that no repository offers: leave those out.
**Upload** phone `01`, `02`, `03`, `07`; for TV, `tv/unaltered/tv-status.png` first, then `tv/01` to `04` and `06`. Graphics avoid Plex's logo and colours;
Plex's own player and library appear only inside the captures.
