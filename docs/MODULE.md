# How the module fits into Mochy

## The contract mochy loads

Mochy reads the apk's manifest for the entry class:

```xml
<meta-data android:name="com.mochyy.mochymusic.module.entry"
           android:value="com.qtide.QtideModule" />
```

then starts it through the bridge it carries in its own zip:

```kotlin
loader.loadClass("com.mochyy.mochymusic.module.bridge.ModuleSide")
```

That class lives in `api/…/bridge/ModuleSide.kt`. **If it is missing from the apk**, mochy's load
throws and the module page says *"this module's code didn't start — it may be made for a different
version of Mochy"*. That is the one mistake this project made once; the copy in `api/` is there for
good now.

## What the module implements

```kotlin
class QtideModule : SourceModule, ModuleFields {
    override val info: ModuleInfo            // id, name, version, author
    override val fields: List<ModuleField>   // the four token fields on its page
    override fun onFields(values: Map<String,String>)   // called whenever a field changes
    override val choices: List<ModuleChoice> // the quality chips
    override suspend fun resolve(track, picks): ResolveResult
    override suspend fun diagnose(track, picks, report)  // resolve, step by step, for the test button
}
```

`resolve` returns one of:

```kotlin
ResolveResult.Found(SourceStream(url, format, sourceLabel, qualityLabel, codec, sampleRateHz, matched))
ResolveResult.NotFound("not on any of the services")
ResolveResult.Unavailable("signed out")
```

Nothing is downloaded by mochy: the url is handed to its player, and **the player** opens it. `StreamFormat` is `Progressive` for one file or `Dash` for a manifest.

## The three services

| | sign-in | search | stream |
| --- | --- | --- | --- |
| Tidal | refresh token → access token (two client pairs, one per tier) | `api.tidal.com/v1/search/tracks` | CD: one FLAC link · hi-res: DASH manifest as a `data:` uri |
| Qobuz | user token, app pair scraped from `play.qobuz.com` | `track/search` | `track/getFileUrl` with an md5 signature: one FLAC link |
| Deezer | `arl` cookie → `checkForm` + licence token (needs the site's own cookies first) | `api.deezer.com/search` | licence → `media.deezer.com/v1/get_url`; the preview is used when a full file isn't licensed |

Deezer's decoder (Blowfish over 2048-byte slices) is not in this project: the licence's own url is
played as it comes, and a file that arrives encrypted is saved as downloaded. For a HiFi account
that is a FLAC; without one it is MP3 320.

## Why no dependencies

The api is plain kotlin on purpose, and a module is loaded into mochy's process. The only libraries
this module brings are `kotlin-stdlib`, `kotlinx-coroutines` and `kotlinx-serialization`, and `Json.kt`
is its own reader/writer so nothing else has to be dexed in.
