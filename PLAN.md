# Cofre for Android: plan

Status (2026-11-09): phases 1, 2 and 5 are built and emulator-tested; phases 3 and 4 run on the emulator up to Minecraft's own Vulkan device check, which the emulator fails (as expected). The first frame waits for the Odin 3. Facts marked ✔︎ were checked against the game jars, Maven, Modrinth or real devices.

## 🎯 Goal
A native Android launcher for **Minecraft Java 26.2 + NeoForge**, rendering with **Mojang's Vulkan backend on the device's own driver**. It's controller-first on the **AYN Odin 3 / Odin 3 Portal**. It carries Cofre's look and mod features, and nothing that's iOS-only.

## ✅ Decisions
| Topic | Decision |
|---|---|
| Minecraft | **26.2 only** (final release, 2026-06-16) |
| Loader | **NeoForge 26.2** (latest `26.2.0.93`) |
| Renderer | **Vulkan only** (`--graphicsBackend vulkan`). No GL translation layers (no Zink, GL4ES, MobileGlues or ANGLE) |
| GPU driver | The device's Qualcomm driver by default; importing a Turnip driver comes later |
| Runtime | **Our own, built from source.** Zalith Launcher 2 and FCL are reference designs only |
| UI | New Kotlin + Compose app that reuses Cofre's design system and screens |
| Orientation | **Landscape only** (`sensorLandscape`). No portrait or compact layouts: a landscape phone gets the rail, the Odin 3 the sidebar |
| Repo | `cofre-android`, GPL-3.0. App id `com.ignacio.cofre` (Android and iOS ids live in separate stores, so it can match iOS) |
| Test hardware | Odin 3 is ordered. Until it arrives, the emulator and the OnePlus 5 can test the launcher UI only |

## 🔬 Verified facts
**The 26.2 client jar ✔︎**
- Java 25.
- Libraries: LWJGL **3.4.1** (core, glfw, vulkan, vma, shaderc, spvc, openal, freetype, jemalloc, stb, tinyfd, opengl) and JNA 5.17.
- The Vulkan backend lives in `com.mojang.blaze3d.vulkan`. It needs **Vulkan 1.2**, plus:
  - extensions: `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain`
  - features: `multiDrawIndirect`, `fillModeNonSolid`, `samplerAnisotropy`, `shaderDrawParameters`, `timelineSemaphore`
- Window access goes through GLFW, which only has to provide `glfwVulkanSupported`, `glfwGetRequiredInstanceExtensions` and `glfwCreateWindowSurface`.
- Backend choice: the `--graphicsBackend` argument, plus the `preferredGraphicsBackend` and `startedCleanly` keys in options.txt.

**NeoForge 26.2 mods on Modrinth ✔︎**

| Mod | Version | Works on Vulkan? |
|---|---|---|
| Sodium | `mc26.2-0.9.2-neoforge` (release) | ✅ |
| Controlify | `3.5.3+mc26.2` | ❌ for now: finds no controller without an Android SDL3, so it's out of the preset (see Controller) |
| Distant Horizons | `3.3.3-26.2` | ❌ for now: renders on Vulkan, but its SQLite and zstd natives have no Android build, so worlds don't load |
| ImmediatelyFast | `1.16.5+26.2-neoforge` | ✅ since 1.16.0 |
| Entity Culling | `1.11.3` | ✅ |
| FerriteCore | `9.0.0-neoforge` | ✅ |
| ModernFix (mVUS fork) | `5.27.21+mc26.2` | ✅ |
| NeoLoading-Vulkan-Fix | `1.0.0` | turns off NeoForge's GL early window |
| Iris | `1.11.4+26.2-neoforge` | ❌ OpenGL only, so no shaders for now |

**Devices ✔︎**

| Device | Runs the game on Vulkan? | Why |
|---|---|---|
| OnePlus 5 (Adreno 540, Android 9) | ❌ | Vulkan 1.1, and it's missing dynamic_rendering, sync2, the divisor extension and timeline semaphores |
| Emulator (Android 17, llvmpipe) | ❌ | Vulkan 1.3, but it's missing push_descriptor and the EXT divisor (only the KHR divisor is there) |
| Odin 3 / Portal (Snapdragon 8 Elite, Adreno 830) | ✅ expected | Check it in Phase 0 |

