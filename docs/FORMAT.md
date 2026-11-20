# The zip, field by field

```
qtide.mochyymodule.zip
├── manifest.json
└── module.apk
```

## manifest.json

| field | value here | what mochy does with it |
| --- | --- | --- |
| `id` | `qtide` | stable forever; an update reuses it |
| `name` | `Qtide` | the row and the page |
| `version` | `1.0` | shown, and on the update button |
| `description` | one line | under the name |
| `author` | `geisselgottes` | under module info |
| `resources` | `["search", "stream"]` | tells mochy the module brings songs |
| `code` | `module.apk` | the apk inside the zip holding the module's code |

A zip with `code` is only run when the apk is signed like mochy — see [SIGNING.md](SIGNING.md).
Without `code`, a manifest that lists `search`/`stream` is read as a **server** and needs a `url`;
that is why this one carries `code`.

## module.apk

| part | what |
| --- | --- |
| `AndroidManifest.xml` | `com.mochyy.mochymusic.module.entry` → `com.qtide.QtideModule` |
| `classes.dex` | the module, the bridge, kotlin stdlib, coroutines, serialization |
| `META-INF/…` | the signature |

Mochy never installs it: it extracts `module.apk`, loads `ModuleSide` from it in a class loader that
only sees android, java and the bridge's `shared` package, and calls the module through json.

## The form the zip takes when published

| file name | picked |
| --- | --- |
| `qtide.mochyymodule.zip` | yes (release builds) |
| `qtide-debug.mochyymodule.zip` | yes (debug builds) |
| `qtide.zip` | no — the name must contain `mochyymodule` |

Both may sit on the same release; mochy takes the `debug` one on a debug build and the other on a
release build.
