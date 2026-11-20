# CI

`build.yml` builds the two zips. For the release one, add three secrets:

| secret | what |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 qtide.jks` |
| `RELEASE_KEYSTORE_PASS` | the keystore's password |
| `RELEASE_KEY_ALIAS` | the key's alias |

The debug zip needs nothing: it is signed with the standard Android debug key.
