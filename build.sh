#!/bin/sh
# builds build/qtide.mochyymodule.zip with the tools that need no gradle daemon:
# kotlinc (from the gradle cache), d8, aapt2, apksigner. no gradle daemon means no loopback socket.
#
#   ./build.sh                 signs with the debug key (a debug mochy accepts it)
#   RELEASE=1 ./build.sh       signs with $KEYSTORE / $KS_PASS / $KEY_ALIAS when they are set
set -e
cd "$(dirname "$0")"
. ./toolchain.sh

rm -rf out-classes dex apk-build
mkdir -p dex apk-build/root

echo "1/4 kotlin"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -classpath "$LIBS" -d out-classes api module/src

echo "2/4 dex"
"$BT/d8.bat" --release --min-api 26 --lib "$ANDROID_JAR" --output dex \
  "$STDLIB" "$COROUTINES" "$SERJSON" "$SERCORE" $(find out-classes -name "*.class")

echo "3/4 apk"
cp dex/classes.dex apk-build/root/
cp tools/AndroidManifest.xml apk-build/root/
(
  cd apk-build
  "$BT/aapt2.exe" link -o base.apk -I "$ANDROID_JAR" --manifest root/AndroidManifest.xml
  python -I -c "import zipfile, shutil; shutil.copy('base.apk','unsigned.apk'); z=zipfile.ZipFile('unsigned.apk','a',zipfile.ZIP_DEFLATED); z.write('root/classes.dex','classes.dex'); z.close()"
  "$BT/zipalign.exe" -f 4 unsigned.apk aligned.apk
  if [ "$RELEASE" = "1" ] && [ -n "$KEYSTORE" ]; then
    "$BT/apksigner.bat" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
      --key-pass "pass:${KEY_PASS:-$KS_PASS}" --ks-key-alias "$KEY_ALIAS" --out module.apk aligned.apk
  else
    "$BT/apksigner.bat" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
      --key-pass pass:android --ks-key-alias androiddebugkey --out module.apk aligned.apk
  fi
)

echo "4/4 zip"
mkdir -p build
rm -f build/qtide.mochyymodule.zip build/qtide-debug.mochyymodule.zip
if [ "$RELEASE" = "1" ] && [ -n "$KEYSTORE" ]; then
  python -I -c "import zipfile; z=zipfile.ZipFile('build/qtide.mochyymodule.zip','w',zipfile.ZIP_DEFLATED); z.write('module/manifest.json','manifest.json'); z.write('apk-build/module.apk','module.apk'); z.close()"
  echo "built build/qtide.mochyymodule.zip (release key)"
else
  python -I -c "import zipfile; z=zipfile.ZipFile('build/qtide-debug.mochyymodule.zip','w',zipfile.ZIP_DEFLATED); z.write('module/manifest.json','manifest.json'); z.write('apk-build/module.apk','module.apk'); z.close()"
  echo "built build/qtide-debug.mochyymodule.zip (debug key)"
fi
