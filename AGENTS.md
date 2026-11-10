# Cofre for Android: rules for AI agents

Cofre for Android is a native Kotlin + Jetpack Compose launcher for Minecraft: Java Edition **26.2 + NeoForge**. It renders with Mojang's Vulkan backend on the device's own driver. It's controller-first for Android handhelds (AYN Odin 3). The UI and the features come from Cofre iOS (`ignaciojuarez/cofre`).

## Status (update when it changes)
- Read `PLAN.md` for decisions, verified facts and phases.
- Launcher, kit, design system and runtime are built and emulator-tested. The game reaches Minecraft's Vulkan device check on the emulator; the first frame waits for the Odin 3 (`runtime/README.md` → "Needs the real device").

## Hard rules
1. **Scope:** MC 26.2, NeoForge, Vulkan only. Never add GL translation layers (Zink, GL4ES, MobileGlues, ANGLE) or other game versions without asking. The app is **landscape only**: never add portrait or phone-width (compact) layouts.
2. **Licenses:** this repo is GPL-3.0.
   - Never copy AGPL code (Terracotta, FlameLauncher, StikDebug).
   - Never copy code from Zalith Launcher 2 without asking first. Its GPL §7 terms would force an "Unofficial Modified Version" label.
   - Code from FCL (GPL-3.0) and from MIT/BSD projects (mojoexec, libadrenotools) is allowed, with attribution in `NOTICE`.
   - Never vendor a binary without its source, version and SHA-256 recorded in `runtime/NATIVES.md`.
3. **Natives:** every `.so` must be built from pinned source with 16 KB alignment. Check with `llvm-readelf -lW` (LOAD `Align` must be at least 0x4000).
4. **Game surface:** never create EGL/GL on the game's `ANativeWindow`.
5. **Architecture:** the game's files are the truth.
   - `core/kit` is pure Kotlin/JVM (no Android imports) and is tested on the Mac.
   - One small type per domain. No interface with only one implementation, no forwarding layers.
   - Delete what a new piece replaces in the same change.
6. **Never commit** `local.properties`, keystores, `secrets.properties`, API keys, APKs, or `build/`, `.work/` or `artifacts/`.
7. **Never log** tokens, account data or anything a user would consider private.
8. **Devices:** use T3 device tools for the emulator and phones. Reuse the existing AVDs; never create or erase them unless asked.
9. **Commits and PRs:** `[TYPE] Imperative, user-meaningful outcome`, where TYPE is one of BUG, FEATURE, UI/UX, REFACTOR, PERF, SECURITY, CLEAN, DOCS, HOTFIX or REVERT.
10. **"Done" means verified:** tests pass, and for game or runtime features, the device log shows the marker. Stubs and "should work" don't count.

## Where things live
| Path | What |
|---|---|
| `core/kit/` | Pure Kotlin/JVM logic, one package per domain plus `common` (Kit's JSON/HTTP, `FileOps`/`Zip`, hashes, target constants) (`accounts`, `modrinth`, `curseforge`, `pages`, `versions`, `downloads`, `mods`, `modfolders`, `instances`, `content`, `compat`, `modpacks`, `worlds`, `servers`, `logs`, `servercards`, `options`). Tests use JUnit 6 and run on the Mac |
| `core/design/` | Compose design system ported from Cofre iOS (`app/UI/Sources/CofreUI/Tokens`, `Components`, `Layout`). Screenshot tests use Roborazzi |
| `app/` | The app: screens in `com.ignacio.cofre.ui.<area>`, navigation, wiring |
| `runtime/` | Java runtime, natives, GLFW shim, launch, NeoForge install, `:game` and `:installer` processes |
| `scripts/natives/` | Builds for every native library (see `runtime/NATIVES.md`) |

The iOS source this ports from: `/Volumes/microwave/Developer/cofre` (or `github.com/ignaciojuarez/cofre`). Read only; never edit it from here.

## Build and test
    scripts/gradle.sh <tasks>   # always use this: right JDK, one build at a time (agents share the checkout)
    scripts/test.sh             # everything device-free: kit tests, unit + screenshot tests, lint, debug APK
    scripts/gradle.sh :core:kit:test
    scripts/gradle.sh recordRoborazziDebug   # update screenshot baselines after an intended UI change

## Working in parallel (several agents share this checkout)
- Stay inside the paths your task owns. Never edit, reformat or delete another area's files.
- `gradle/libs.versions.toml` and `settings.gradle.kts` are shared: re-read right before editing, change only your lines.
- Keep your module compiling after every step. If it breaks because of someone else's files, wait a few minutes and retry; don't fix their code.
- Commit only your own paths: `git add <your paths> && git commit -m "…" -- <your paths>`. Retry if `.git/index.lock` exists.
