#!/bin/sh
# every jar and tool the build needs, from this machine's gradle cache and android sdk
G=~/.gradle/caches/modules-2/files-2.1
SDK=${ANDROID_SDK:-${ANDROID_HOME:-/c/Android/Sdk}}
# the kotlin the jars come froms ( 2.3.10 - mochy is here
KOTLIN=${KOTLIN_VERSION:-2.3.20}
export BT="$SDK/build-tools/36.0.0"
export ANDROID_JAR="$SDK/platforms/android-36/android.jar"
# windows ships d8.bat and aapt2.exe, mac and linux ship plain d8 and aapt2, yea thats definetly different from the rest of the android sdk, thanks google
tool() { for f in "$BT/$1" "$BT/$1.bat" "$BT/$1.exe"; do [ -f "$f" ] && { echo "$f"; return; }; done; echo "$BT/$1"; }
export D8=$(tool d8) AAPT2=$(tool aapt2) ZIPALIGN=$(tool zipalign) APKSIGNER=$(tool apksigner)
export PYTHON=$(command -v python || command -v python3)
export KEYTOOL=$(tool keytool)
# the cache keeps -sources jars next to the real ones and those have no classes in them. wow
findj() { find "$1" -name "$2" ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null | sort -V | tail -1; }
export STDLIB=$(findj "$G/org.jetbrains.kotlin/kotlin-stdlib/$KOTLIN" "*.jar")
export ANNOTATIONS=$(findj "$G/org.jetbrains/annotations" "annotations-*.jar")
export COROUTINES=$(findj "$G/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "*.jar")
export SERJSON=$(findj "$G/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.8.1" "*.jar")
export SERCORE=$(findj "$G/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.8.1" "*.jar")
COMPILER=$(findj "$G/org.jetbrains.kotlin/kotlin-compiler-embeddable/$KOTLIN" "*.jar")
REFLECT=$(findj "$G/org.jetbrains.kotlin/kotlin-reflect" "kotlin-reflect-1.6*.jar")
SCRIPT=$(findj "$G/org.jetbrains.kotlin/kotlin-script-runtime" "*.jar")
TROVE=$(findj "$G/org.jetbrains.intellij.deps/trove4j" "*.jar")
export COMPILER_CP="$COMPILER:$STDLIB:$REFLECT:$SCRIPT:$TROVE:$COROUTINES:$ANNOTATIONS:$SERJSON:$SERCORE"
export LIBS="$STDLIB:$COROUTINES:$ANNOTATIONS:$SERJSON:$SERCORE"
