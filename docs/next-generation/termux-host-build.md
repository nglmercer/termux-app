# Experimental Termux-host APK assembly

The supported Gradle/NDK build cannot run the NDK 29 `ndk-build` host tools on this aarch64 Android host. `scripts/assemble-termux-experiment.sh NEW_WORKTREE_PATH` is a separate experiment: it creates a detached worktree at the current commit, cross-compiles the three native libraries with Termux Clang and the NDK 29 sysroot, replaces ndk-build with generated JNI libraries only in that worktree, and assembles a universal debug APK. It requires Termux Clang 21, Python 3, a JDK, Android SDK platform 36, NDK 29.0.14206865, Termux AAPT2, and the pinned bootstrap archives or network access to download them. The APK uses the repository's untrusted debug test key.

Example:

```sh
. ~/android-sdk/env.sh
scripts/assemble-termux-experiment.sh ~/termux-apk-scratch
apksigner verify --verbose ~/termux-apk-scratch/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

The script refuses to overwrite its destination. It leaves the temporary worktree and its build-file modifications for inspection; use `git worktree remove` after deciding they are no longer needed. This does not validate the official NDK build, reproducibility across toolchain versions, runtime behavior, signing upgrades, app and add-on compatibility, security, native symbol alignment, or Android device behavior. A differently signed `com.termux` APK cannot update an official installation; do not uninstall or replace an existing app without a verified migration and backup. This experimental artifact is not a release.

On this host, two separate experimental builds of commit `7fb9ca27dfe09e36eeeb07c773661af033f05d7a` produced the same SHA-256, `822571417ac22d502ef04c7867ace870dd4edad2986acbe8727829cb495a52a6`. `apksigner verify` reported v1 and v2 signatures valid, with warnings about unsigned META-INF entries; `zipalign -c 4` passed. `aapt2 dump badging` reported `com.termux`, minSdk 21 and targetSdk 28, and ZIP inspection found all three native libraries for each of the four configured ABIs. These are static observations on one host, not device or release validation. That commit contains the output-client workstream and does not include the separate image-import or process-monitor branches.