**Reference launchers ✔︎**
- **Zalith Launcher 2** (GPL-3.0+, plus §7 terms that forbid the "Zalith" name and require an "Unofficial Modified Version" label). Its open bugs hit our setup: #2060 (NeoForge on Vulkan fails with `NATIVE_WINDOW_IN_USE`) and #1270 (its bundled Turnip crashes on the Adreno 830).
- **FCL** (GPL-3.0): its `vkshim` fakes the EXT divisor from the KHR one, and fakes `fillModeNonSolid`.
- Both launchers ship mostly **4 KB-aligned** natives. Their runtimes are near-identical prebuilt binaries.

## 🏗️ Architecture
```
Cofre (Compose)                        main process
 ├ core/kit      pure Kotlin/JVM: Modrinth, versions, NeoForge meta, instances, mods, worlds, servers, logs
 ├ core/design   tokens, block components, pixel icons, focus + button hints
 └ runtime       Java manager, natives, launch args, Vulkan probe, NeoForge installer
        │ Intent
        ▼
:game process      GameActivity + SurfaceView → ANativeWindow
   HotSpot JDK 25 (dlopen libjli → JLI_Launch)
   MC 26.2 + NeoForge → LWJGL 3.4.1 → our GLFW shim → vkCreateAndroidSurfaceKHR → libcofrevk (pre-rotation) → system libvulkan
   SDL3 (controllers for Controlify) · OpenAL Soft (AAudio)
:installer process NeoForge install processors on the same JDK 25
```
- Separate processes, because the game calls `System.exit` and must never take the launcher down with it.
- **Never touch the game surface with EGL/GL.** That's what causes `NATIVE_WINDOW_IN_USE`.

## 🧩 Runtime pieces (all built from source, all 16 KB-aligned)
| Piece | Plan | Notes |
|---|---|---|
| JRE 25 aarch64 | Build from OpenJDK `jdk-25.0.5` GA with NDK r28+ | MojoLauncher's scripts work and are 16 KB-ready, but have **no license**: ask the author, or write our own |
| LWJGL 3.4.1 natives | Build from LWJGL source with the NDK | Must match the game's 3.4.1 exactly |
| GLFW shim | Our own `org.lwjgl.glfw` replacement plus a small C bridge | Window, size, input callbacks and the 3 Vulkan functions |
| OpenAL Soft | Build with the AAudio/Oboe backend | |
| shaderc, spirv-cross, VMA, freetype | Build | Use `-Dorg.lwjgl.system.allocator=system` instead of jemalloc |
| JNA 5.17 | Take `libjnidispatch.so` from the Maven `.aar` (arm64, 16 KB) | The `.jar` only carries glibc builds |
| SDL3 | Build upstream 3.4.x and initialise it under ART | Only for Controlify's controller access |
| Vulkan | System `libvulkan.so` | Turnip later, through a linker-namespace loader (mojoexec, MIT; or libadrenotools, BSD-2) |
| Vulkan shim | Our own, following FCL's vkshim idea | Only if the Odin driver lacks something |

## 🧰 Stack (latest stable, checked 2026-11-08)
| Area | Pick | SwiftUI equivalent |
|---|---|---|
| IDE | Android Studio Rabbit 2 Patch 1 (2026.2.2.9) | Xcode |
| Build | AGP 9.5.1, Gradle wrapper chosen by AGP, version catalog | SPM |
| Language | Kotlin 2.4.21 (K2), explicit API mode and warnings as errors in core | Swift 6 strict mode |
| SDK | compile/target 37, **min 28** (Android 9: the OnePlus 5 can run the launcher, and 28 is the floor for the driver-loading tricks). Play is not a target | deployment target |
| UI | Compose BOM 2026.09.00, with our own design system and little Material | SwiftUI |
| Navigation | Navigation 3 1.2.1 (the back stack is a plain list) | `NavigationStack(path:)` |
| State | State-holder classes with `mutableStateOf`; ViewModel only where it must outlive the screen | `@Observable` |
| Concurrency | Coroutines 1.11, structured scopes, `Mutex` | async/await, actors |
| Network + JSON | OkHttp 5.5 + kotlinx.serialization 1.12 | URLSession + Codable |
| Settings, secrets | DataStore 1.3, with tokens AES-GCM-encrypted by a Keystore key | UserDefaults, Keychain |
| Images | Coil 3.6 | AsyncImage |
| Dependency injection | Constructor injection plus a few `CompositionLocal`s; no framework | `@Environment` |
| Tests | JUnit 5 + kotlin.test (core/kit runs on the Mac JVM), Roborazzi screenshots, Compose UI tests on the emulator | Swift Testing, previews |
| Native | NDK 30, CMake 4.1 | — |

