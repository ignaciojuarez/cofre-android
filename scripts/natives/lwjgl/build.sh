#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
source "$(dirname "$0")/../common.sh"
ndk_setup
for dep in lwjgl libffi shaderc spvc openal oboe; do source_tree "$dep"; done
build_freetype
# shaderc's own DEPS pins every transitive source revision. Its sync tool uses those pins.
(cd "$WORK/src/shaderc" && python3 utils/git-sync-deps)
python3 "$NATIVE_SCRIPTS/lwjgl/patch.py" "$WORK/src/lwjgl"
# libffi is linked statically; LWJGL's generated bindings use its public ABI.
(cd "$WORK/src/libffi" && ./autogen.sh)
mkdir -p "$WORK/build/libffi"
(cd "$WORK/build/libffi" && CFLAGS=-fPIC "$WORK/src/libffi/configure" \
    --host=aarch64-linux-android --prefix="$WORK/ffi" --disable-shared --enable-static && \
    make -j "$JOBS" && make install)
cmake -S "$NATIVE_SCRIPTS/lwjgl" -B "$WORK/build/lwjgl" "${CMAKE_ANDROID[@]}" \
    "-DLWJGL_SOURCE=$WORK/src/lwjgl" "-DJAVA_HEADERS=$JAVA_HOME/include" \
    "-DFFI_INCLUDE=$WORK/ffi/include" "-DFFI_LIBRARY=$WORK/ffi/lib/libffi.a"
cmake --build "$WORK/build/lwjgl" -j "$JOBS"
cmake -S "$WORK/src/shaderc" -B "$WORK/build/shaderc" "${CMAKE_ANDROID[@]}" \
    -DSHADERC_SKIP_TESTS=ON -DSHADERC_SKIP_EXAMPLES=ON -DSHADERC_SKIP_COPYRIGHT_CHECK=ON \
    -DSPIRV_SKIP_EXECUTABLES=ON -DENABLE_GLSLANG_BINARIES=OFF -DBUILD_TESTING=OFF
cmake --build "$WORK/build/shaderc" --target shaderc_shared -j "$JOBS"
cmake -S "$WORK/src/spvc" -B "$WORK/build/spvc" "${CMAKE_ANDROID[@]}" \
    -DSPIRV_CROSS_SHARED=ON -DSPIRV_CROSS_STATIC=OFF -DSPIRV_CROSS_CLI=OFF -DSPIRV_CROSS_ENABLE_TESTS=OFF
cmake --build "$WORK/build/spvc" -j "$JOBS"
cmake -S "$WORK/src/openal" -B "$WORK/build/openal" "${CMAKE_ANDROID[@]}" \
    -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF -DALSOFT_TESTS=OFF \
    -DALSOFT_BACKEND_OBOE=ON -DALSOFT_REQUIRE_OBOE=ON "-DOBOE_SOURCE=$WORK/src/oboe" \
    -DALSOFT_BACKEND_OPENSL=ON -DALSOFT_REQUIRE_OPENSL=ON -DALSOFT_BACKEND_WAVE=OFF
cmake --build "$WORK/build/openal" -j "$JOBS"
LIB="$WORK/lwjgl/lib/arm64-v8a"
mkdir -p "$LIB" "$WORK/lwjgl/legal"
cp "$WORK/build/lwjgl/lib/"*.so "$LIB/"
cp -L "$WORK/build/shaderc/libshaderc/libshaderc_shared.so" "$LIB/libshaderc.so"
cp -L "$WORK/build/spvc/libspirv-cross-c-shared.so" "$LIB/libspirv-cross.so"
cp -L "$WORK/build/openal/libopenal.so" "$LIB/libopenal.so"
cp -L "$WORK/freetype/lib/libfreetype.so" "$LIB/libfreetype.so"
for binary in "$LIB/"*.so; do "$STRIP" --strip-unneeded "$binary"; done
# Preserve every source project's license files, including bundled third parties.
python3 "$NATIVE_SCRIPTS/licenses.py" "$WORK/src" "$WORK/lwjgl/legal" lwjgl freetype shaderc spvc openal oboe libffi
verify "$WORK/lwjgl" lwjgl
pack_sources lwjgl lwjgl freetype shaderc spvc openal oboe libffi
tar -C "$WORK/lwjgl" -cJf "$DIST/lwjgl-3.4.1-android-arm64.tar.xz" .
