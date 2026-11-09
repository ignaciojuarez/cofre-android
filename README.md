# Cofre for Android

A Minecraft: Java Edition launcher for Android handhelds such as the AYN Odin 3. It runs **Minecraft 26.2 with NeoForge** on Mojang's own **Vulkan** renderer, using the device's GPU driver directly, with no OpenGL translation layer. The whole app works with the built-in controller; touch works too.

It's the Android sibling of [Cofre for iPhone and iPad](https://github.com/ignaciojuarez/cofre): same look, same mod features, built natively for Android.

> Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

## Status
Early development. The launcher, mod browser and launch pipeline run on the emulator. The first in-game frame waits for real hardware; see [`PLAN.md`](PLAN.md).

## What your device needs
Minecraft 26.2's Vulkan renderer needs **Vulkan 1.2** with `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2` and `VK_EXT_vertex_attribute_divisor`. We expect recent Snapdragon chips (8 Gen 2 and newer, such as the AYN Odin 3's 8 Elite) to qualify, but that isn't confirmed on hardware yet. Cofre checks your device on first run and tells you exactly what's missing.

## Features
- **Play:** one button into your last world or a server, with launch progress and a plain explanation when the game stops.
- **Instances:** NeoForge 26.2 instances with a handheld preset (Sodium, Controlify, ImmediatelyFast and more), worlds, backups, import and export.
- **Mods:** Modrinth (and CurseForge with your own key) search, mod pages, dependency-aware installs with rollback, updates, and a check that flags mods that call OpenGL, since those crash on Vulkan.
- **Controller first:** every screen works with the D-pad, A/B, L1/R1 and the triggers, with button hints for Xbox, PlayStation and Nintendo layouts.
- **Files:** instances show up in Android's file picker, so you can drop in mods and worlds with any file manager.

## Build
Requirements: macOS or Linux, Android Studio Rabbit 2 (2026.2.2) or newer, and an Android SDK with platform 37 and NDK 30.

```sh
scripts/natives/fetch.sh natives-20261109-7   # Java 25 + game natives (pinned in gradle.properties)
scripts/test.sh                               # tests, screenshots, lint, debug APK
```

Microsoft sign-in needs an Azure app client ID that Mojang has approved for Minecraft services. Put it in an untracked `secrets.properties` as `MICROSOFT_CLIENT_ID=…`.

## Layout
| Path | What |
|---|---|
| `app/` | The Compose app: screens, navigation, sign-in, file provider |
| `core/kit/` | Pure Kotlin logic: Modrinth, CurseForge, versions, downloads, mods, instances, worlds, servers, logs |
| `core/design/` | Cofre's block design system and controller focus |
| `runtime/` | Java 25 runtime, GLFW shim, game and installer processes, launch |
| `scripts/natives/` | Reproducible builds of every native library ([`runtime/NATIVES.md`](runtime/NATIVES.md)) |

## License
GPL-3.0. Third-party credits are in [`NOTICE`](NOTICE).
