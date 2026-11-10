# Native programs and their licenses

The app runs four programs of its own, each packaged as a file in the APK's native
library folder (`lib/<abi>/`), the only place Android lets an app start a program from:

| File | What it is | License | Source |
|---|---|---|---|
| `librex.so` | rex, the media server for the Plex apps | PolyForm Noncommercial 1.0.0 | [debridmediamanager/rex](https://github.com/debridmediamanager/rex) at the commit in `rex.ref` |
| `librexaddons.so` | the addon host, which runs addons and feeds rex | see `rex-addons-LICENSE.txt` | [debridmediamanager/rex-addons](https://github.com/debridmediamanager/rex-addons) at the commit in `rex-addons.ref` |
| `libffmpeg.so` | rex's converter | LGPL 3.0 or later | FFmpeg 9.0.1, unmodified |
| `libffprobe.so` | reads what a media file holds | LGPL 3.0 or later | FFmpeg 9.0.1, unmodified |

These are programs, not JNI libraries: the app starts them as separate processes and
talks to them over their command line and loopback HTTP. It links none of their code.

FFmpeg is built from unmodified FFmpeg 9.0.1 under LGPL 3.0 or later, with OpenSSL 3.5.8
under Apache 2.0 for HTTPS input. GPL-only and nonfree FFmpeg components are not enabled,
so there is no software H.264 encoder: conversions encode on the device's MediaCodec.
FFmpeg's LGPL 3.0 terms incorporate the accompanying GPL 3.0 text.

Every release includes `rex-android-<version>-native-source.zip` with the exact upstream
source archives, their hashes, the complete license texts and the build recipe. The app
carries the same license texts under Settings, Licenses. This is free software supplied
without warranty; the license texts describe your rights to modify and redistribute it.

To rebuild FFmpeg, extract the source ZIP and run `python3 build-ffmpeg.py` with Python
3.10 or newer, make, Perl, pkg-config, nasm, the Android SDK and NDK 28.2.13676358.
Set `ANDROID_HOME` to the SDK and `REX_ANDROID_NATIVE_CACHE` to the folder holding the
source archives; the script checks their hashes before extracting them.
`REX_ANDROID_ABIS` picks space-separated ABIs and `REX_ANDROID_JOBS` the parallelism.
On macOS set `SDKROOT` to a Command Line Tools SDK that links (for example
`/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk`), since FFmpeg builds a few
host tools with the system compiler.

Replacing a program means rebuilding and signing the APK with your own key. Android
then installs it only after the copy signed with another key is uninstalled.
