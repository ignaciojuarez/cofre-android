#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
source "$(dirname "$0")/common.sh"
ndk_setup
source_tree jna
AAR="$WORK/jna-5.17.0.aar"
curl -fL --retry 3 -o "$AAR" https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.aar
printf '%s  %s\n' 4dbeffffa665d97ad5aa7eee297531d3c841a86716ab7f774fd6956422b3cf38 "$AAR" | sha256sum -c -
mkdir -p "$WORK/jna/lib/arm64-v8a" "$WORK/jna/legal"
unzip -p "$AAR" jni/arm64-v8a/libjnidispatch.so > "$WORK/jna/lib/arm64-v8a/libjnidispatch.so"
printf '%s  %s\n' abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06 "$WORK/jna/lib/arm64-v8a/libjnidispatch.so" | sha256sum -c -
python3 "$NATIVE_SCRIPTS/licenses.py" "$WORK/src" "$WORK/jna/legal" jna
verify "$WORK/jna" jna
pack_sources jna jna
tar -C "$WORK/jna" -cJf "$DIST/jna-5.17.0-android-arm64.tar.xz" .
