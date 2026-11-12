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
# -k surfaces every compile error in one run; the build still fails if any target fails.
make -k CONF=android-arm64 JOBS="$JOBS" images || {
    grep -rh -A4 'error:' "$JDK/build/android-arm64/make-support/failure-logs" | head -300
    exit 1
}
# A runtime, not a JDK: every module except the compilers, debuggers and developer tools. That drops Graal and JVMCI
# (a second JIT nothing turns on), javac and its ct.sym of older Java versions, the serviceability agent, and ~30 tools
# in bin/. java.se stays whole: mods use any of it (ImageIO, java.sql, scripting). The host jlink links the target's
# jmods, as `make images` itself does when cross-compiling.
DROP=(jdk.graal.compiler jdk.graal.compiler.management jdk.internal.vm.ci jdk.compiler jdk.javadoc jdk.internal.md
      jdk.jshell jdk.editpad jdk.internal.le jdk.internal.ed jdk.internal.opt jdk.jdeps jdk.jlink jdk.jpackage jdk.jartool
      jdk.jdi jdk.jdwp.agent jdk.hotspot.agent jdk.jcmd jdk.jconsole jdk.jstatd jdk.jsobject)
JMODS="$JDK/build/android-arm64/images/jmods"
MODULES=$(find "$JMODS" -name '*.jmod' -exec basename {} .jmod \; | grep -vxF -f <(printf '%s\n' "${DROP[@]}") | sort | paste -sd, -)
JRE="$WORK/jre25"
rm -rf "$JRE"
"$JAVA_HOME/bin/jlink" --module-path "$JMODS" --add-modules "$MODULES" --output "$JRE" --no-man-pages --no-header-files
linked=$(sed -n 's/^MODULES="\(.*\)"$/\1/p' "$JRE/release" | tr ' ' '\n')
for module in "${DROP[@]}"; do
    if grep -qxF "$module" <<<"$linked"; then echo "jlink kept $module: a module we keep requires it" >&2; exit 1; fi
done
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
