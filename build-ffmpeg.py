#!/usr/bin/env python3
"""Build the app's ffmpeg and ffprobe from pinned, unmodified upstream sources.

ffmpeg is rex's converter and encodes on the device's MediaCodec (LGPL: there is no
software H.264 encoder in this build); ffprobe reads what a file holds.

Requires Python 3.10+, make, Perl, pkg-config, nasm and Android NDK r28c. Build trees
stay outside the repository. The source ZIP this writes must accompany every
published APK and AAB: it holds the sources, their licenses and this recipe.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parent
SOURCES = {
    "ffmpeg-9.0.1.tar.xz": {
        "url": "https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz",
        "sha256": "cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635",
    },
    "openssl-3.5.8.tar.gz": {
        "url": "https://github.com/openssl/openssl/releases/download/openssl-3.5.8/openssl-3.5.8.tar.gz",
        "sha256": "a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2",
    },
}
TARGETS = {
    "arm64-v8a": ("aarch64", "aarch64-linux-android", "android-arm64"),
    "armeabi-v7a": ("arm", "armv7a-linux-androideabi", "android-arm"),
    "x86_64": ("x86_64", "x86_64-linux-android", "android-x86_64"),
}
# Archives another checkout already downloaded are read from there, never written.
SHARED_CACHES = [Path.home() / ".cache/zurg-android-native"]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(arguments, cwd, env):
    subprocess.run([str(value) for value in arguments], cwd=cwd, env=env, check=True)


def archive_path(cache, name, source):
    for directory in [cache, *SHARED_CACHES]:
        candidate = directory / name
        if candidate.exists() and sha256(candidate) == source["sha256"]:
            return candidate
    archive = cache / name
    temporary = archive.with_suffix(archive.suffix + ".part")
    with urllib.request.urlopen(source["url"], timeout=120) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    temporary.replace(archive)
    if sha256(archive) != source["sha256"]:
        raise RuntimeError(f"Source checksum mismatch: {name}")
    return archive


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    ndk = Path(os.environ.get("ANDROID_NDK_HOME", sdk / "ndk/28.2.13676358"))
    host = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
    toolchain = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    cache = Path(os.environ.get("REX_ANDROID_NATIVE_CACHE", Path.home() / ".cache/rex-android-native"))
    cache.mkdir(parents=True, exist_ok=True)
    source_dir = cache / "source"
    source_dir.mkdir(exist_ok=True)
    archives = {}
    for name, source in SOURCES.items():
        archives[name] = archive_path(cache, name, source)
        # Extract only verified archives. A fresh version never reuses another
        # release's tree, and a failed extraction cannot mark it complete.
        extracted = source_dir / name.split(".tar.")[0]
        marker = extracted / ".extracted"
        if not marker.exists():
            if extracted.exists():
                shutil.rmtree(extracted)
            with tarfile.open(archives[name]) as bundle:
                bundle.extractall(source_dir, filter="data")
            marker.touch()

    recipe = Path(__file__)
    fingerprint = hashlib.sha256(recipe.read_bytes() + (ndk / "source.properties").read_bytes()).hexdigest()
    jobs = os.environ.get("REX_ANDROID_JOBS", str(os.cpu_count() or 2))
    for abi in os.environ.get("REX_ANDROID_ABIS", "arm64-v8a armeabi-v7a x86_64").split():
        arch, triple, openssl_target = TARGETS[abi]
        build = cache / "build" / fingerprint / abi
        binary = build / "libffprobe.so"
        converter = build / "libffmpeg.so"
        if not binary.exists() or not converter.exists():
            build.mkdir(parents=True, exist_ok=True)
            ssl_build = build / "openssl"
            ssl_build.mkdir(exist_ok=True)
            ssl_prefix = build / "openssl-install"
            env = dict(os.environ, ANDROID_NDK_ROOT=str(ndk), PATH=f"{toolchain}:{os.environ['PATH']}")
            run([source_dir / "openssl-3.5.8/Configure", openssl_target,
                 "-D__ANDROID_API__=26", "no-shared", "no-tests", "no-apps",
                 f"--prefix={ssl_prefix}", "--libdir=lib"], ssl_build, env)
            run(["make", f"-j{jobs}"], ssl_build, env)
            run(["make", "install_sw"], ssl_build, env)

            probe_build = build / "ffmpeg"
            probe_build.mkdir(exist_ok=True)
            env.update(PKG_CONFIG_LIBDIR=str(ssl_prefix / "lib/pkgconfig"), PKG_CONFIG_PATH="")
            run([source_dir / "ffmpeg-9.0.1/configure", "--target-os=android", f"--arch={arch}",
                 "--enable-cross-compile", f"--cc={toolchain / (triple + '26-clang')}",
                 f"--cxx={toolchain / (triple + '26-clang++')}",
                 f"--ar={toolchain / 'llvm-ar'}", f"--ranlib={toolchain / 'llvm-ranlib'}",
                 f"--strip={toolchain / 'llvm-strip'}", "--pkg-config-flags=--static",
                 "--disable-autodetect", "--disable-shared", "--enable-static",
                 "--disable-ffplay", "--disable-doc", "--disable-debug",
                 # The full set, as rex's conversions use stock filters and muxers and its probe
                 # reads lavfi, an avdevice input. MediaCodec is the only H.264 encoder.
                 "--enable-mediacodec", "--enable-jni",
                 "--enable-small", "--enable-pic", "--enable-openssl", "--enable-version3", "--enable-zlib",
                 "--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384",
                 "--extra-libs=-lmediandk -landroid"],
                probe_build, env)
            run(["make", f"-j{jobs}", "ffprobe", "ffmpeg"], probe_build, env)
            for program, destination in [("ffprobe", binary), ("ffmpeg", converter)]:
                run([toolchain / "llvm-strip", "--strip-unneeded", probe_build / program], probe_build, env)
                shutil.copy2(probe_build / program, destination)
            # Only the two binaries are kept; the trees are rebuilt from the sources if needed.
            for tree in [ssl_build, ssl_prefix, probe_build]:
                shutil.rmtree(tree)
        libraries = ROOT / "app/src/main/jniLibs" / abi
        libraries.mkdir(parents=True, exist_ok=True)
        for built in [binary, converter]:
            destination = libraries / built.name
            shutil.copy2(built, destination)
            destination.chmod(0o755)
            print(f"Built {built.name} for {abi}: {destination.stat().st_size} bytes", flush=True)

    licenses = ROOT / "app/src/main/assets/native-licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    for source, name in [
        ("ffmpeg-9.0.1/COPYING.LGPLv3", "FFmpeg-LGPL-3.0.txt"),
        ("ffmpeg-9.0.1/COPYING.GPLv3", "GPL-3.0.txt"),
        ("ffmpeg-9.0.1/LICENSE.md", "FFmpeg-LICENSE.md"),
        ("openssl-3.5.8/LICENSE.txt", "OpenSSL-Apache-2.0.txt"),
    ]:
        shutil.copy2(source_dir / source, licenses / name)
    manifest = json.dumps({"sources": SOURCES, "ndk": (ndk / "source.properties").read_text(),
                           "recipe_sha256": sha256(recipe)}, indent=2) + "\n"
    (licenses / "sources.json").write_text(manifest)
    version = os.environ.get("REX_ANDROID_VERSION", "0.1.0-dev")
    artifacts = ROOT / "artifacts"
    artifacts.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(artifacts / f"rex-android-{version}-native-source.zip", "w", zipfile.ZIP_STORED) as bundle:
        for name, archive in archives.items():
            bundle.write(archive, name)
        bundle.write(recipe, "build-ffmpeg.py")
        bundle.write(ROOT / "NATIVE-LICENSES.md", "NATIVE-LICENSES.md")
        bundle.writestr("sources.json", manifest)
        for license_file in sorted(licenses.iterdir()):
            bundle.write(license_file, f"licenses/{license_file.name}")


if __name__ == "__main__":
    main()
