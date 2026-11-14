# Store listing

Google Play copy for rex for Android 0.3.0, the first store release. The Amazon Appstore copy,
adapted from this, is in [amazon.md](amazon.md). Paste each block as it is.

Store text and images show only what a store install gets: the bundled Internet Archive addon,
adding a repository by its link, addon settings and logs, and the Plex apps playing. Nothing in
them names another addon, a debrid service or torrents.

| Field | Value |
|---|---|
| App name (30 max) | `rex: home media server` |
| Default language | English (United States) |
| App or game / Free or paid | App / Free, no in-app purchases, no ads |
| Category | Video Players & Editors |
| Tags | Media server, Home media, Streaming (pick the closest in Play's tag list) |
| Contact email | `support@debridmediamanager.com` |
| Website | `https://notes.debridmediamanager.com/rex/` |
| Privacy policy | `https://notes.debridmediamanager.com/rex/android-privacy/` (answered 200 on 2026-11-14) |
| Developer name | Ben Adrian Sarmiento (as for DMM Mobile, same developer account) |

**Short description** (80 max, this is 79):

```text
Turn a phone, tablet or TV box into a media server the Plex apps can play from.
```

**Full description** (4,000 max):

```text
rex turns this device into a media server. The Plex apps on your phone, TV and computer find it like any other server and play from it, at home and, once it's set up, away from home too.

What it plays comes from addons. rex comes with one: the Internet Archive's public domain films, from silent classics to film noir, ready to play as soon as it has synced. You can add more from addon repositories you trust, by their link.

• Sign in once with your Plex account. On a TV, enter the code at plex.tv/link on your phone.
• Addons sync on their own. Turn them on or off, sync now or change their settings.
• Before you install an addon, rex shows which websites it can reach, and it can reach nothing else.
• Each addon keeps its own log, so you can see what it did.
• Converts what a player can't play, using the device's own video encoder.
• Runs in the background with a notification you can stop it from, and can start when the device turns on.
• Works with a remote on Android TV and Google TV.
• Need help? Send your logs to support from the app, after seeing everything that would go.
• Free and without ads. No account with us.

rex is not made by or affiliated with Plex, Inc. A free Plex account is enough.
```

**Release notes for 0.3.0** (Play's "What's new", 500 max):

```text
First release on Google Play.
• The Internet Archive addon is built in: public domain films, ready for the Plex apps.
• Add addon repositories by their link. rex shows which websites an addon can reach before you install it.
• Send logs to support from the Logs screen, after seeing everything that would go.
• Needs fewer permissions: no Wi-Fi access.
```

## Graphics

All in `play/graphics/`, 24-bit PNG without alpha, rebuilt by `python3 play/graphics/render.py`
from the captures in `raw/`. The rex screens were captured on 2026-11-14 from the 0.3.0 play
build (host 0.9, no repository added, the Internet Archive addon only): phone shots on an
Android 15 emulator, TV shots on Google's Android TV 14 emulator. The Plex app shots are from
2026-11-12 and show only Internet Archive films. Plex shots use a lab Plex account and show no
account name. Sizes checked against Google's help pages on 2026-11-12.

| Asset | File | Play's rule |
|---|---|---|
| App icon | `icon-512.png` | 512 x 512, 32-bit PNG (alpha allowed), up to 1 MB |
| Feature graphic | `feature-graphic.png` | 1024 x 500, JPEG or 24-bit PNG, no alpha. Required. No "free" and no device pictures |
| Phone screenshots | `phone/01..08-*.png`, 1080 x 1920 | 2 to 8, sides 320 to 3840 px, long side at most twice the short. Captions stay in the top 20% |
| TV banner | `tv-banner.png`, 1280 x 720 | Required for an Android TV app, no alpha |
| TV screenshots | `tv/01..06-*.png`, 1920 x 1080; `tv/unaltered/*.png` | At least one; Android TV review also wants at least one unaltered shot of the current app |

**Upload, phone (all eight, in order):** Status, a film in the Plex app, playback, Addons, a
repository added by its link, the Internet Archive addon's settings, its log, setup.

**Upload, Android TV:** `tv/unaltered/tv-status.png` first (unaltered), then `tv/01` to `tv/06`.
The Plex account's initial in `tv/02` (Plex's side rail) is blanked.

Graphics avoid Plex's logo and colours; Plex's own player and library appear only inside the
captures.