Kept out on purpose:
- Room: the game's files are the truth.
- Hilt and Retrofit: not needed.
- `tv-material`: built for TVs, while handhelds use touch and controller together.

## 📁 Modules
Four modules. No build-logic plugins and no per-feature modules: for one or two people they add build files, not clarity.
```
cofre-android/
├ gradle/libs.versions.toml   every version, plus compile/target/min SDK
├ app/                        Application, MainActivity, navigation, screens (one package per area)
├ core/kit/                   pure Kotlin/JVM (no Android) ← port of CofreKit + fixtures
├ core/design/                tokens, fonts, components, PixelIcon, focus + controller hints
├ runtime/                    Android lib + NDK: JRE, natives, GLFW shim, :game + :installer
└ scripts/                    gradle.sh (one build at a time), test.sh, natives/ builds
```

## 🔁 Porting from Cofre iOS
| Area | Lines | Verdict |
|---|---|---|
| CofreKit logic: Modrinth, pages, versions, content, compatibility, modpacks, instances, worlds, servers, logs | ~7k | **Port** to `core/kit`, together with its tests |
| Mod install/update behavior in `CofreBackendAdapter+Mods` | ~1.7k | **Rewrite** in Kotlin: staged install, rollback, SHA-1 lookup |
| CofreUI screens, components, tokens, pixel icons, fonts | ~13k | **Rebuild** in Compose with the same tokens and look |
| Server cards (Ed25519 envelope) | 577 | **Port**; the byte format is portable |
| JIT, VPN, Llave, Air bridge, Metallum, signing, iOS setup steps | ~8.7k | **Drop** |
| Presets and the known-mods data | — | Same mechanism, new Vulkan/NeoForge 26.2 data |

New on Android:
- a Java runtime manager
- a NeoForge installer
- a Vulkan suitability check, shown before Play
- a scanner that flags jars calling OpenGL (under Vulkan they abort with no crash log)
- a DocumentsProvider that exposes instances to file managers
- `.mrpack` opening from other apps
- self-update from GitHub

## 🎮 Controller
- **Launcher:** Compose focus on every screen, with a visible block-style focus ring.
  - A → select, B → back, L1/R1 → tabs, triggers → scroll, Y → search, Start → Play.
  - A bottom hint bar shows button glyphs for the current layout (Odin Xbox/Nintendo mode, Xbox, PlayStation).
  - Every dialog sets its first focus, and focus returns where it was on back.
- **In game:** our own mapper in `GameActivity` (stick → mouse, buttons → keys; the D-pad moves focus in menus).
  - Controlify later, once there's an Android SDL3 it can load (`-Ddev.isxander.sdl.library`); then the built-in mapper steps aside so presses don't count twice.
