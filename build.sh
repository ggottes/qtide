#!/bin/sh
# builds one zip per service: qtide-tidal, qtide-qobuz, qtide-deezer.
#
#   ./build.sh              debug key (what a debug mochy accepts)
#   RELEASE=1 KEYSTORE=… KS_PASS=… KEY_ALIAS=… ./build.sh
set -e
cd "$(dirname "$0")"
# one value per build of this checkout, so mochy never reuses a code folder from an older one
MODE=${MODE:-$(date -u +%Y%m%d%H%M)}
# the module's id in its manifest, and the key mochy keeps its code under. a debug and a release
# copy of one module must not share a key: they're three zips, not one build type each.
RELEASE_BUILD=
[ "$RELEASE" = "1" ] && [ -n "$KEYSTORE" ] && RELEASE_BUILD=1
if [ -n "$RELEASE_BUILD" ]; then SUFFIX=; else SUFFIX=-debug; fi
. ./toolchain.sh

build_one() {
  entry=$1        # com.qtide.QtideTidal
  id=$2           # qtide-tidal-debug / qtide-tidal, the key mochy keeps code under
  name=$3
  description=$4
  resources=$5
  out=$6          # qtide-debug.mochyymodule.zip
  icon=$7         # this service's icon, at the zip root

  work="work/$id"
  rm -rf "$work"
  mkdir -p "$work/dex" "$work/apk/root"

  # only this module's class is the entry; the three share the sources, but the manifest names one
  # the mode is woven into the apk so a module cache from an earlier build can't be reused
  sed -e "s/com.qtide.ENTRY/$entry/" -e "s|SWAP_ID|$id|" -e "s|SWAP_MODE|$MODE|" \
    tools/AndroidManifest.xml > "$work/apk/root/AndroidManifest.xml"

  "$D8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$work/dex" \
    "$STDLIB" "$COROUTINES" "$SERJSON" "$SERCORE" $(find out-classes -name "*.class")

  cp "$work/dex/classes.dex" "$work/apk/root/"
  (
    cd "$work/apk"
    "$AAPT2" link -o base.apk -I "$ANDROID_JAR" --manifest root/AndroidManifest.xml
    "$PYTHON" -I -c "import zipfile, shutil; shutil.copy('base.apk','unsigned.apk'); z=zipfile.ZipFile('unsigned.apk','a',zipfile.ZIP_DEFLATED); z.write('root/classes.dex','classes.dex'); z.close()"
    "$ZIPALIGN" -f 4 unsigned.apk aligned.apk
    if [ "$RELEASE" = "1" ] && [ -n "$KEYSTORE" ]; then
      "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
        --key-pass "pass:${KEY_PASS:-$KS_PASS}" --ks-key-alias "$KEY_ALIAS" --out module.apk aligned.apk
    else
      # a mac has no debug keystore until something makes one
      if [ ! -f "$HOME/.android/debug.keystore" ]; then
        mkdir -p "$HOME/.android"
        "$KEYTOOL" -genkeypair -keystore "$HOME/.android/debug.keystore" -storepass android \
          -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 || true
      fi
      "$APKSIGNER" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
        --key-pass pass:android --ks-key-alias androiddebugkey --out module.apk aligned.apk
    fi
  )

  mkdir -p build
  "$PYTHON" -I - "$work/apk/module.apk" "build/$out" "$id" "$name" "$description" "$resources" "$icon" <<'PY'
import json, sys, zipfile
apk, out, mid, name, desc, resources, icon = sys.argv[1:8]
manifest = {
    "id": mid, "name": name, "version": "1.5", "author": "geisselgottes",
    "description": desc, "resources": [r for r in resources.split(",") if r], "code": "module.apk",
    # the pictures are also beside the manifest, the way a link module carries its own
    "icon": "icon.png", "banner": "banner.png",
}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
    z.write(apk, "module.apk")
    z.write(icon, "icon.png")
    z.write("assets/banner/banner.png", "banner.png")
PY
  echo "built build/$out"
}

echo "1/3 kotlin"
rm -rf out-classes
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$LIBS" -d out-classes api module/src

if [ "$RELEASE" = "1" ] && [ -n "$KEYSTORE" ]; then
  TIDAL_OUT=qtide-tidal.mochyymodule.zip
  QOBUZ_OUT=qtide-qobuz.mochyymodule.zip
  DEEZER_OUT=qtide-deezer.mochyymodule.zip
  POOL_OUT=qtide-pool.mochyymodule.zip
  # a release run leaves the debug zips alone: both go in one release
  : > /dev/null
else
  TIDAL_OUT=qtide-tidal-debug.mochyymodule.zip
  QOBUZ_OUT=qtide-qobuz-debug.mochyymodule.zip
  DEEZER_OUT=qtide-deezer-debug.mochyymodule.zip
  POOL_OUT=qtide-pool-debug.mochyymodule.zip
fi

echo "2/3 zips"
# the key, not the manifest id: a debug and a release zip of the same module replace each other
build_one com.qtide.QtideTidal  qtide-tidal${SUFFIX}  "Qtide — Tidal"  "Tidal: your own account, lossless and hi-res where it has it" "search,stream" "$TIDAL_OUT"  assets/icons/icon-tidal.png
build_one com.qtide.QtideQobuz  qtide-qobuz${SUFFIX}  "Qtide — Qobuz"  "Qobuz: your own account, FLAC up to 24-bit on a Studio plan" "search,stream" "$QOBUZ_OUT"  assets/icons/icon-qobuz.png
build_one com.qtide.QtideDeezer qtide-deezer${SUFFIX} "Qtide — Deezer" "Deezer: your own account, FLAC on a HiFi plan" "search,stream" "$DEEZER_OUT"  assets/icons/icon-deezer.png
build_one com.qtide.QtidePool   qtide-pool${SUFFIX}   "Qtide — Pool"   "Your own Tidal and Qobuz through a Qtide server" "search,stream" "$POOL_OUT"  assets/icons/icon-pool.png

# the sign-in helper, next to the zips: a phone can open it straight from the chat, no site needed
cp docs/tidal.html build/tidal.html
echo "3/3 done (build/tidal.html is the Tidal sign-in page)"
