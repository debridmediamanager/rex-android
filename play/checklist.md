# Store submission: what is decided and what Ben does

Done in the repository: the GitHub releases, the store graphics (`graphics/`), the copy and form
answers (`listing.md`, `amazon.md`, `data-safety.md`, `fgs-special-use.md`, the content rating
below) and the published privacy policy. Release 0.3.0 is the first store build.

## Decided (2026-11-14)

| Question | Decision |
|---|---|
| Stores | Google Play closed test, then production after the 14-day test; an Amazon Appstore draft |
| Play App Signing | Google's key (the default). The upload key stays the upload key, and the GitHub APKs stay signed with it |
| What Play gets | `rex-android-X.Y.Z-play.aab`: the `play` flavor, no addon repository added, only the bundled Internet Archive addon |
| What Amazon gets | `rex-android-X.Y.Z-amazon.apk`: the same `play` flavor as one APK (arm64-v8a + armeabi-v7a), because Amazon takes no AAB and its content rules are like Play's |
| rex-direct | Stays on in the store builds; the privacy policy and data safety answers say so |
| Testers | The Google Group `dmm-mobile-beta@googlegroups.com`, as for DMM Mobile |
| Contact | `support@debridmediamanager.com` |
| Website | `https://notes.debridmediamanager.com/rex/` |
| Privacy policy | `https://notes.debridmediamanager.com/rex/android-privacy/` (notes `rex/android-privacy.md`, a copy of `privacy-policy.md`: change both together) |
| Store text and images | Only what a store install gets: the Internet Archive addon, adding a repository by link, settings, the log, the Plex apps playing. No other addon, no debrid or torrent anything |

## Signing

The upload key is `~/.config/rex-android/upload.jks` (alias `upload`, password in
`upload.jks.password` beside it, both mode 600; certificate SHA-256
`39:AF:C0:A2:DA:A2:CE:E0:64:8A:57:42:CA:DE:D9:42:F0:16:3C:F2:56:05:7A:EC:0A:FA:B4:98:CE:0F:67:28`).
`verify-release.sh` refuses any APK signed with another key. Back both files up in Bitwarden:
losing them means asking Google for an upload key reset.

With Google's app signing key, Play, Amazon and GitHub installs are three identities: none
updates another, and switching means uninstalling (and losing that device's claim and addons).

## Play Console

1. Create app: name `rex: home media server`, default language English (United States), App, Free.
2. App content:
   - Privacy policy URL (above).
   - Ads: no ads.
   - App access: all functionality is available without special access. Note for review:
     "No account with us. Plex sign-in is optional and any free Plex account works; every rex
     screen works without it. The bundled Internet Archive addon syncs about 100 public domain
     films in 10 to 30 minutes."
   - Content rating: the answers below.
   - Target audience: 18 and over (not designed for children).
   - Data safety: `data-safety.md`.
   - Foreground service: special use, with the text and the video in `fgs-special-use.md`.
   - Government app, financial features, health, news: no. Advertising ID: not used (no AD_ID
     permission).
3. Store listing: `listing.md` (copy, category, contact, graphics and which files to upload).
4. Android TV: Setup, Advanced settings, Form factors, add **Android TV**; upload `tv-banner.png`
   and the TV screenshots, `tv/unaltered/tv-status.png` first. The description says "Android TV".
   Google reviews TV separately (D-pad only, no touch needed, banner present: all in the app).
5. Closed testing: create the track, upload `rex-android-0.3.0-play.aab`, release notes from
   `listing.md`, countries, testers = the Google Group above. Send for review.
6. Once Play accepts the AAB, publish the GitHub draft release `v0.3.0` (its unversioned
   `rex-android-<abi>.apk` copies become the `releases/latest/download` links only then).
7. 12 testers opted in for 14 days in a row, then apply for production.

### Content rating (IARC), proposed answers

Category "All Other App Types", answered like DMM Mobile's (submitted 2026-11-10): the app's
own screens hold no rated content, but the bundled addon serves films that are not in the
download (public domain features from the Internet Archive: noir, horror and war films among
them), which the Plex apps play, so it is rated like a streaming catalog.

| Question | Answer | Why |
|---|---|---|
| Ratings-relevant content in the app package | No | The APK holds no films or images |
| Users interact or exchange content | No | No chat, comments or sharing between users |
| Features content not in the download | Yes | The Internet Archive films |
| Violence: can contain it / primary purpose / visual / gory / only cartoony / in text | Yes / No / Yes / No / No / Yes | Classic crime, war and horror films |
| Sexual material or nudity | No | Public domain features of 1910-1960s; none sexual |
| Offensive language: can contain / focus / minor / moderate / discriminatory / sexual expletives | Yes / No / Yes / No / No / No | Mild language at most |
| Drugs: references / focus / visual / in text | Yes / No / Yes / No | Smoking and drinking on screen |
| Age-restricted products, location sharing, digital purchases, cash rewards, browser, news/education | No | |

Expected: about ESRB Teen / PEGI 12 / IARC 12+, like DMM Mobile. If Ben prefers to rate only
the app's own screens ("Utility", no content), the result would be Everyone / PEGI 3, but a
reviewer who plays a noir film could call that wrong.

## Amazon Appstore

Everything is in `amazon.md`: fields, copy, content rating, data privacy, images, testing
instructions. Upload `rex-android-0.3.0-amazon.apk`; leave it as a draft until Ben submits.

## Policy risks to watch in review

- **Plex trademark:** the name and listing say the app works with the Plex apps and that it is
  not made by Plex. The graphics use neither Plex's logo nor its colours.
- **Third-party code (addons):** addons are JavaScript the app downloads from repositories the
  person adds and runs in a sandbox that reaches only the sites the addon names. Play allows
  interpreted code that cannot reach Android APIs; say so if review asks. The store builds add no
  repository; the GitHub build adds rex-addons' own, which holds only Internet Archive. Other
  addons live in a community repository that no build adds; keep it out of the store listing and
  the pages it links to.
- **Permissions:** internet, network state, the foreground service (special use),
  notifications and start at boot; nothing else (no Wi-Fi permissions since 0.2.2;
  `verify-release.sh` fails a build that asks for one). Addons sign in to websites with codes
  the person enters on that website, and their tokens stay on the device. The addon host has no
  Spotify code; `verify-release.sh` and rex-addons' `build-android.sh` refuse a build that does.
- **Battery:** the app does not request the battery exemption permission; it opens the system
  list and tells the person what to pick.
