#!/bin/sh
# DEV ONLY. Downloads MojoLauncher's prebuilt JRE 25 (Android, arm64) and repacks it into
# .work/dev-jre/jre-25.tar.xz, which the runtime module bundles into debug APKs.
# It's never committed and never shipped: release builds use our own JRE from scripts/natives.
# Usage: scripts/dev/fetch-dev-jre.sh
set -eu
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
URL="https://github.com/MojoLauncher/android-openjdk-build-17-25/releases/download/rolling/jre25-pojav.zip"
SHA256="fc3a96bb60b53c55306e93f053f993739cdaa6c4b72824d88f04d990fb868f5a"
OUT="$ROOT/.work/dev-jre"
mkdir -p "$OUT"
cd "$OUT"

if [ ! -f jre25-pojav.zip ] || ! echo "$SHA256  jre25-pojav.zip" | shasum -a 256 -c - >/dev/null 2>&1; then
  curl -fsSL -o jre25-pojav.zip.part "$URL"
  # The release is "rolling": if this fails, the upstream build changed. Check it and update SHA256.
  echo "$SHA256  jre25-pojav.zip.part" | shasum -a 256 -c -
  mv jre25-pojav.zip.part jre25-pojav.zip
fi

# The zip holds universal.tar.xz (classes, conf) plus one bin-<arch>.tar.xz (libraries): merge them.
rm -rf unpack jre25 && mkdir unpack jre25
unzip -q -o jre25-pojav.zip universal.tar.xz bin-arm64.tar.xz -d unpack
tar -xJf unpack/universal.tar.xz -C jre25
tar -xJf unpack/bin-arm64.tar.xz -C jre25
rm -rf jre25/bin jre25/lib/jspawnhelper   # executables can't run from app storage anyway
tar --no-xattrs --no-mac-metadata --format ustar -cJf jre-25.tar.xz.part -C jre25 .
mv jre-25.tar.xz.part jre-25.tar.xz
rm -rf unpack
echo "dev JRE: $OUT/jre-25.tar.xz ($(du -h jre-25.tar.xz | cut -f1)), java $(grep JAVA_VERSION= jre25/release | cut -d'"' -f2)"