- **Typing:** the soft keyboard shows while a game text field has focus (the GLFW shim reads it from Minecraft's text-input ticks). Search fields open a dedicated text sheet.

## 📦 Mod folders
- A folder (`filesDir/mod-folders/<id>/`) holds `mods/`, `resourcepacks/`, `shaderpacks/` and `cofre-mods.json`, with a game version and loader. Instances link to one; several can share it; deleting an instance keeps it.
- An instance's content dirs are relative symlinks into its folder; the launcher relinks before every launch. FML follows them ✔︎ (emulator, 2026-11-09).
- Folders for another loader or game version can be made (Convert) and kept, but only folders that fit the instance and `Target` can be linked or played.
- Snapshots (before each update, last good launch, manual) store each jar once per folder; Android blocks hard links in app data ✔︎, so the first snapshot is one full copy.

## ⚠️ Edge cases
- 🖼️ **Graphics**
  - NeoForge's early window is GL: write `earlyWindowControl = false` to `config/fml.toml`, or ship NeoLoading-Vulkan-Fix.
  - Never create EGL on the game surface.
  - Fall back clearly when the device fails the Vulkan checks: a "This device can't run Vulkan Minecraft" screen with the reason, not a crash.
  - GL-calling mods abort with no log: scan jars before launch.
  - No shaders (Iris is GL-only).
- 🔋 **Handheld**
  - Sleep/Home: Minecraft makes its Vulkan surface once, so the SurfaceView keeps its surface through a stop (`FOLLOWS_ATTACHMENT`, Android 14+) and the game sees a minimized 0×0 window until it's back.
  - Docking over DisplayPort changes the resolution.
  - A Bluetooth pad can become player 1.
  - Odin firmware swaps A/B in Xbox mode.
  - 120 Hz and heat: thermal headroom, ADPF hints.
  - The back paddles may be eaten by the system.
- 🧠 **Memory:** set `-Xmx` by RAM tier (8 GB → 4 GB heap, 12 GB → 6 GB, 16 GB+ → 8 GB). The low-memory killer SIGKILLs without warning.
- 📦 **Platform**
  - 16 KB page devices: every `.so` must be aligned.
  - Landscape only: `sensorLandscape` on both activities; `android:appCategory="game"` keeps Android 16+ honoring it on large screens.
  - Short screens: a landscape phone is about 400 dp tall, so screens scroll or fit that height.
  - Edge-to-edge and immersive mode, predictive back.
  - Big downloads use user-initiated data-transfer jobs.
  - The launcher can die while the game runs: the game process must not depend on it.
- 🔐 **Accounts**
  - Microsoft sign-in with OAuth + PKCE in a Custom Tab.
  - The Azure client ID needs Mojang approval (weeks), so ask now. The iOS app's ID may be reusable with an Android redirect URI.
- 🛒 **Distribution**
  - No Google Play: its policy bans downloading and running code.
  - GitHub Releases plus Obtainium.
  - Google's developer verification for sideloaded apps arrives in 2027.

## 🗺️ Phases
| # | Phase | Done when | State (2026-11-09) |
|---|---|---|---|
| 0 | 🚦 Hardware check (Odin 3 arrives) | `cmd gpu vkjson` shows 26.2's needs; our app reaches a first frame | 🔜 waits for the Odin 3 |
| 1 | Repo skeleton, design system, every screen | Fully controller-navigable on the emulator; screenshot tests pass | ✅ |
| 2 | `core/kit` port | Ported tests pass on the Mac JVM | ✅ ~270 kit tests |
| 3 | Runtime: JRE, natives, GLFW shim, `:game` | Vanilla 26.2 shows a first frame on Vulkan on the Odin 3 | 🟡 Our JRE 25 + LWJGL 3.4.1 natives (`natives-20261109-7`, all 16 KB-aligned) boot the game; it reaches Minecraft's Vulkan device check on the emulator |
| 4 | NeoForge install + launch, Sodium, the handheld preset | Modded 26.2 is playable with the built-in controller | 🟡 NeoForge 26.2.0.93 installs in `:installer` and launches to the Vulkan check; the handheld preset installs; controller play needs the Odin 3 |
| 5 | Accounts, mod browse/install/update, modpacks, worlds, servers, logs, crash screen | Same features as iOS | ✅ built; Microsoft sign-in needs an approved client ID |
| 5b | Mod folders (shared by instances, convert to another loader/version, auto-update, snapshots) and the Home dashboard | FML loads a folder's mods through the instance's links | ✅ built; launch through links checked on the emulator (2026-11-09) |
| 6 | Handheld polish, release pipeline | A signed GitHub release that self-updates | ⏳ |

What to run first on the Odin 3: `runtime/README.md` → "Needs the real device".

## ⏳ Long-lead items (start now)
1. Azure app plus Mojang approval for Microsoft sign-in.
2. ~~JDK build scripts~~ ✅ our own scripts and patches (`scripts/natives/`, `runtime/NATIVES.md`).
3. CurseForge API key policy: Modrinth-only at first; add CurseForge later through a user-supplied key or a small proxy.
4. Decide whether to sign up for Google's developer verification for sideloaded apps.

## ❓ Open questions
- Reuse the iOS Azure client ID (add an Android redirect `cofre://auth`), or register a new one? Either way it needs Mojang's approval before sign-in works.
- Does the Odin driver expose everything 26.2 needs? Phase 0 answers this.
- When to add 26.3/26.4 (SDL3 instead of GLFW): only the runtime's window/input layer changes.

## 🔗 References
- Zalith Launcher 2: https://github.com/ZalithLauncher/ZalithLauncher2 (window/SDL hooks, `:game` process, Vulkan requirement table)
- FoldCraftLauncher: https://github.com/FCL-Team/FoldCraftLauncher (vkshim, driver loading, `fml.toml` handling)
- MojoLauncher JDK builds: https://github.com/MojoLauncher/android-openjdk-build-17-25
- mojoexec (MIT Vulkan driver loader): https://github.com/MojoLauncher/mojoexec
- libadrenotools: https://github.com/bylaws/libadrenotools
- Turnip builds (MIT): https://github.com/nihui/mesa-turnip-android-driver
- NeoForge maven: https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml
- Cofre iOS (source of the UI and the logic): https://github.com/ignaciojuarez/cofre
