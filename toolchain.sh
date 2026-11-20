#!/bin/sh
# every jar and tool the build needs, from this machine's gradle cache and android sdk
G=~/.gradle/caches/modules-2/files-2.1
SDK=${ANDROID_SDK:-/c/Android/Sdk}
export BT="$SDK/build-tools/36.0.0"
export ANDROID_JAR="$SDK/platforms/android-36/android.jar"
findj() { find "$1" -name "$2" 2>/dev/null | sort -V | tail -1; }
export STDLIB=$(findj "$G/org.jetbrains.kotlin/kotlin-stdlib/2.3.20" "*.jar")
export ANNOTATIONS=$(findj "$G/org.jetbrains/annotations" "annotations-*.jar")
export COROUTINES=$(findj "$G/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "*.jar")
export SERJSON=$(findj "$G/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.8.1" "*.jar")
export SERCORE=$(findj "$G/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.8.1" "*.jar")
COMPILER=$(findj "$G/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.20" "*.jar")
REFLECT=$(findj "$G/org.jetbrains.kotlin/kotlin-reflect" "kotlin-reflect-1.6*.jar")
SCRIPT=$(findj "$G/org.jetbrains.kotlin/kotlin-script-runtime" "*.jar")
TROVE=$(findj "$G/org.jetbrains.intellij.deps/trove4j" "*.jar")
export COMPILER_CP="$COMPILER:$STDLIB:$REFLECT:$SCRIPT:$TROVE:$COROUTINES:$ANNOTATIONS:$SERJSON:$SERCORE"
export LIBS="$STDLIB:$COROUTINES:$ANNOTATIONS:$SERJSON:$SERCORE"
