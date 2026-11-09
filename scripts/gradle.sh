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
  # The lock holds its build's PID: stale once that process is gone. Without one (an older
  # gradle.sh, or killed before writing it), it's stale after 45 minutes.
  owner=$(cat "$LOCK/pid" 2>/dev/null || true)
  if { [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; } ||
     { [ -z "$owner" ] && [ -n "$(find "$LOCK" -maxdepth 0 -mmin +45 2>/dev/null)" ]; }; then
    rm -rf "$LOCK"
    continue
  fi
  [ $((waited % 60)) -eq 0 ] && echo "gradle.sh: waiting for another build ($LOCK${owner:+, pid $owner})" >&2
  sleep 5; waited=$((waited + 5))
done
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT
trap 'exit 130' INT TERM
cd "$ROOT"
./gradlew --console=plain "$@"
