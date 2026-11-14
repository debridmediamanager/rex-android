# Foreground service: special use

Play Console, App content, Foreground service permissions: declare `FOREGROUND_SERVICE_SPECIAL_USE`.

**Subtype in the manifest** (`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`):
"Runs a media server the Plex apps on this device and the home network stream from, while the
person keeps it on"

**Why no other type fits.** rex is a server: other apps (the Plex apps on this device, on TVs
and on computers) connect to it over the network, ask for libraries and stream video from it.
It plays nothing itself, so `mediaPlayback` does not apply; it is not a one-off transfer, so
`dataSync` (and its 6-hour limit) does not either. Clients can connect at any moment, so the
server has to keep running while the person wants it on, which only a foreground service allows.

**What the person sees and controls.** The service starts only when the person presses Start
(or, if they turned it on, when the device boots). Its notification shows the server's state and
has a Stop action; the Quick Settings tile and the app's Stop button end it too.

**Video for the declaration** (Play asks for a link to one): recorded 2026-11-14 on an Android 15
phone emulator with the 0.3.1 store build, 1080 x 2400, no sound: the home screen, rex opened
(Stopped), Start pressed, rex running, the notification shade pulled down showing "rex: Running ·
Ready for the Plex apps" with its Stop action, Stop pressed in the notification, the notification
gone and the app showing Stopped. The file is kept outside the repository
(`.artifacts/rex-android/v031/fgs-declaration-video.mp4` in the workspace); upload it unlisted to
YouTube (or Drive, shared by link) and paste that link into the declaration.
