# Android natives

**Pinned release: [`natives-20261109-7`](https://github.com/ignaciojuarez/cofre-android/releases/tag/natives-20261109-7)** (verified on the Mac 2026-11-09, see [Released artifacts](#released-artifacts)). Fetch it with `scripts/natives/fetch.sh natives-20261109-7`.
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
scripts/natives/fetch.sh natives-20261109-7
```

To rerun only a failed piece, dispatch the workflow with `pieces` set to e.g. `["jdk"]` and `reuse_run` set to an earlier run whose other pieces are green; the release job takes the missing pieces (and their alignment reports) from that run and refuses to publish without all three tarballs.

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

**LWJGL's hash check:** `org.lwjgl.system.Library.checkHash` compares the SHA-1 of every loaded `.so` with `META-INF/linux/arm64/org/lwjgl/<lib>.so.sha1` in Mojang's classes jar. That's the hash of the glibc binary in LWJGL's own `natives-linux-arm64` jar (`liblwjgl.so`: `36af32d2…`), not a version or build number, so no Android build can match it, however exact its source pin. A mismatch only prints `[LWJGL] [ERROR] Incompatible Java and native library versions detected.` (LWJGL calls it "not a security feature"). The runtime passes `-Dorg.lwjgl.util.NoHashChecks=true`; `fetch.sh` checks these files against the release's `SHA256SUMS` instead.

**Linker noise:** OpenJDK links its libraries with `DT_RPATH`, which bionic ignores, so it warns `unused DT entry: DT_RPATH` once per JRE library. `LD_LIBRARY_PATH` already covers those directories. The game-output reader drops these lines. A JDK rebuild with `-Wl,--enable-new-dtags` (DT_RUNPATH, which bionic honours) would remove them at the source.

**Exported-symbol check against Mojang's 3.4.1 linux-arm64 jars** (`llvm-nm -D --defined-only`, run on a local NDK r28c build): every `Java_org_lwjgl_*`, `FT_*`, `shaderc_*`, `spvc_*` and `al*`/`alc*` symbol the upstream libraries export is present. The deliberate differences are:
- `liblwjgl.so`: no `JAWTFunctions` JNI. There is no AWT on Android, and these are only called by LWJGL's AWT interop.
- `libfreetype.so`: upstream also bundles and exports HarfBuzz, libpng and zlib. Minecraft 26.2 does not ship lwjgl-harfbuzz, and its fonts are plain TTF/OTF, so this build disables those modules. FreeType's gzip/bzip2 entry points remain as "unimplemented" stubs.
- `libshaderc.so`: no glslang C API and no spirv-fuzz/reduce entry points. LWJGL only binds `shaderc_*`.
- C++ internals differ (libc++ vs libstdc++). Neither set is part of any LWJGL binding.

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

Mojo's Android diffs modify OpenJDK source files whose own license headers grant GPL-2.0 rights, with the Classpath Exception on designated Java library files; HotSpot is GPL-2.0. We reuse that OpenJDK-derived patch lineage under those source licenses, preserving existing headers and attributing the exact origin. The one remaining added bionic snippet (`getloadavg` via `sysinfo`) keeps its AOSP BSD-2-Clause notice. These notices remain in the patch and binary distribution (`legal/cofre-port`). Unneeded ARM32/x86 portions, including glibc LGPL FPU headers, are excluded. We do not claim the whole builder repository is GPL or copy its unlicensed scripts. Cofre's newly written orchestration is GPL-3.0; it builds and distributes OpenJDK as a separate GPL-2.0 artifact with corresponding source, not by relicensing HotSpot GPL-3.0.

`MojoLauncher/unilwjgl3-builder` also has no license: read for porting ideas only; no code/patches copied. Our generated-code syscall edits are independently written. LWJGL 3.4.1 core has already removed the X11 includes; zstd is outside Minecraft's module set, so its qsort workaround is unnecessary.

### Patches

| File | Effect |
|---|---|
| jdk/patches/0001-android-bionic.patch | OpenJDK bionic port for API 28: treat `*-android` as Linux in autoconf/make, sysv hash style, PIC everywhere, no libjsound/libsaproc/splashscreen, headless fontmanager; HotSpot: SYS_gettid, no glibc version probe, Android temp dir, libjvm path from `/proc/self/maps`, `RTLD_DI_LINKMAP` guard, getcpu warning instead of exit, `getloadavg` via `sysinfo` (bionic adds it only at API 29), no utmpx, fork/exec in error reporting, `Thread::current()` instead of the TLS asm helper; libjli exec path from `JAVA_HOME`; `/etc/resolv.conf` override (`ext.net.resolvPath`); awt path lookup from maps |
| jdk/patches/0002-android-ipv6.patch | Skip Android-incompatible proc/network availability probe |
| jdk/patches/0003-statx-fallback.patch | Fall back when statx exists in libc but is blocked/missing in the kernel |

`0001` also routes `getErrorString` in `libjava/jni_util_md.c` to bionic's POSIX `strerror_r` symbol. OpenJDK uses glibc's `__xpg_strerror_r` for the same reason; bionic has no `__xpg_` alias, and with `_GNU_SOURCE` (API ≥ 23) its header renames `strerror_r` to the `char *`-returning `__gnu_strerror_r`. Written for Cofre, not from the inherited lineage.
| lwjgl/patch.py | Use direct process_vm_readv/writev syscalls at API 28; use NDK UAPI structs instead of liburing compatibility duplicates; no ENOSYS success stubs |

Deliberately removed from the inherited lineage because bionic provides them at API 28 and the copies either shadow libc with incompatible layouts or are broken: `dlvsym` (API 24), `getifaddrs` (API 24; the copied version `memset`s an uninitialised pointer), `nl_langinfo` (API 26) and the hard-coded `"ASCII"` codeset, `getgrgid_r`/`getgrnam_r` (API 24), `iconv` (API 28, so no `libtinyiconv`), and `posix_spawn` (API 28; the dhcpcd copy ignored file actions). This also drops the matching AOSP/dhcpcd notices.

The upstream "ignore chmod failures" patch is deliberately **not** applied: permission errors must propagate. No upstream branding patch is applied.

## Hashes and verification

JNA Maven Central AAR: `https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.aar`.

| Input | SHA-256 | LOAD alignment |
|---|---|---|
| jna-5.17.0.aar | 4dbeffffa665d97ad5aa7eee297531d3c841a86716ab7f774fd6956422b3cf38 | n/a |
| libjnidispatch.so (unmodified AAR bytes) | abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06 | 0x4000, 0x4000 |

`verify.py` runs `llvm-readelf -h` and `-lW` on every `.so` (including versioned names) and JRE `bin/java`, rejects non-aarch64 and every LOAD Align below 0x4000, and fails on an empty file set. `test_verify.py` exercises positive, 4 KB, mixed-segment and empty cases. CI also checks the JRE's full image layout, Java 25 release metadata and `file` output. No device execution is claimed.

The libffi 3.5.2 release source tarball (SHA-256 `f3a3082a23b37c293a4fcd1053147b371f2ff91fa7ea1b2a52e335676bac82dc`) supplies its generated configure files, avoiding an incompatible host libtool macro. It is bundled in the corresponding sources.

## Released artifacts

Release [`natives-20261109-7`](https://github.com/ignaciojuarez/cofre-android/releases/tag/natives-20261109-7), workflow run [42586160735](https://github.com/ignaciojuarez/cofre-android/actions/runs/42586160735). `jdk` and `lwjgl` were built in run [42583890396](https://github.com/ignaciojuarez/cofre-android/actions/runs/42583890396) at `4f037c2` and reused; `jna` was rebuilt at `7eb0620`. The native build scripts and patches are identical at both commits. Toolchain for all: Ubuntu 24.04, NDK r28c (28.2.13676358), API 28, Temurin 25 host JDK.

| Asset | Size | SHA-256 | Source / tag | Patches | License |
|---|---|---|---|---|---|
| jre25-android-arm64.tar.xz | 45,951,484 | 0269f3ac87dde79501b07483466fc454eb474ea124794807825f5ef5d77933eb | openjdk/jdk25u jdk-25.0.5-ga + FreeType VER-2-14-1 | jdk/patches/0001–0003 | GPL-2.0 + Classpath Exception; FreeType License |
| lwjgl-3.4.1-android-arm64.tar.xz | 3,929,412 | accb3d1385a9b10fee5927ec6fdbde50605f3d651feb45bf053d1bd439b8c50d | LWJGL 3.4.1 + deps in the pin table | lwjgl/patch.py | see pin table |
| jna-5.17.0-android-arm64.tar.xz | 40,720 | e59306494a446f256402ebc3f66939e0216b968f654bf25fc55109953fba51c9 | JNA 5.17.0 AAR (unmodified) | none | Apache-2.0; libffi MIT |
| jdk25-sources.tar.xz | 78,734,152 | 6ea4332ea9abd45422d7cc99296464934096ec4b52099d3ea489dd497fcc3bbc | corresponding source | | |
| lwjgl-sources.tar.xz | 38,495,032 | 12459042754fd4fad72c0a790de416ef3b9a0a334769bb1ee0bf2c607918f4e8 | corresponding source | | |
| jna-sources.tar.xz | 75,611,212 | bb3749a1e0fbe464e7cba25e35cfaf4f1f3e0844e9f2d0453eb5ecd008bcd2fb | corresponding source | | |

The JRE tarball has no top-level folder: `bin/ conf/ legal/ lib/ release` sit at its root. `release` reads `JAVA_VERSION="25.0.5"`, `JAVA_RUNTIME_VERSION="25.0.5+8"`, `OS_ARCH="aarch64"`. The library tarballs extract to `lib/arm64-v8a/` and `legal/`.

Mac verification (`fetch.sh`, then NDK `llvm-readelf -h` / `-lW`): all 46 ELF files (45 `.so` + `bin/java`) are AArch64 and every LOAD `Align` is 0x4000.

| File | Size | SHA-256 | LOAD Align |
|---|---|---|---|
| jre25/bin/java | 7,424 | 8b6f013485e3c24d513146c29299663a30712d0aa583af9aaa30bd31115a84bd | 0x4000 |
| jre25/lib/server/libjvm.so | 26,692,528 | 6ed057c1cf2b7f820719c2eaee59616929bb0284cb73d8961ed711ee3fced96b | 0x4000 |
| liblwjgl.so | 561,864 | 5e0e765789d1ea2e867a2e17ffff09cec0b2cdc72ce4b6a070ad5973da79d936 | 0x4000 |
| liblwjgl_stb.so | 424,456 | 5e2f32cf12ade943e23535b399d0a240ea130c1a9514c3568cab8ee4cddd14b0 | 0x4000 |
| liblwjgl_vma.so | 373,056 | d393ffa2d74717938a4e0222acb400b37f64d1e1f1979a70fcdb12cd41c3be7c | 0x4000 |
| liblwjgl_tinyfd.so | 85,392 | 92fe66680b5c7b85f7d72518facb568d3f20ca410a82ef83419f6d5d859854d0 | 0x4000 |
| liblwjgl_opengl.so | 335,568 | 03d35205245f1ef7b0a110688efa8ebbbc0b72b1ba6595074ac6fdeb52c3544b | 0x4000 |
| libshaderc.so | 8,806,688 | 2691050d17204d74ac1681a9a7ec7df75cb8d7c4cbdae5544825ea4526fb2c9f | 0x4000 |
| libspirv-cross.so | 3,491,504 | 4a010231c4656ca124f5be4570ee114da75f9ba1c79ec4b4d2d598896bc27f15 | 0x4000 |
| libopenal.so | 1,781,152 | fa92af2f282d3f387c87932cd86f522e8392ea36f104bbb4db1e313299d3c445 | 0x4000 |
| libfreetype.so | 781,232 | cc69f4341b61ae97a1a716c908d4d03309ffb7efbf6759893fabb3181524183d | 0x4000 |
| libjnidispatch.so | 176,520 | abc26e994517bcaa3309acdb0a27373864086c7569c89d3087b8626fada9ef06 | 0x4000 |

Per-file hashes for every JRE library are in the `jre25-alignment.tsv` release asset.
