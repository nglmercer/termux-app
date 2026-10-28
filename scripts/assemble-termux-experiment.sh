#!/bin/sh
# Experimental Termux-host build, not the repository-supported Android NDK build.
# The generated checkout and debug APK must not be treated as a release.
set -eu
if [ "$#" -ne 1 ]; then
    echo "Usage: ANDROID_HOME=... JAVA_HOME=... $0 NEW_WORKTREE_PATH" >&2
    exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK with platform 36 and NDK 29.0.14206865}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK supported by Gradle}"
if [ "$(uname -m)" != aarch64 ]; then echo "This experiment is for an aarch64 Termux host." >&2; exit 2; fi
source_root=$(git rev-parse --show-toplevel)
commit=$(git rev-parse HEAD)
destination=$1
if [ -e "$destination" ]; then echo "Destination already exists: $destination" >&2; exit 2; fi
ndk_root="$ANDROID_HOME/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64"
resource="$ndk_root/lib/clang/21"
if [ ! -d "$ndk_root/sysroot" ] || [ ! -d "$resource" ]; then echo "NDK 29 sysroot or Clang resources missing." >&2; exit 2; fi
for program in clang clang++ python3 git; do command -v "$program" >/dev/null || { echo "Missing $program" >&2; exit 2; }; done
if [ ! -x /data/data/com.termux/files/usr/bin/aapt2 ]; then echo "Termux aapt2 is required." >&2; exit 2; fi

git worktree add --detach "$destination" "$commit"
cd "$destination"
# Reuse local archives if present. Gradle checks their pinned SHA-256 values.
for arch in aarch64 arm i686 x86_64; do
    archive="$source_root/app/src/main/cpp/bootstrap-$arch.zip"
    if [ -f "$archive" ]; then cp "$archive" "app/src/main/cpp/bootstrap-$arch.zip"; fi
done
./gradlew :app:downloadBootstraps -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
mkdir -p build/termux-native-experiment
for spec in 'arm64-v8a:aarch64-linux-android21' 'armeabi-v7a:armv7a-linux-androideabi21' 'x86:i686-linux-android21' 'x86_64:x86_64-linux-android21'; do
    abi=${spec%%:*}; target=${spec#*:}
    out="$PWD/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    clang --target="$target" --sysroot="$ndk_root/sysroot" -resource-dir="$resource" \
        -std=c11 -fPIC -shared -Wall -Wextra -Werror -Os -fno-stack-protector \
        terminal-emulator/src/main/jni/termux.c -o "$out/libtermux.so"
    (cd app/src/main/cpp && clang --target="$target" --sysroot="$ndk_root/sysroot" -resource-dir="$resource" \
        -std=c11 -fPIC -shared -Wall -Wextra -Werror -Os -fno-stack-protector \
        termux-bootstrap.c termux-bootstrap-zip.S -o "$out/libtermux-bootstrap.so")
    clang++ --target="$target" --sysroot="$ndk_root/sysroot" -resource-dir="$resource" \
        -fPIC -shared -nostdlib++ termux-shared/src/main/cpp/local-socket.cpp \
        -lc++_static -lc++abi -llog -o "$out/liblocal-socket.so"
done
# Alter only this disposable checkout: use the generated JNI libraries in place of ndk-build.
python3 - <<'PY'
from pathlib import Path
for name in ('app/build.gradle', 'terminal-emulator/build.gradle', 'termux-shared/build.gradle'):
    path = Path(name)
    lines = path.read_text().splitlines(keepends=True)
    result, i, removed = [], 0, 0
    while i < len(lines):
        if lines[i].strip() == 'externalNativeBuild {':
            depth = 0
            while i < len(lines):
                depth += lines[i].count('{') - lines[i].count('}')
                i += 1
                if depth == 0:
                    break
            if depth != 0:
                raise SystemExit('Unbalanced build block: ' + name)
            removed += 1
        else:
            result.append(lines[i]); i += 1
    if removed != 2:
        raise SystemExit('Unexpected native build layout: ' + name)
    path.write_text(''.join(result))
path = Path('app/build.gradle')
text = path.read_text()
before = '        jniLibs {\n            useLegacyPackaging true'
after = '        jniLibs {\n            keepDebugSymbols += ["**/*.so"]\n            useLegacyPackaging true'
if text.count(before) != 1:
    raise SystemExit('Unexpected JNI packaging layout')
path.write_text(text.replace(before, after))
PY
TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS=0 ./gradlew :app:assembleDebug \
    -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
printf '\nExperimental debug APK: %s\n' "$PWD/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk"
printf 'Source commit: %s\n' "$commit"
printf '%s\n' 'The checkout has temporary build changes. Verify the APK and test on devices before use.'
