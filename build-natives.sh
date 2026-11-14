#!/usr/bin/env bash
# Builds rex and the rex-addons host for every ABI from the commits rex.ref and
# rex-addons.ref pin, into app/src/main/jniLibs/<abi>/librex.so and librexaddons.so.
# rex-addons also provides the addon the app installs on first start.
set -euo pipefail
cd "$(dirname "$0")"
ndk=${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME}/ndk/28.2.13676358}
case "$(uname -s)" in Darwin) host=darwin-x86_64 ;; Linux) host=linux-x86_64 ;; *) echo 'Build on Linux or macOS' >&2; exit 1 ;; esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
cache=${REX_ANDROID_NATIVE_CACHE:-$HOME/.cache/rex-android-native}
abis=${REX_ANDROID_ABIS:-arm64-v8a armeabi-v7a x86_64}

# checkout <name> <ref file>: a clone of github.com/debridmediamanager/<name> at the pinned commit.
checkout() {
    local source="$cache/$1" ref
    ref=$(tr -d '[:space:]' < "$2")
    [ -d "$source/.git" ] || git clone --quiet "https://github.com/debridmediamanager/$1" "$source"
    git -C "$source" cat-file -e "$ref^{commit}" 2>/dev/null || git -C "$source" fetch --quiet origin "$ref"
    git -C "$source" checkout --quiet --detach "$ref"
    printf '%s' "$source"
}

# cargo_android <manifest> <binary> <library name>: one Rust binary, built and stripped per ABI.
cargo_android() {
    local manifest=$1 binary=$2 library=$3 target triple clang linker upper out
    for target in $abis; do
        case "$target" in
            arm64-v8a) triple=aarch64-linux-android clang=aarch64-linux-android ;;
            # Rust names the 32-bit target armv7, the NDK's compiler armv7a.
            armeabi-v7a) triple=armv7-linux-androideabi clang=armv7a-linux-androideabi ;;
            x86_64) triple=x86_64-linux-android clang=x86_64-linux-android ;;
            *) echo "Unknown ABI: $target" >&2; exit 1 ;;
        esac
        out="app/src/main/jniLibs/$target/$library"
        mkdir -p "$(dirname "$out")"
        linker="$toolchain/${clang}26-clang"
        upper=$(printf '%s' "$triple" | tr 'a-z-' 'A-Z_')
        env "CC_${triple//-/_}=$linker" "AR_${triple//-/_}=$toolchain/llvm-ar" \
            "CARGO_TARGET_${upper}_LINKER=$linker" \
            "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
            CARGO_TARGET_DIR="$cache/target" \
            cargo build --quiet --release --locked --manifest-path "$manifest" --target "$triple" --bin "$binary"
        "$toolchain/llvm-strip" --strip-unneeded -o "$out" "$cache/target/$triple/release/$binary"
        chmod 755 "$out"
        # Android 15+ devices with 16 KB pages refuse to map a segment aligned for 4 KB.
        "$toolchain/llvm-readelf" -lW "$out" | awk '/LOAD/ { if ($NF != "0x4000" && $NF != "0x10000") { print "Invalid ELF alignment: " $0; exit 1 } }'
        echo "Built $library for $target: $(wc -c < "$out" | tr -d ' ') bytes"
    done
}

# rust_libraries <manifest>: every Rust library a program links, with its license and where its text is.
rust_libraries() {
    cargo metadata --locked --format-version 1 --filter-platform aarch64-linux-android --manifest-path "$1" |
        jq -r '.packages[] | select(.source != null) | "\(.name) \(.version): \(.license // "see its page"), \(.repository // .homepage // "crates.io/crates/\(.name)")"'
}

rex=$(checkout rex rex.ref)
cargo_android "$rex/rust/Cargo.toml" rex librex.so
mkdir -p app/src/main/assets/native-licenses
git -C "$rex" show HEAD:LICENSE > app/src/main/assets/native-licenses/rex-LICENSE.txt

# An empty rex-addons.ref builds an app without addons; verify-release.sh refuses to sign one.
if [ -n "$(tr -d '[:space:]' < rex-addons.ref)" ]; then
    addons=$(checkout rex-addons rex-addons.ref)
    # The host's own script: QuickJS needs bindings generated for Android at build time.
    ABIS="$abis" OUT="$PWD/app/src/main/jniLibs" CARGO_TARGET_DIR="$cache/target" "$addons/build-android.sh"
    rm -rf app/src/main/assets/addons
    mkdir -p app/src/main/assets/addons
    cp -R "$addons/addons/internet-archive" app/src/main/assets/addons/
    git -C "$addons" show HEAD:LICENSE > app/src/main/assets/native-licenses/rex-addons-LICENSE.txt
else
    echo "rex-addons.ref is empty: building without addons" >&2
fi

# Settings, Licenses shows it. A blank line every 20 entries: a remote scrolls that page one paragraph at a time.
{
    echo 'rex and the addon host are built with these Rust libraries (the addon host with QuickJS, in rquickjs-sys). Each is under the license named beside it; its text is at the address that follows.'
    echo
    { rust_libraries "$rex/rust/Cargo.toml"; [ -z "${addons:-}" ] || rust_libraries "$addons/Cargo.toml"; } | sort -u | awk '{ print } NR % 20 == 0 { print "" }'
    echo
    echo 'The app itself uses AndroidX, Jetpack Compose, OkHttp, Okio and the kotlinx libraries, all under the Apache License 2.0 (its text is on this page, under OpenSSL-Apache-2.0.txt).'
} > app/src/main/assets/native-licenses/Libraries.txt
