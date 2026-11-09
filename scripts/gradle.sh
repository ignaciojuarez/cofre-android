#!/bin/sh
# Run Gradle with the right JDK, one build at a time per checkout (agents share this checkout).
# Usage: scripts/gradle.sh <tasks...>
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME_OVERRIDE:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
mkdir -p "$ROOT/.work"
LOCK="$ROOT/.work/.build-lock"
waited=0
until mkdir "$LOCK" 2>/dev/null; do
  # A lock older than 45 minutes belongs to a dead build.
  if [ -n "$(find "$LOCK" -maxdepth 0 -mmin +45 2>/dev/null)" ]; then rmdir "$LOCK" 2>/dev/null || true; continue; fi
  [ $((waited % 60)) -eq 0 ] && echo "gradle.sh: waiting for another build ($LOCK)" >&2
  sleep 5; waited=$((waited + 5))
done
trap 'rmdir "$LOCK" 2>/dev/null || true' EXIT INT TERM
cd "$ROOT"
./gradlew --console=plain "$@"
