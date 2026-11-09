# Android natives

Build in progress; no release is pinned until the released binaries pass verification.
Only `scripts/natives/**`, this document, and `.github/workflows/natives.yml` are owned by the native builder.

## Build and fetch

Ubuntu 24.04, Temurin 25 (host build tools only), Android NDK **r28c / 28.2.13676358**, API **28**, aarch64 / arm64-v8a. Every link uses `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`. C++ dependencies link libc++ statically. CI is manual (`Android natives` / `workflow_dispatch`) and publishes only after all three jobs pass the architecture, image layout and alignment gates. Sources, licenses, per-file hashes and alignment reports accompany binaries. No binaries are committed.

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r28c
export JAVA_HOME=/path/to/host-jdk-25
scripts/natives/jdk/build.sh
scripts/natives/lwjgl/build.sh
scripts/natives/jna.sh
# After publication:
scripts/natives/fetch.sh natives-YYYYMMDD-N
```

The fetcher verifies SHA256SUMS before extracting the complete JRE into `.work/natives/<tag>/jre25/` and the native files into `.work/natives/<tag>/lib/arm64-v8a/`. Keep JRE `conf`, `legal`, `release`, `lib/modules`, `lib/server/libjvm.so`, other libraries and launcher helpers together. Runtime launch paths and Android packaging are handled by the runtime module.

## Exact Minecraft 26.2 native names

Verified against Mojang's [manifest](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json), version JSON (26.2), client SHA-1 `2dc72797acbc1b63fc16a11c4ac393605f453754`, and Maven Central's **3.4.1 natives-linux-arm64** jars. The version JSON lists Linux x64 natives; ARM64 jars were inspected directly, not inferred. These glibc binaries are reference inventories only and are never packaged.

| Module | `.so` in ARM64 jar | Android handling |
|---|---|---|
| lwjgl | liblwjgl.so | Build JNI + static libffi |
| lwjgl-stb | liblwjgl_stb.so | Build |
| lwjgl-vma | liblwjgl_vma.so | Build bundled VMA and Vulkan headers |
| lwjgl-shaderc | libshaderc.so | Build shaderc 2026.1 with its DEPS pins |
| lwjgl-spvc | libspirv-cross.so | Build C shared API |
| lwjgl-openal | libopenal.so | Build OpenAL Soft + Oboe (AAudio), OpenSL ES fallback |
| lwjgl-freetype | libfreetype.so | Build FreeType 2.14.1 |
| lwjgl-tinyfd | liblwjgl_tinyfd.so | Build: mandatory bootstrap call |
| lwjgl-opengl | liblwjgl_opengl.so | Build JNI only: mandatory bootstrap call; no GL driver/translation layer |
| lwjgl-glfw | libglfw.so | Replaced by Cofre's runtime shim; not in this release |
| lwjgl-jemalloc | libjemalloc.so | Omit; `-Dorg.lwjgl.system.allocator=system` |
| lwjgl-vulkan | **none** (ARM64 native jar does not exist) | System libvulkan.so |
| JNA 5.17.0 | libjnidispatch.so (AAR jni/arm64-v8a/) | Extract exact pinned AAR bytes |

**Startup integration requirement:** `javap -c -p` on `com.mojang.blaze3d.platform.NativeLibrariesBootstrap` shows `loadLibraries()` unconditionally enqueues OpenGL and tinyfd before the Vulkan conditional. `loadTinyFD()` invokes `tinyfd_getGlobalChar("tinyfd_version")`; `loadOpenGL()` invokes `GL.getFunctionProvider()`. Omitting either JNI binary makes stock bootstrap fail. Android's Vulkan runtime must also bypass the desktop OpenGL provider probe: shipping `liblwjgl_opengl.so` supplies JNI, not a desktop libGL. `OPENGL_EXPLICIT_INIT=true` alone leaves a null provider and does not satisfy `requireNonNull`. No OpenGL/ES driver, EGL context, GLFW or jemalloc is built here. Tinyfd desktop dialogs are not Android UI; fatal errors should be surfaced by the launcher.

## Source pins and licenses

Authoritative machine-readable pins: [`scripts/natives/pins.tsv`](../scripts/natives/pins.tsv). The shaderc/spvc/freetype commits below are read directly from the 3.4.1 ARM64 jars' embedded `.so.git` metadata.

| Artifact / source | Tag or commit | License |
|---|---|---|
| OpenJDK [openjdk/jdk25u](https://github.com/openjdk/jdk25u) | jdk-25.0.5-ga = **25.0.5+8**, fda231468e56663992785ec199a6efb8eca3cc7a | GPL-2.0 with Classpath Exception where designated; bundled components retain notices |
| Android OpenJDK patch lineage [MojoLauncher/android-openjdk-build-17-25](https://github.com/MojoLauncher/android-openjdk-build-17-25) | 1e90171cce3eca93c14c6ec59811bd88a9792f34, patches/jre_25/android | See decision below |
| LWJGL [LWJGL/lwjgl3](https://github.com/LWJGL/lwjgl3) | 3.4.1, b800ccffab14396fc529ddb6c931b7c5c5226763 | BSD-3-Clause; bundled VMA MIT, Vulkan headers Apache-2.0/MIT, stb MIT/public domain, tinyfiledialogs zlib, liburing MIT/LGPL notices |
| [libffi/libffi](https://github.com/libffi/libffi) | v3.5.2, e2eda0cf72a0598b44278cc91860ea402273fa29 | MIT |
| [LWJGL-CI/Shaderc](https://github.com/LWJGL-CI/Shaderc) | v2026.1, 301b4ede53d59b68bf55f95bb26412d9233c8187 | Apache-2.0; DEPS components retain BSD/Apache notices |
| [LWJGL-CI/SPIRV-Cross](https://github.com/LWJGL-CI/SPIRV-Cross) | a0fba56c34a6700f1724bf9b751da5b488a3775c | Apache-2.0 / MIT as designated |
| [LWJGL-CI/FreeType](https://github.com/LWJGL-CI/FreeType) | VER-2-14-1, 526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b | FreeType License (selected); portions retain their notices |
| [kcat/openal-soft](https://github.com/kcat/openal-soft) | 1.24.3, dc7d7054a5b4f3bec1dc23a42fd616a0847af948 | LGPL-2.0-or-later; BSD and other bundled notices |
| [google/oboe](https://github.com/google/oboe) | 1.10.0, a81bb9f87d4105b84b682685d3bfbb5beca371d1 | Apache-2.0 |
| [java-native-access/jna](https://github.com/java-native-access/jna) | 5.17.0, 695ae749e7bfd92f88324147c5d96b7129efec3e | Apache-2.0 (selected dual-license option); libffi MIT |

Shaderc's committed DEPS pins glslang `f0bd0257c308b9a26562c1a30c4748a0219cc951`, SPIRV-Headers `04f10f650d514df88b76d25e83db360142c7b174`, SPIRV-Tools `fbe4f3ad913c44fe8700545f8ffe35d1382b7093`, and test-only abseil/effcee/googletest/re2. CI runs its upstream source synchronizer, never downloads prebuilt libraries. Corresponding source archives include these nested checkouts and record their resolved commits.

### JDK port license decision

[PojavLauncherTeam/android-openjdk-build-multiarch](https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch), checked at `04b90441da7e9649cb6508a037423f08d1fc531f`, has **no repository LICENSE** (GitHub license endpoint returns 404). The same is true of Mojo's builder. Public visibility is not permission to copy those build scripts. None are copied.

Mojo's Android diffs modify OpenJDK source files whose own license headers grant GPL-2.0 rights, with the Classpath Exception on designated Java library files; HotSpot is GPL-2.0. We reuse that OpenJDK-derived patch lineage under those source licenses, preserving existing headers and attributing the exact origin. The added bionic iconv/getloadavg/ifaddrs code has explicit AOSP BSD notices; the posix_spawn compatibility files have Roy Marples' BSD-2-Clause notices. These notices remain in the patch and binary distribution (`legal/cofre-port`). Unneeded ARM32/x86 portions, including glibc LGPL FPU headers, are excluded. We do not claim the whole builder repository is GPL or copy its unlicensed scripts. Cofre's newly written orchestration is GPL-3.0; it builds and distributes OpenJDK as a separate GPL-2.0 artifact with corresponding source, not by relicensing HotSpot GPL-3.0.

`MojoLauncher/unilwjgl3-builder` also has no license: read for porting ideas only; no code/patches copied. Our generated-code syscall edits are independently written. LWJGL 3.4.1 core has already removed the X11 includes; zstd is outside Minecraft's module set, so its qsort workaround is unnecessary.

### Patches

| File | Effect |
|---|---|
| jdk/patches/0001-android-bionic.patch | OpenJDK bionic port: libc/TLS/dynamic-loader differences, Android properties and networking, iconv and spawn compatibility, headless desktop linkage; excludes ARM32/x86-only changes |
| jdk/patches/0002-android-ipv6.patch | Skip Android-incompatible proc/network availability probe |
| jdk/patches/0003-statx-fallback.patch | Fall back when statx exists in libc but is blocked/missing in the kernel |
| lwjgl/patch.py | Use direct process_vm_readv/writev syscalls at API 28; use NDK UAPI structs instead of liburing compatibility duplicates; no ENOSYS success stubs |

The upstream "ignore chmod failures" patch is deliberately **not** applied: permission errors must propagate. No upstream branding patch is applied.

## Hashes and verification

JNA Maven Central AAR: `https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.aar`.

| Input | SHA-256 | LOAD alignment |
|---|---|---|
| jna-5.17.0.aar | 4dbeffffa665d97ad5aa7eee297531d3c841a86716ab7f774fd6956422b3cf38 | n/a |
| libjnidispatch.so (unmodified AAR bytes) | abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06 | 0x4000, 0x4000 |

`verify.py` runs `llvm-readelf -h` and `-lW` on every `.so` (including versioned names) and JRE `bin/java`, rejects non-aarch64 and every LOAD Align below 0x4000, and fails on an empty file set. `test_verify.py` exercises positive, 4 KB, mixed-segment and empty cases. CI also checks the JRE's full image layout, Java 25 release metadata and `file` output. Final released hashes and Mac verification will be recorded here after the release passes. No device execution is claimed.

The libffi 3.5.2 release source tarball (SHA-256 `f3a3082a23b37c293a4fcd1053147b371f2ff91fa7ea1b2a52e335676bac82dc`) supplies its generated configure files, avoiding an incompatible host libtool macro. It is bundled in the corresponding sources.
