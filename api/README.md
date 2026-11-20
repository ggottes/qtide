# mochy's module api, copied in

These files are mochy's own, from
[`realidkroo/Mochy-Music`](https://github.com/realidkroo/Mochy-Music):

| file | from |
| --- | --- |
| `com/mochyy/mochymusic/module/MochyModule.kt` | `modules/api/.../MochyModule.kt` |
| `com/mochyy/mochymusic/module/TrackMatcher.kt` | `modules/api/.../TrackMatcher.kt` |
| `com/mochyy/mochymusic/module/bridge/*` | `modules/bridge/...` |

They are copied rather than depended on because a zip module is compiled on its own and loaded into
the running mochy: the contract it is built against has to be the exact one it will meet. The three
files under `bridge/` are what mochy loads out of the apk (`ModuleSide`), so a module that lacks them
is refused with "this module's code didn't start".

**Do not edit them.** When mochy bumps `MODULE_API_VERSION`, copy the new files in, rebuild, and bump
this module's version.
