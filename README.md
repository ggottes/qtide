# Qtide — Qobuz, Tidal and Deezer in Mochy

A [Mochy](https://realidkroo.github.io/Mochy-Music/wiki/) module that plays songs from your own
accounts. One module, three catalogues, each with its own token on the module's page.

| service | what you need | what you get |
| --- | --- | --- |
| **Tidal** | a refresh token | FLAC 16/44.1 and, where the master allows, **hi-res up to 24-bit** (as DASH) |
| **Qobuz** | a user token (`X-User-Auth-Token`) | FLAC 16/44.1, up to 24-bit on Studio accounts |
| **Deezer** | the `arl` cookie | MP3 320 (FLAC on a HiFi account) |

Songs are asked for in the order **Tidal → Qobuz → Deezer**; the first one that has the recording
wins. Quality is a chip on the module's page: Hi-Res, Lossless or High.

> This is a third-party module. It is not made or supported by Mochy's author.

## Install

1. Take `qtide.mochyymodule.zip` (a built one is attached to the newest release).
2. Send it to the phone.
3. Mochy → settings → modules → **add a module** → **add from a file** → pick the zip → **add**.
4. Open **Qtide**'s page, fill in the tokens you have, and turn **enable** on.
5. On the modules page, drag Qtide above YouTube Music so it is asked first.

A debug build of Mochy only accepts a zip signed with the **debug** key, a release build one signed
with the **release** key. Take the zip that matches your app: `qtide-debug.mochyymodule.zip` or
`qtide.mochyymodule.zip`.

## Tokens

| field | where it comes from |
| --- | --- |
| **Tidal refresh token** | sign in at tidal.com, or reuse one from streamrip/tiddl. The module renews its access token itself. |
| **Qobuz user token** | the Qobuz web player → developer tools → Network → any request's `X-User-Auth-Token` header. |
| **Qobuz app id and secret** (optional) | `appId:secret` if you have your own pair; left empty they are read from Qobuz's player script. |
| **Deezer ARL** | deezer.com → developer tools → Application → Cookies → `arl`. |

Leave a field empty to skip that service. Tokens live on the phone only, in the module's own
storage; nothing is sent anywhere else.

## How a song is played

```
play → search the services in order (first artist + title, matched by recording)
     → Tidal / Qobuz: a signed link to the file
     → Tidal hi-res: a DASH manifest (2 KB) handed over as a data: uri;
                     the player fetches its segments as it plays
     → Deezer: the file is downloaded, decrypted here, saved, and played as one file
```

## Building

### The quick way (no Gradle daemon)

```sh
./build.sh              # -> build/qtide-debug.mochyymodule.zip  (debug key)
RELEASE=1 KEYSTORE=… KS_PASS=… KEY_ALIAS=… ./build.sh
                        # -> build/qtide.mochyymodule.zip         (your release key)
```

`build.sh` compiles with the Kotlin compiler, dexes with `d8`, packs with `aapt2`, signs with
`apksigner` and zips the manifest next to the apk. `toolchain.sh` points at the jars; override
`ANDROID_SDK` if your SDK is not at `/c/Android/Sdk`.

### The ordinary way (Gradle, Android Studio)

`./gradlew build` compiles the same sources into a jar. It is a **JVM** project, not an Android app:
the apk is a thin shell around the jar's classes, and `build.sh` makes that shell. Docs:
[build](docs/BUILD.md) · [module format](docs/MODULE.md) · [signing](docs/SIGNING.md).

## Layout

```
api/                      mochy's own module api + bridge, copied in (see api/README.md)
module/src/com/qtide/
├── QtideModule.kt        the module: sources, quality, the flow Mochy calls
├── Tidal.kt              session, search, stream (links + hi-res DASH)
├── Qobuz.kt              session (app pair scraped or given), search, signed stream
├── Deezer.kt             ARL session, search, licence stream
└── Json.kt               a small json reader/writer: the module carries no dependencies but kotlin
tools/
├── AndroidManifest.xml   the apk's shell: the entry class mochy reads
└── tokens.md             your own test tokens (git-ignored)
module/manifest.json      what mochy shows about the module
build.sh, toolchain.sh    the build
```

## Limits

- **Tidal hi-res** is DASH. Mochy's player takes it; the module's **test button** plays without
  labelling the item as DASH, so it can report `Play` as failed for that tier while real playback
  works. Pick Lossless if you want the test to be green.
- **Dolby Atmos** is not reachable through the public Tidal api: the atmos row answers with the
  stereo rendition. See `notes` in the parent project, or just pick Lossless.
- **Deezer** only streams FLAC on a HiFi account; without one it plays MP3 320.
