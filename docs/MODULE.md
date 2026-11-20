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
class QtideModule : SourceModule, ModuleFields, ModuleArt {
    override val info: ModuleInfo            // id, name, version, author, source url
    override val signIn: SignInSpec?         // the service's page, opened in mochy's own browser
    override fun onSignInHeader(name, value, url): Boolean   // true once the session is in
    override val fields: List<ModuleField>   // the token fields on its page, kept as a fallback
    override fun onFields(values: Map<String,String>)        // called whenever a field changes
    override val choices: List<ModuleChoice> // the quality chips
    override val icon: String?               // written into host.folder, answered as an absolute path
    override val banner: String?             // the same
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
| Tidal | device login approved at `link.tidal.com`, or a pasted refresh token; two client pairs, one per tier | `api.tidal.com/v1/search/tracks` | CD: one FLAC link · hi-res: DASH manifest as a `data:` uri · High/Low: one M4A link |
| Qobuz | `play.qobuz.com` in mochy's browser sends `X-User-Auth-Token`, or the token is pasted; app pair scraped from the player | `track/search` | `track/getFileUrl` with an md5 signature: one FLAC link |
| Deezer | `arl` copied from deezer.com (an HttpOnly cookie no browser hands over) → `checkForm` + licence token | `api.deezer.com/search` | licence → `media.deezer.com/v1/get_url`, then the striped file is decrypted with Blowfish (`Blowfish.kt`) and saved; a preview is never played |

Each quality chip is a ceiling with a floor: Hi-Res asks for the top tier and falls to CD (Tidal
`HI_RES_LOSSLESS` → `LOSSLESS`, Qobuz 27 → 7 → 6), High asks for `HIGH` and falls to `LOW`, and the
label always names the tier that answered. The ladders live in `Ladder.kt` and in each service's
`RUNGS` table.

Deezer strips its files: every 6144 bytes, the first 2048 are Blowfish-CBC under a key built from
the track id (`md5(id)`, halves xored with `g4el58wc0zvf9na1`), starting from IV `0001020304050607`.
`Blowfish.kt` is the module's own implementation of that cipher (the platform's is not guaranteed
on every Android version) and was checked against the published vectors and against a real track.

## Why no dependencies

The api is plain kotlin on purpose, and a module is loaded into mochy's process. The only libraries
this module brings are `kotlin-stdlib`, `kotlinx-coroutines` and `kotlinx-serialization`, and `Json.kt`
is its own reader/writer so nothing else has to be dexed in.
