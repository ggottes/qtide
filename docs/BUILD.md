# Building the zip

A mochy zip module is two files:

```
qtide.mochyymodule.zip
├── manifest.json     what mochy reads first: id, name, resources, code
└── module.apk        the module's own code, signed with mochy's key
```

The apk is **not installed**. Mochy unpacks it and loads `ModuleSide` out of it into its own process.

## What the build does

```
module/src + api/        ──kotlinc──▶  out-classes/       .class files
out-classes + stdlib     ──d8──────▶  dex/classes.dex      dex, libraries folded in
tools/AndroidManifest    ──aapt2───▶  apk-build/base.apk   the shell
base.apk + classes.dex   ──zip─────▶  unsigned.apk
unsigned.apk             ──zipalign─▶ aligned.apk
aligned.apk              ──apksigner─▶ module.apk
manifest.json + module.apk ──zip───▶ build/qtide[-debug].mochyymodule.zip
```

`build.sh` does exactly that, in order, with no Gradle daemon (which needs a loopback socket some
sessions do not allow).

## Requirements

| thing | where | env |
| --- | --- | --- |
| Kotlin compiler | from the Gradle cache, `~/.gradle/caches/…/kotlin-compiler-embeddable` | edit `toolchain.sh` if you use a different version |
| `kotlin-stdlib`, `kotlinx-coroutines`, `kotlinx-serialization` | same cache | `toolchain.sh` |
| `d8`, `aapt2`, `zipalign`, `apksigner` | Android SDK build-tools | `ANDROID_SDK` (default `/c/Android/Sdk`) |
| `android.jar` | `platforms/android-36` | `toolchain.sh` |
| a key | debug keystore at `~/.android/debug.keystore` | `RELEASE=1 KEYSTORE=… KS_PASS=… KEY_ALIAS=…` |
| Python 3 | to add the dex to the apk and write the zip | — |

## Debug and release

```sh
./build.sh
# built build/qtide-debug.mochyymodule.zip (debug key)

RELEASE=1 KEYSTORE=$HOME/keys/qtide.jks KS_PASS=secret KEY_ALIAS=qtide ./build.sh
# built build/qtide.mochyymodule.zip (release key)
```

Name them **`…-debug.mochyymodule.zip`** and **`…mochyymodule.zip`**: mochy looks for `mochyymodule`
in the release's filename, and takes the `debug` one on a debug/fast build and the other on a release
build.

## Publish so people get updates

```sh
gh release create v1.0 build/qtide.mochyymodule.zip --title "Qtide 1.0"
```

Keep `manifest.json`'s `id` the same forever; bump `version` on every release. People who added the
project link see an **update** button within a few hours.

## When mochy changes its api

`api/` holds mochy's own sources. When `MODULE_API_VERSION` goes up, copy the new
`modules/api` and `modules/bridge` files in, rebuild, and bump this module's version. A module built
for another api is skipped by mochy with a log line, not a crash.
