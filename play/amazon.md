# Amazon Appstore: fill-in sheet

Everything the Amazon Developer Console asks for when adding rex for Android, in the order the
console asks. Copy is adapted from [listing.md](listing.md): Amazon sells to Fire TV and Fire
tablets, so it names Fire TV, and no Google TV. The developer account is the one DMM Mobile
uses (Ben's personal Amazon login; see the DMM Mobile 1.2.0 rollout notes for sign-in and the
console's traps).

## 1. New app

| Field | Value |
|---|---|
| Platform | Android |
| App title | `rex: home media server` |
| Category | Utilities (no subcategory fits a media server; if the list offers Photo & Video or Movies & TV, prefer that) |

## 2. App file

| Field | Value |
|---|---|
| APK | `rex-android-0.3.0-amazon.apk` from the GitHub release (the `play` flavor: no addon repository added, only the bundled Internet Archive addon; one APK with arm64-v8a and armeabi-v7a, signed with the upload key) |
| Apply Amazon DRM | No |
| App SKU | `rex-android` |
| Language | English |
| Support contact | Company Profile's: `support@debridmediamanager.com`; website `https://notes.debridmediamanager.com/rex/` if the app lets you override it |

Amazon re-signs the APK with its own key, so an Amazon install can't be updated by a Play or
GitHub copy, or the other way round.

## 3. Target your app

- **Devices:** every Fire TV model and Fire tablet the console selects from the manifest (both
  ARM ABIs are in, so 32-bit Fire TV Sticks stay in). minSdk is 26 (Android 8.0, Fire OS 7), so
  Fire OS 5 and 6 devices drop out on their own. Leave "Android mobile devices" (non-Amazon
  phones) off, and Automotive off.
- **Availability:** all countries.
- **Target audience:** 18 and over; not designed for children.

### Content rating (Amazon's questionnaire: None / Moderate / Strong)

rex's own screens show only settings, addon names and logs. What it serves comes from the
bundled Internet Archive addon: public domain feature films, including film noir, horror and
war films from the 1910s to the 1960s, which the Plex apps play. The answers rate those films,
as the Play answers do (see checklist.md):

| Category | Answer |
|---|---|
| Violence | Moderate: "Fear, Tension, Distress" and "Realistic violence" (no gore) |
| Cartoon or fantasy violence | None |
| Alcohol, tobacco or drugs | Moderate: "Implied alcohol, tobacco, legal drug use" (smoking and drinking in old films) |
| Nudity | None |
| Sexual and suggestive content | None |
| Intolerance | None (some old films carry dated attitudes; change to Moderate if the reviewer disagrees) |
| Profanity or crude humor | None |
| Academic | No |
| Account creation or other personal info collected | Yes: no account with us, but rex-direct receives the server's identifier, token and IP addresses, and support logs carry device facts when the person sends them |
| Advertisements | No |
| Gambling | No |
| Location detection | No (the public IP rex-direct gets is not used for location) |
| User generated content or user-to-user communication | No |

### User data privacy

"Does your app collect user data or transfer user data to third parties?" **Yes.**
Privacy policy: `https://notes.debridmediamanager.com/rex/android-privacy/`.

| Amazon data type | Collected | Transferred to a third party | Purpose |
|---|---|---|---|
| Device or other IDs (a random app identifier and the server's identifier: to plex.tv at sign-in, the server's to rex-direct) | Yes | Yes (plex.tv, at the person's sign-in) | App functionality |
| App info and performance: crash logs and diagnostics (Send logs to support, only when pressed) | Yes | No | App functionality (support) |
| Everything else | No | | |

## 4. Appstore details

**Price:** free, no in-app items, no ads.

**Display title:** `rex: home media server`

**Short description** (1,200 max; Fire TV shows about the first 200 characters):

```text
Turn your Fire TV or Fire tablet into a media server the Plex apps play from. rex shows up in the Plex apps on your TV, phone and computer like any other Plex server. It comes with the Internet Archive's public domain films, from silent classics to film noir. Free, no ads, no account with us. A free Plex account is enough. Not made by or affiliated with Plex, Inc.
```

**Long description** (4,000 max):

```text
rex turns this device into a media server. The Plex apps on your phone, TV and computer find it like any other server and play from it, at home and, once it's set up, away from home too.

What it plays comes from addons. rex comes with one: the Internet Archive's public domain films, from silent classics to film noir, ready to play as soon as it has synced. You can add more from addon repositories you trust, by their link.

• Sign in once with your Plex account: enter the code at plex.tv/link on your phone.
• Addons sync on their own. Turn them on or off, sync now or change their settings.
• Before you install an addon, rex shows which websites it can reach, and it can reach nothing else.
• Each addon keeps its own log, so you can see what it did.
• Converts what a player can't play, using the device's own video encoder.
• Runs in the background with a notification you can stop it from, and can start when the device turns on.
• Works with the Fire TV remote on every screen.
• Need help? Send your logs to support from the app, after seeing everything that would go.
• Free and without ads. No account with us.

rex is not made by or affiliated with Plex, Inc. A free Plex account is enough.
```

**Product feature bullets** (one per line):

```text
A media server on your Fire TV or Fire tablet that the Plex apps play from
Public domain films from the Internet Archive, built in
Add addon repositories by their link and see which websites each addon can reach
Sign in to Plex with a code, no typing on the remote
Runs in the background, with a notification you can stop it from
```

**Keywords:**
`media server, home media, plex server, public domain films, internet archive, classic films, fire tv, streaming, home theater`

## Images

All under `play/graphics/`, RGB without alpha, rebuilt by `python3 play/graphics/render.py`.

| Slot | File | Size |
|---|---|---|
| Small icon | `amazon/icon-114.png` | 114 x 114 |
| Large icon | `amazon/icon-512.png` (same as the Play icon) | 512 x 512 |
| Tablet / phone screenshots (3 to 10) | `phone/01..08-*.png` (Play's phone set; 1080 x 1920 is the portrait form of Amazon's 1920 x 1080) | 1080 x 1920 |
| Promotional image (optional) | `feature-graphic.png` (Play's feature graphic) | 1024 x 500 |
| Fire TV app icon | `amazon/firetv-app-icon-1280x720.png` (mark and name inside the 882 x 448 safe area) | 1280 x 720 |
| Fire TV screenshots (3 to 10) | `tv/unaltered/tv-status.png`, then `tv/01..06-*.png` (Play's Android TV set) | 1920 x 1080 |
| Fire TV background | `amazon/firetv-background-1920x1080.png` (Internet Archive films from the Plex library row, blurred, no logo or text) | 1920 x 1080 |

The TV shots come from Google's Android TV emulator, not a Fire TV; the app looks the same on
Fire TV, which the app was tested on in 0.2.x (Fire TV Cube, armv7).

## 5. Review and submit

- Publishing start: blank (as soon as approved).
- Export compliance: tick it (the app uses only standard HTTPS).

**Testing instructions:**

```text
rex turns this device into a media server for the Plex apps. It has no content of its own: the bundled Internet Archive addon lists public domain feature films from archive.org, and the Plex apps play them from rex. No account with us is needed. A free Plex account is enough to see rex in a Plex app; without one, every rex screen still works.

Fire TV (remote)
1. Open rex. Step 1 explains what it does; select "Start rex".
2. Step 2: select "Sign in to Plex". A four-letter code appears; enter it at plex.tv/link on a phone or computer signed in to any Plex account. Or select "Skip for now".
3. Status shows RUNNING and the address rex serves on. The Addons tab lists Internet Archive, which syncs about 100 films in 10 to 30 minutes; Settings changes how many, Log shows what it did.
4. Optional: on the Addons tab, under "Add a repository", type github.com/debridmediamanager/rex-addons and select Add. Its only addon is Internet Archive, already installed.
5. Open the Plex app on any device signed in to the same Plex account: the server named on the Status tab lists the films and plays them.
6. On the Status tab, select Stop to end the server (on a tablet, the notification's Stop does the same).

Addons are JavaScript that runs inside rex in a sandbox reaching only the websites each addon names, which rex shows before installing. rex adds no repository by itself.

Contact: support@debridmediamanager.com
```
