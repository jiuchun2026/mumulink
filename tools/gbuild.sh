#!/usr/bin/env bash
# Serialize Gradle invocations across agents; usage: bash tools/gbuild.sh assembleDebug
set -u
cd "$(dirname "$0")/../android"
LOCK=.gradle-build.lock
i=0
while ! mkdir "$LOCK" 2>/dev/null; do
  i=$((i+1))
  if [ "$i" -gt 900 ]; then echo "GBUILD: lock timeout" >&2; exit 1; fi
  sleep 2
done
trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM
export JAVA_HOME='%JAVA_HOME%'
"$(pwd)/gradlew.bat" "$@" --console=plain
rc=$?
exit $rc
