# runtime

The game side of Cofre: Vulkan check, JRE install, the `:game` process, the GLFW shim.

## API for the app
| Call | What |
|---|---|
| `VulkanSupport.check(): VulkanReport` | Driver vs 26.2's needs: `suitable`, `missing`, per-extension/feature maps. Logs `[Cofre] vulkan check suitable=… missing=[…]` |
| `JreManager(context).install(): File` | Unpacks the bundled JRE into `filesDir/runtimes/jre-25` (atomic, version-stamped). `isInstalled()`, `bundledVersion()` |
| `LaunchSpec(...).write(file)` | javaHome, classpath, jvmArgs, mainClass, gameArgs, gameDir, env, logFile, exitFile, builtInController |
| `startActivity(GameActivity.intent(context, specFile))` | Runs the game in `:game`. Adds the GLFW shim first on the classpath and the LWJGL/java.home properties |
| `ExitRecord.read(exitFile)` | `code` + `timeMillis` when the JVM ended; `null` after a crash or kill |
| `GameFiles(filesDir/minecraft)` | Shared store (`versions/`, `libraries/`, `assets/`). `install(id)` downloads client, libraries (Linux arm64 rules, no desktop natives) and assets through kit's queue, hash-checked; `progress: StateFlow<Progress>` |
| `NeoForgeInstaller(context, files).install("26.2.0.93")` | Installer jar (Maven SHA-1), its libraries, client processors in the `:installer` process (`InstallerService`), output hashes, then `versions/neoforge-26.2.0.93/*.json`. Returns the version id |
| `GameLaunch(context).prepare(versionId, gameDir, GameAccount(...), heapMB, extraJvmArgs, extraGameArgs)` | Installs what's missing, writes `preferredGraphicsBackend:vulkan` and (NeoForge) `earlyWindowControl = false`, returns the spec file for `GameActivity.intent`. Adds `--userType msa --graphicsBackend vulkan`, then `extraGameArgs` (Quick Play). `heapMB` defaults to `JavaHeap.autoMB` |
| `GameLaunch(context).exit(specFile): GameExit` | `ExitRecord`, game output, newest crash report or `hs_err`, kit's `GameExitReport`; `summary` is the line to show ("Vulkan device not suitable. This device's Vulkan driver is missing: …") |

Debug builds: `RuntimeDebugActivity` has buttons for "Install NeoForge 26.2", "Launch vanilla 26.2" and "Launch NeoForge 26.2" (`--es action install|vanilla|neoforge`), using `DevAccount` (offline `Dev`, token `0`; debug source set only). Probes: `--es main hello|vulkan`, `--es args "exit=3"`, `--ez reinstall true`, `--ez checkOnly true`.

## JRE packaging
`scripts/dev/fetch-dev-jre.sh` (dev only) writes `.work/dev-jre/jre-25.tar.xz`; debug builds bundle it. Any build can use `-Pcofre.jre=<tar.xz>` instead (the natives release's `jre25-android-arm64.tar.xz`). No tarball → no JRE asset; the build still works.

## Verified (2026-11-09)
- Emulator (Android 17) and OnePlus 5 (Android 9): Vulkan report, JRE install, HotSpot 25 boots in `:game`, exit codes from `main` returning and from `System.exit`.
- `:runtime:glfw-shim:test`: every GLFW member the 26.2 client jar uses resolves.
- Emulator: NeoForge 26.2.0.93 installs (PROCESS_MINECRAFT_JAR in `:installer`, exit 0, ~12 s). Vanilla and NeoForge 26.2 launch up to LWJGL's native load (`Failed to locate library: liblwjgl.so`, diagnosed MissingLibrary) until the natives release lands; FML 11.0.16 boots with no class or module errors.

## Needs the real device (Odin 3)
- Vulkan probe through the shim (`--es main vulkan`): needs the LWJGL natives release and a suitable driver.
- The game's first frame; swapchain rebuild after sleep (`surfaceDestroyed` → iconify + blocked `glfwCreateWindowSurface`).
- Pointer capture with a real mouse; the built-in controller layout on the Odin pad (A/B swap in Xbox mode, back paddles); look speed.
- Touch coordinates if the surface gets a fixed lower resolution (they're in view pixels today).
- Clipboard is in-process only; no IME/preedit; no virtual cursor for the stick in menus (Controlify draws one).
