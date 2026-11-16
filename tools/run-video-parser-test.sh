#!/usr/bin/env bash
# 纯 JVM 自测 VideoFrameParser（不用 gradle，不碰模拟器）
# 用法：bash tools/run-video-parser-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA='D:/app/Java/jdk-17.0.2/bin/java.exe'
KOTLINC_JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.2.21/*/kotlin-compiler-embeddable-2.2.21.jar | head -1)
STDLIB_JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.21/*/kotlin-stdlib-2.2.21.jar | head -1)
REFLECT_JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-reflect/2.0.21/*/kotlin-reflect-2.0.21.jar | head -1)
SCRIPT_JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-script-runtime/2.2.21/*/kotlin-script-runtime-2.2.21.jar | head -1)
COROUTINES_JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/*/kotlinx-coroutines-core-jvm-1.10.2.jar | head -1)

ANDROID_JAR="D:/app/Android/Sdk/platforms/android-36/android.jar"
ANNOTATIONS_JAR=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.jetbrains/annotations/23.0.0/*/annotations-23.0.0.jar | head -1)

OUT=tools/.parser-test
rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVA" -Dfile.encoding=UTF-8 -cp "$KOTLINC_JAR;$STDLIB_JAR;$REFLECT_JAR;$SCRIPT_JAR;$COROUTINES_JAR;$ANNOTATIONS_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -classpath "$STDLIB_JAR;$ANDROID_JAR" -d "$OUT" \
  android/app/src/main/java/com/mumulink/remote/net/VideoFrameParser.kt \
  android/app/src/main/java/com/mumulink/remote/net/StreamSocket.kt \
  tools/VideoFrameParserSelfTest.kt

"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT;$STDLIB_JAR;$ANDROID_JAR" VideoFrameParserSelfTestKt
