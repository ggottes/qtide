# When something is wrong

| what you see | why | what to do |
| --- | --- | --- |
| *"This module's code didn't start. It may be made for a different version of Mochy."* | the apk is missing `ModuleSide`, or was built for another api version | take a zip built from this repo's current sources; both `-debug` and release zips carry the bridge |
| *"It has to be signed with the same key as your Mochy."* | wrong key for your build | debug Mochy → `qtide-debug.mochyymodule.zip`; release Mochy → `qtide.mochyymodule.zip` |
| *"That file isn't a module."* | the zip has no `manifest.json` at its root | take the built zip, not the sources |
| Account step red | the token is wrong or expired | paste it again; Tidal's refresh token rotates, take a fresh one |
| *Find the song* red | the recording isn't on that service in your country | check the spelling, or turn on another service |
| *Stream* red | the service refused the stream (licence, region, free account) | Deezer needs HiFi for FLAC; Qobuz needs a Studio account for 24-bit |
| Tidal **hi-res** plays but the test's **Play** step is red | the test plays without labelling the item as DASH | it works in the real player; use Lossless if you want the test green |
| nothing plays, no error | the module is below YouTube Music in the order | drag Qtide above it on the modules page |

The module's page shows `adb logcat -s ModuleTest` lines too if you want the details:

```sh
adb logcat -s ModuleRegistry ModuleTest
```
