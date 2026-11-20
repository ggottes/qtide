# When something is wrong

| what you see | why | what to do |
| --- | --- | --- |
| *"This module's code didn't start. It may be made for a different version of Mochy."* | the apk is missing `ModuleSide`, or was built for another api version | take a zip built from this repo's current sources; both `-debug` and release zips carry the bridge |
| *"It has to be signed with the same key as your Mochy."* | wrong key for your build | debug Mochy → `qtide-<service>-debug.mochyymodule.zip`; release Mochy → `qtide-<service>.mochyymodule.zip` |
| *"That file isn't a module."* | the zip of the service you want has no `manifest.json` at its root | take the built zip, not the sources |
| Account step red | the token is wrong or expired | sign in again from the module's page, or paste a fresh token |
| *Find the song* red, saying *"open link.tidal.com and approve code …"* | Tidal isn't signed in yet | tap **Sign in**, log in, type the code, then play again |
| *Find the song* red | the recording isn't on that service in your country | check the spelling, or turn on another service |
| *Stream* red, Deezer | the account can't be given a full file (Free plan, no FLAC, region) | the line names the call that refused; Deezer Free has no streaming at all |
| *Stream* red, Qobuz | the account can't stream that tier | 24-bit needs a Studio plan; Lossless works on Sublime, and Hi-Res falls back to CD by itself |
| Tidal **hi-res** plays but the test's **Play** step is red | the test plays without labelling the item as DASH | it works in the real player; use Lossless if you want the test green |
| nothing plays, no error | the module is below YouTube Music in the order | drag Qtide above it on the modules page |

The module's page shows `adb logcat -s ModuleTest` lines too if you want the details:

```sh
adb logcat -s ModuleRegistry ModuleTest
```
