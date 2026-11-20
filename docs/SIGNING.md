# Signing

Mochy only runs a zip module whose apk is signed with **the same key the running mochy is signed
with**:

```kotlin
private fun signedLikeMochy(context: Context, theirs: PackageInfo?): Boolean {
    val own = signers(context.packageManager.getPackageInfo(context.packageName, SignatureFlags))
    return own.isNotEmpty() && own == signers(theirs)
}
```

It compares sha-256 fingerprints of the signing certificates, newest only on a rotated key.

## Which key your Mochy has

| your Mochy | install this zip |
| --- | --- |
| debug / fast / profileable build | one signed with the **debug** key (`~/.android/debug.keystore`, password `android`, alias `androiddebugkey`) |
| release build | one signed with the **release** key that build was signed with |

A zip signed with anything else is refused with *"it has to be signed with the same key as your
Mochy"*. A debug zip on a release build (or the reverse) gets the same message.

```sh
sh ./build.sh                                   # debug key
RELEASE=1 KEYSTORE=qtide.jks KS_PASS=… KEY_ALIAS=qtide sh ./build.sh
```

## Checking a zip before sending it

```sh
unzip -l qtide-debug.mochyymodule.zip                 # manifest.json + module.apk
$ANDROID_SDK/build-tools/36.0.0/apksigner.bat verify --print-certs \
  apk-build/module.apk | head -3                      # the certificate digest
```

## Release key

One key per project, kept out of the repository (`.gitignore` covers `*.jks` and `*.keystore`).
Losing it means nobody can update the module in place — a new key is a new signature and mochy
refuses the update. Keep a backup.

```sh
keytool -genkeypair -keystore qtide.jks -alias qtide -keyalg RSA -keysize 4096 -validity 10000
```
