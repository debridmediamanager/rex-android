# Play Console: what Ben does

Done so far: the app is released on GitHub, the graphics are made and the privacy policy is
published. No Play Console app exists yet and nothing is uploaded there.

## Before the first upload

1. **Decide the signing setup.** The upload key is `~/.config/rex-android/upload.jks` (alias
   `upload`, password in `upload.jks.password` beside it, both mode 600; SHA-256
   `39:AF:C0:A2:DA:A2:CE:E0:64:8A:57:42:CA:DE:D9:42:F0:16:3C:F2:56:05:7A:EC:0A:FA:B4:98:CE:0F:67:28`).
   Back both files up somewhere safe (Bitwarden): losing them means asking Google for an upload key reset.
   - *Google-generated app signing key* (default): Play re-signs what it ships. The GitHub APKs
     stay signed with the upload key, so a sideloaded copy and a Play copy cannot update each
     other; switching means uninstalling (and losing that device's claim and addons).
   - *Use this key as the app signing key* ("Use a different key" at enrollment, uploaded with
     Play's PEPK tool), then create a new upload key: Play and GitHub copies then share one
     identity and update each other. Choose this if sideloading on Fire TV matters.
2. **Decide on rex-direct.** rex sends its server token and IP addresses to
   debridmediamanager.com once signed in (see privacy-policy.md). Keep it (Plex apps reach rex over
   HTTPS away from home) or ship the app with `REX_DIRECT_URL=off`. The privacy policy and the
   data safety answers say it is on.
3. Done: the privacy policy is at <https://notes.debridmediamanager.com/rex/android-privacy/>
   (notes `rex/android-privacy.md`, a copy of `play/privacy-policy.md`: change both together).
   Its contact is support@debridmediamanager.com.
4. Graphics are made: `play/graphics/`, listed with Play's rules in `listing.md`. Upload the
   shots `listing.md` names: the ones showing the example Home Server addon stay out, because no
   repository offers it.

## Create the app

5. Create app: name `rex: home media server`, default language English, App, Free.
6. App content:
   - Privacy policy URL (step 3).
   - Ads: no ads.
   - App access: all functions available without special access (a Plex account is the
     person's own; give reviewers a note that a free Plex account works).
   - Content rating questionnaire: a media server with no content of its own; addons can add
     public domain films. Expect "Everyone"/PEGI 3 for the app itself; answer the
     user-generated/third-party content questions honestly (addons are third-party code).
   - Target audience: 18+ (not designed for children).
   - Data safety: answers in `data-safety.md`.
   - Foreground service: declare special use, text and video per `fgs-special-use.md`.
   - Government app, financial features, health: no.
7. Store listing: copy from `listing.md`; category Video Players & Editors.
8. Android TV: under Setup, Advanced settings, Form factors, add **Android TV**; upload
   `tv-banner.png` and the TV screenshots, at least one of them from `tv/unaltered/`. The description
   must say "Android TV" (it does). Google reviews TV apps separately (D-pad only, no touch needed, banner present:
   all done in the app).

## Upload and test

9. Push a `vX.Y.Z` tag. CI builds, signs and attaches the APKs, AAB and native source ZIP to a
   **draft** GitHub release (needs the three `REX_ANDROID_*` secrets, already set on the repo).
10. Upload `rex-android-X.Y.Z-play.aab` (the `play` flavor: no repository added, only the
    bundled Internet Archive addon, so review sees public domain films working out of the box) to
    an **internal testing** track, add testers, install from
    Play on a phone and a Google TV, and check: rex starts, its programs run (Play installs the
    split for the device), sign-in works, the Internet Archive addon syncs.
11. Publish the GitHub draft release with the same files.
12. Move to closed testing, then production. New personal developer accounts must run a closed
    test with at least 12 testers for 14 days before production access.

## Policy risks to watch in review

- **Plex trademark:** the name and listing say the app works with the Plex apps and that it is
  not made by Plex. Don't use Plex's logo or colours in graphics.
- **Third-party code (addons):** addons are JavaScript the app downloads from repositories the
  person adds and runs in a sandbox that reaches only the sites the addon names. Play allows
  interpreted code that cannot reach Android APIs; say so if review asks. The Play build adds no
  repository by itself; the GitHub build adds rex-addons' own, which holds only Internet Archive.
  The Stremio, YouTube and Spotify addons are in rex-community-addons, which no build adds; keep
  that repository out of the store listing and the pages it links to.
- **Permissions:** internet, network state, the foreground service (special use),
  notifications and start at boot; nothing else. Addons sign in to websites with codes the person
  enters on that website, and their tokens stay on the device. The addon host has no
  Spotify code; rex-addons' `build-android.sh` refuses to build one that does.
- **Battery:** the app does not request the battery exemption permission; it opens the system
  list and tells the person what to pick.
