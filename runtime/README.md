# runtime

The game side of Cofre: Vulkan check, JRE install, the `:game` process, the GLFW shim.

## API for the app
| Call | What |
|---|---|
| `VulkanSupport.check(): VulkanReport` | Driver vs 26.2's needs: `suitable`, `missing`, per-extension/feature maps. Logs `[Cofre] vulkan check suitable=… missing=[…]` |
| `JreManager(context).install(): File` | Unpacks the bundled JRE into `filesDir/runtimes/jre-25` (atomic, version-stamped). `isInstalled()`, `bundledVersion()` |
| `LaunchSpec(...).write(file)` | javaHome, classpath, jvmArgs, mainClass, gameArgs, gameDir, logFile, exitFile. Never holds the access token: args carry `LaunchSpec.TOKEN` |
| `startActivity(GameActivity.intent(context, specFile, accessToken))` | Runs the game in `:game`. Puts the token in place of `LaunchSpec.TOKEN` in memory; adds the GLFW shim first on the classpath and the LWJGL/java.home properties |
| `ExitRecord.read(exitFile)` | `code` + `timeMillis` when the JVM ended; `null` after a crash or kill |
| `GameFiles(filesDir/minecraft)` | Shared store (`versions/`, `libraries/`, `assets/`). `install(id)` downloads client, libraries (Linux arm64 rules, no desktop natives) and assets through kit's queue, hash-checked; `progress: StateFlow<Progress>` |
| `NeoForgeInstaller(context, files).install("26.2.0.93")` | Installer jar (SHA-1 pinned for 26.2.0.93, Maven's `.sha1` for other versions), its libraries, client processors in the `:installer` process (`InstallerService`: a plain service promoted to foreground when Android allows; each step killed after `STEP_TIMEOUT_MILLIS`), output hashes, then `versions/neoforge-26.2.0.93/*.json`. Returns the version id |
| `GameLaunch(context).prepare(versionId, gameDir, GameAccount(name, uuid), heapMB, extraJvmArgs, extraGameArgs)` | The one place game files and the JRE get installed for a launch. Writes the spec and exit record to `noBackupFilesDir/launch/<instance>[.exit].json`, never into the instance; deletes an older build's `<instance>/.cofre` and old `hs_err_pid*` and `*.hprof` files (a native crash's `hs_err` and a heap dump go next to the exit record: they hold the token). Then writes `preferredGraphicsBackend:vulkan` and (NeoForge) `earlyWindowControl = false`, returns the spec file for `GameActivity.intent`. Adds `--graphicsBackend vulkan`, then `extraGameArgs` (Quick Play). `heapMB` defaults to `JavaHeap.autoMB` |
| `GameLaunch(context).exit(specFile): GameExit` | `ExitRecord`, game output, newest crash report or `hs_err`, kit's `GameExitReport`; `summary` is the line to show ("Vulkan device not suitable. This device's Vulkan driver is missing: …") |

Debug builds: `RuntimeDebugActivity` has buttons for "Install NeoForge 26.2", "Launch vanilla 26.2" and "Launch NeoForge 26.2" (`--es action install|vanilla|neoforge`), using `DevAccount` (offline `Dev`, token `0`; debug source set only).

## JRE packaging
Builds bundle the pinned natives release's `jre25-android-arm64.tar.xz` (`scripts/natives/fetch.sh <tag>`); `-Pcofre.jre=<tar.xz>` overrides it. No tarball → no JRE asset; the build still works.

## Verified (2026-11-09)
- Emulator (Android 17) and OnePlus 5 (Android 9): Vulkan report, JRE install, HotSpot 25 boots in `:game`, exit codes from `main` returning and from `System.exit`.
- `:runtime:glfw-shim:test`: every GLFW member the 26.2 client jar uses resolves.
- Emulator: NeoForge 26.2.0.93 installs (PROCESS_MINECRAFT_JAR in `:installer`, exit 0, ~12 s). Vanilla and NeoForge 26.2 launch up to LWJGL's native load (`Failed to locate library: liblwjgl.so`, diagnosed MissingLibrary) until the natives release lands; FML 11.0.16 boots with no class or module errors.

## Needs the real device (Odin 3)
- The game's first frame. Minecraft makes its Vulkan surface once: the SurfaceView keeps it through Home and sleep (`FOLLOWS_ATTACHMENT`, Android 14+), and `onStop` sends a minimized 0×0 window so the game stops acquiring images until `onStart`.
- Rotation: `libcofrevk.so` (`vk_rotate.c`) reports the IDENTITY transform and hides the SUBOPTIMAL that follows, so Android's compositor turns the picture. Check on a portrait-native panel (`adb shell wm size`) and after a 180° flip: right way up, and no swapchain rebuild every frame.
- Pointer capture with a real mouse; the built-in controller layout on the Odin pad (A/B swap in Xbox mode, back paddles); look speed.
- Touch coordinates if the surface gets a fixed lower resolution (they're in view pixels today).
- Clipboard is in-process only; no preedit. The soft keyboard should show on a focused text field (the first one too) and hide when it loses focus. In menus the D-pad moves focus; there's no cursor for the stick.
- Sleep and Home while the game renders. On the Odin 3, in a world and on the title screen: press power, wait 30 s, wake; press Home, wait, come back through Recents; both with the dock and with Bluetooth audio on. Expect the same session, the picture back, no `VK_ERROR_SURFACE_LOST_KHR`/`NATIVE_WINDOW_IN_USE` and no "Failed to acquire/present image" in logcat or the game log, input still working, and no `:game` death. Also leave it in the background for 10 min to see whether the low-memory killer takes it.
- Leaving during a first launch: press Play on a fresh instance, press Home while it downloads, come back. Expect the game to start, not a "Minecraft stopped" screen (Android may block the launch from the background).
- In game, change the theme or bold-text setting (the activity is recreated in place): the built-in controller and mouse capture should keep working.
- Leaving the game killed: swipe Cofre away from Recents mid-game (expect "You quit the game"), and update the app mid-game (expect "Cofre was updated"). Kill `:game` while away and reopen Cofre: it shows why the game stopped instead of starting it again.
- Join a server known only by an SRV record (`_minecraft._tcp`): the game connects on the record's port (`-Dext.net.resolvPath`).
- A world and a mod file with an emoji in the name: Quick Play and the mod list should show them unmangled (JNI passes arguments as modified UTF-8).
