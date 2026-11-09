#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
NATIVE_SCRIPTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$NATIVE_SCRIPTS/../.." && pwd)
WORK=${NATIVE_WORK:-$REPO/.work/natives/build}
DIST=${NATIVE_DIST:-$REPO/.work/natives/dist}
mkdir -p "$WORK/src" "$WORK/build" "$DIST"
JOBS=${NATIVE_JOBS:-$(getconf _NPROCESSORS_ONLN)}

source_tree() {
    local name=$1 repo commit
    read -r _ repo commit < <(awk -v n="$name" '$1 == n' "$NATIVE_SCRIPTS/pins.tsv")
    test -n "$commit"
    if [[ ! -d "$WORK/src/$name/.git" ]]; then
        git init -q "$WORK/src/$name"
        git -C "$WORK/src/$name" fetch -q --depth 1 "https://github.com/$repo.git" "$commit"
        git -C "$WORK/src/$name" checkout -q --detach FETCH_HEAD
    fi
    test "$(git -C "$WORK/src/$name" rev-parse HEAD)" = "$commit"
}

ndk_setup() {
    : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK r28c or newer}"
    local host=linux-x86_64
    [[ $(uname -s) != Darwin ]] || host=darwin-x86_64
    TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host"
    export CC="$TOOLCHAIN/bin/aarch64-linux-android28-clang"
    export CXX="$TOOLCHAIN/bin/aarch64-linux-android28-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    export STRIP="$TOOLCHAIN/bin/llvm-strip" OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
    READELF="$TOOLCHAIN/bin/llvm-readelf"
    PAGE_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    CMAKE_ANDROID=(-G Ninja -DCMAKE_BUILD_TYPE=Release
        "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static
        "-DCMAKE_SHARED_LINKER_FLAGS=$PAGE_FLAGS" "-DCMAKE_EXE_LINKER_FLAGS=$PAGE_FLAGS")
}

build_freetype() {
    source_tree freetype
    cmake -S "$WORK/src/freetype" -B "$WORK/build/freetype" "${CMAKE_ANDROID[@]}" \
        -DBUILD_SHARED_LIBS=ON -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON \
        -DFT_DISABLE_PNG=ON -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON \
        "-DCMAKE_INSTALL_PREFIX=$WORK/freetype"
    cmake --build "$WORK/build/freetype" -j "$JOBS"
    cmake --install "$WORK/build/freetype"
}

verify() {
    python3 "$NATIVE_SCRIPTS/verify.py" "$READELF" "$1" | tee "$DIST/$2-alignment.tsv"
}

# Publish corresponding sources, including dependency pins, patches and our scripts.
pack_sources() {
    local name=$1; shift
    local stage="$WORK/sources-$name"
    mkdir -p "$stage/scripts" "$stage/src"
    cp -R "$NATIVE_SCRIPTS/." "$stage/scripts/"
    for dep in "$@"; do
        mkdir -p "$stage/src/$dep"
        git -C "$WORK/src/$dep" archive HEAD | tar -x -C "$stage/src/$dep"
    done
    if [[ -d "$stage/src/shaderc" ]]; then
        for dep in "$WORK/src/shaderc/third_party/"*; do
            [[ -d "$dep/.git" ]] || continue
            mkdir -p "$stage/src/shaderc/third_party/$(basename "$dep")"
            git -C "$dep" archive HEAD | tar -x -C "$stage/src/shaderc/third_party/$(basename "$dep")"
            printf '%s %s\n' "shaderc/third_party/$(basename "$dep")" "$(git -C "$dep" rev-parse HEAD)" >> "$stage/scripts/transitive-pins.txt"
        done
    fi
    tar -C "$stage" -cJf "$DIST/$name-sources.tar.xz" .
}
