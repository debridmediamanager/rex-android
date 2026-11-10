#!/usr/bin/env bash
# Checks the signed release APKs and AAB before they are published, and copies them into
# artifacts/ under their release names. Run after build-natives.sh, build-ffmpeg.py and
# :app:assembleRelease :app:bundleRelease, with REX_ANDROID_VERSION set.
set -euo pipefail
cd "$(dirname "$0")"
version=${REX_ANDROID_VERSION:?Set REX_ANDROID_VERSION to the release version}
sdk=${ANDROID_HOME:?Set ANDROID_HOME}
build_tools="$sdk/build-tools/36.0.0"
ndk=${ANDROID_NDK_HOME:-$sdk/ndk/28.2.13676358}
case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; *) host=linux-x86_64 ;; esac
readelf="$ndk/toolchains/llvm/prebuilt/$host/bin/llvm-readelf"
apks=app/build/outputs/apk/release
bundle=app/build/outputs/bundle/release/app-release.aab
programs="librex.so librexaddons.so libffmpeg.so libffprobe.so"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
mkdir -p artifacts

python3 - "$version" <<'PY'
import hashlib, runpy, sys, zipfile
sources = runpy.run_path('build-ffmpeg.py')['SOURCES']
with zipfile.ZipFile(f'artifacts/rex-android-{sys.argv[1]}-native-source.zip') as bundle:
    assert bundle.read('build-ffmpeg.py') == open('build-ffmpeg.py', 'rb').read(), 'The source ZIP holds another recipe'
    for name, source in sources.items():
        assert hashlib.sha256(bundle.read(name)).hexdigest() == source['sha256'], f'Wrong source in the ZIP: {name}'
    for name in ['FFmpeg-LGPL-3.0.txt', 'GPL-3.0.txt', 'OpenSSL-Apache-2.0.txt']:
        assert bundle.read(f'licenses/{name}'), f'The source ZIP lacks {name}'
PY

# check_programs <archive> <prefix> <abi>: every program is in it, identical to the one built, and
# Android 15+ devices with 16 KB pages can map it.
check_programs() {
    local archive=$1 prefix=$2 abi=$3 program
    for program in $programs; do
        unzip -p "$archive" "${prefix}lib/$abi/$program" > "$scratch/$program" 2>/dev/null || true
        test -s "$scratch/$program" || { echo "$archive has no $program for $abi" >&2; exit 1; }
        cmp "$scratch/$program" "app/src/main/jniLibs/$abi/$program"
        if [ "$abi" != armeabi-v7a ]; then
            "$readelf" -lW "$scratch/$program" |
                awk '/LOAD/ { count++; if ($NF != "0x4000" && $NF != "0x10000") bad=1 } END { if (!count || bad) exit 1 }'
        fi
    done
    for license in FFmpeg-LGPL-3.0.txt GPL-3.0.txt OpenSSL-Apache-2.0.txt rex-LICENSE.txt rex-addons-LICENSE.txt; do
        unzip -p "$archive" "${prefix}assets/native-licenses/$license" | cmp - "app/src/main/assets/native-licenses/$license"
    done
    unzip -Z1 "$archive" | grep -q "^${prefix}assets/addons/internet-archive/manifest.json$" ||
        { echo "$archive does not carry the bundled addon" >&2; exit 1; }
}

for abi in arm64-v8a armeabi-v7a x86_64; do
    apk="$apks/app-$abi-release.apk"
    "$build_tools/apksigner" verify "$apk"
    "$build_tools/zipalign" -c -P 16 4 "$apk"
    "$build_tools/aapt" dump badging "$apk" > "$scratch/manifest"
    grep -qF "package: name='com.debridmediamanager.rex'" "$scratch/manifest"
    grep -qF "versionName='$version'" "$scratch/manifest"
    grep -qF 'leanback-launchable-activity:' "$scratch/manifest"
    if grep -q 'application-debuggable' "$scratch/manifest"; then echo "Refusing a debuggable APK" >&2; exit 1; fi
    # The programs run from nativeLibraryDir, which only an extracting install fills.
    "$build_tools/aapt" dump xmltree "$apk" AndroidManifest.xml | grep -q 'extractNativeLibs.*0xffffffff'
    check_programs "$apk" "" "$abi"
    cp "$apk" "artifacts/rex-android-$version-$abi.apk"
done

jarsigner -verify "$bundle" | grep -q 'jar verified'
for abi in arm64-v8a armeabi-v7a x86_64; do check_programs "$bundle" "base/" "$abi"; done
cp "$bundle" "artifacts/rex-android-$version.aab"
echo "Verified signed APKs and AAB for $version"
