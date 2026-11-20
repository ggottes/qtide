# Qtide — Qobuz, Tidal and Deezer in Mochy

Three [Mochy](https://realidkroo.github.io/Mochy-Music/wiki/) modules that play songs from your own
accounts. Each service is its own zip with its own switch and its own token, so you take only the
ones you need.

| zip | module | what you need | what you get |
| --- | --- | --- | --- |
| `qtide-tidal.mochyymodule.zip` | **Qtide — Tidal** | a refresh token | FLAC 16/44.1 and, where the master allows, **hi-res up to 24-bit** (as DASH) |
| `qtide-qobuz.mochyymodule.zip` | **Qtide — Qobuz** | a user token (`X-User-Auth-Token`) | FLAC 16/44.1, up to 24-bit on Studio accounts |
| `qtide-deezer.mochyymodule.zip` | **Qtide — Deezer** | the `arl` cookie | **FLAC** on a HiFi account, MP3 320 otherwise — the file is decrypted in the module |

Install any combination. Mochy asks players in the order you drag them on the modules page, so put
whichever you trust first; a module below YouTube Music is only asked after YouTube has nothing.
Quality is a chip on each module's page.

> This is a third-party module. It is not made or supported by Mochy's author.

## Install

1. Take the zips you want from the newest release (`…-debug.mochyymodule.zip` for a debug Mochy).
2. Send it to the phone.
3. Mochy → settings → modules → **add a module** → **add from a file** → pick the zip → **add**.
4. Open each module's page, paste its token, and turn **enable** on.
5. On the modules page, drag them above YouTube Music so they are asked first.

A debug build of Mochy only accepts a zip signed with the **debug** key, a release build one signed
with the **release** key. Take the zips that match your app: `qtide-<service>-debug.mochyymodule.zip`
or `qtide-<service>.mochyymodule.zip`.

## Tokens

Each module has one field:

| field | where it comes from |
| --- | --- |
| **Tidal refresh token** | sign in at tidal.com, or reuse one from streamrip/tiddl. The module renews its access token itself. |
| **Qobuz user token** | the Qobuz web player → developer tools → Network → any request's `X-User-Auth-Token` header. |
| **Qobuz app id and secret** (optional, Qobuz module) | `appId:secret` if you have your own pair; left empty they are read from Qobuz's player script. |
| **Deezer ARL** | deezer.com → developer tools → Application → Cookies → `arl`. |

Tokens live on the phone only, in that module's own storage; nothing is sent anywhere else.

## How a song is played

```
play → search the services in order (first artist + title, matched by recording)
     → Tidal / Qobuz: a signed link to the file
     → Tidal hi-res: a DASH manifest (2 KB) handed over as a data: uri;
                     the player fetches its segments as it plays
     → Deezer: the file is downloaded, de-striped (Blowfish-CBC), saved, and played as one file
```

## Building

### The quick way (no Gradle daemon)

```sh
./build.sh              # -> build/qtide-<service>-debug.mochyymodule.zip (debug key)
RELEASE=1 KEYSTORE=… KS_PASS=… KEY_ALIAS=… ./build.sh
                        # -> build/qtide-<service>.mochyymodule.zip        (your release key)
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
├── QtideTidal.kt         the Tidal module: its token field, its quality chips
├── QtideQobuz.kt         the Qobuz module
├── QtideDeezer.kt        the Deezer module
├── Sources.kt            the song flow they share: search, match, open, hand over
├── Tidal.kt              session, search, stream (link + hi-res DASH)
├── Qobuz.kt              session (app pair scraped or given), search, signed stream
├── Deezer.kt             ARL session, search, licence stream
├── Blowfish.kt           Deezer's striped files are decrypted with this
└── Json.kt               a small json reader/writer: no dependencies but kotlin
tools/AndroidManifest.xml  the apk's shell; build.sh swaps the entry class into it
build.sh, toolchain.sh     one zip per service
```

Each zip carries the same classes and differs only in the entry class its manifest names, so a
fix to the shared code reaches all three at once.

## Limits

- **Tidal hi-res** is DASH. Mochy's player takes it; the module's **test button** plays without
  labelling the item as DASH, so it can report `Play` as failed for that tier while real playback
  works. Pick Lossless if you want the test to be green.
- **Dolby Atmos** is not reachable through the public Tidal api: the atmos row answers with the
  stereo rendition on every quality and parameter we tried (2026-11-20).
- **Deezer** only streams FLAC on a HiFi account; without one it plays MP3 320. Either way the
  file is decrypted here (a 5-minute FLAC is about 39 MB and takes under a second to unwind), so the
  30-second preview is only the fallback when the account can't get a full file at all.
