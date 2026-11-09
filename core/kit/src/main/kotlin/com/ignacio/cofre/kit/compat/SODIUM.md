Verified 2026-11-08 using Modrinth API, the downloaded jar, its constant pool and javap -c -p.

- Project AANobbMI, version DmnNKsfS, mc26.2-0.9.2-neoforge.
- URL: https://cdn.modrinth.com/data/AANobbMI/versions/DmnNKsfS/sodium-neoforge-0.9.2%2Bmc26.2.jar
- SHA-256: 8364f21b122ffe975392dea12eba0b3bc90609c982113e92dc2e30df2c335805
- SHA-512: 15fb941bd58abe3e57b53eb91f070f00e9a5178c718d50cbae588986e4cd8349c5a2ef4f6c84cc4887320d85ec561aa2094214723f502fddd3aeb493c80b5c39

Only two outer-jar classes reference either requested OpenGL namespace:

| Class under net/caffeinemc/mods/sodium/client/ | References |
|---|---|
| compatibility/environment/GlContextInfo | org/lwjgl/opengl/GL11C |
| compatibility/workarounds/nvidia/NvidiaWorkarounds | GL, GL32C, GLCapabilities, KHRDebug |

No com/mojang/blaze3d/opengl references were found. GlContextInfo.create calls glGetString,
but no other class's constant pool invokes create in this artifact. NvidiaWorkarounds'
GL calls live in applyContextChanges$Windows, behind both NVIDIA and Windows checks.
Its caller is PostLaunchChecks.onContextInitialized. These are retained GL compatibility
paths, not a reason to block the Vulkan-compatible Sodium release.

The scanner excludes exactly these two class paths only when the whole jar matches the
verified SHA-512. Renaming a jar or using Sodium's package name cannot bypass the scan.
The production Kotlin scanner returned usages=[] and the two expected exclusions on the
real jar. Synthetic fixtures cover both namespaces, strings, arrays, malformed pools,
and a repackaged Sodium path that must remain flagged. Static scanning cannot establish
execution reachability for arbitrary mods; a finding requires review or disabling the mod.
