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

**Video for the declaration** (Play asks for one): record on a phone: open rex, press Start,
pull down the notification, open a Plex app on another device and play a film, then press Stop
in the notification.
