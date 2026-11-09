#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
source "$(dirname "$0")/../common.sh"
ndk_setup
source_tree jdk
JDK="$WORK/src/jdk"
for patch in "$NATIVE_SCRIPTS/jdk/patches/"*.patch; do
    if git -C "$JDK" apply --reverse --check "$patch" 2>/dev/null; then continue; fi
    git -C "$JDK" apply --check "$patch"
    git -C "$JDK" apply "$patch"
done
build_freetype
mkdir -p "$WORK/compat-libs" "$WORK/headers"
for lib in pthread rt thread_db; do "$AR" cr "$WORK/compat-libs/lib$lib.a"; done
# Only headers: these desktop APIs are not linked into the headless Android build.
for header in X11 fontconfig cups alsa; do ln -sfn "/usr/include/$header" "$WORK/headers/$header"; done
cd "$JDK"
bash configure --with-conf-name=android-arm64 --openjdk-target=aarch64-linux-android \
    --with-toolchain-type=clang --with-sysroot="$TOOLCHAIN/sysroot" \
    --with-boot-jdk="$JAVA_HOME" --with-build-jdk="$JAVA_HOME" \
    --disable-warnings-as-errors --disable-precompiled-headers --enable-headless-only \
    --with-debug-level=release --with-native-debug-symbols=none \
    --with-jvm-variants=server --with-jvm-features=-dtrace,-vm-structs \
    --with-freetype-include="$WORK/freetype/include/freetype2" \
    --with-freetype-lib="$WORK/freetype/lib" \
    --with-alsa-include="$WORK/headers" \
    --with-cups-include="$WORK/headers" --with-fontconfig-include="$WORK/headers" \
    --x-includes="$WORK/headers" --x-libraries=/usr/lib \
    --with-extra-cflags="-DANDROID -DLE_STANDALONE -I$WORK/headers" \
    --with-extra-cxxflags="-DANDROID -DLE_STANDALONE -I$WORK/headers" \
    --with-extra-ldflags="$PAGE_FLAGS -L$WORK/compat-libs -Wl,--undefined-version" \
    --with-version-pre= --with-version-opt= --with-version-build=8 \
    CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" OBJCOPY="$OBJCOPY" \
    BUILD_CC=/usr/bin/clang BUILD_CXX=/usr/bin/clang++ BUILD_LD=/usr/bin/ld.lld
make CONF=android-arm64 JOBS="$JOBS" images
IMAGE="$JDK/build/android-arm64/images/jdk"
JRE="$WORK/jre25"
mkdir -p "$JRE"
for item in bin conf legal lib release; do cp -a "$IMAGE/$item" "$JRE/"; done
# Keep the full modules image; remove only development and debug material.
rm -f "$JRE/lib/src.zip"
cp -L "$WORK/freetype/lib/libfreetype.so" "$JRE/lib/libfreetype.so"
find "$JRE" -type f \( -name '*.so' -o -path '*/bin/*' -o -name jspawnhelper \) -print0 |
    while IFS= read -r -d '' binary; do
        if "$READELF" -h "$binary" >/dev/null 2>&1; then "$STRIP" --strip-debug "$binary"; fi
    done
mkdir -p "$JRE/legal/cofre-port"
cp "$NATIVE_SCRIPTS/jdk/patches/"*.patch "$JRE/legal/cofre-port/"
cp "$WORK/src/freetype/docs/FTL.TXT" "$JRE/legal/cofre-port/"
verify "$JRE" jre25
file "$JRE/bin/java" "$JRE/lib/server/libjvm.so"
cat "$JRE/release"
tar -C "$JRE" -cJf "$DIST/jre25-android-arm64.tar.xz" .
pack_sources jdk25 jdk freetype
