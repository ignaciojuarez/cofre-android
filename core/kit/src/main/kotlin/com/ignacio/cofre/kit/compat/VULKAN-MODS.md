# Which mods run on Vulkan

Checked 2026-11-09: the newest NeoForge 26.2 jar of each mod from Modrinth, outer and `META-INF/jarjar` classes,
scanned for `org/lwjgl/opengl/` and `com/mojang/blaze3d/opengl/` references, and for 26.2's backend-neutral GPU API
(`com/mojang/blaze3d/systems/GpuDevice`, `GpuBackend`, `CommandEncoder`, `RenderPass`, `com/mojang/blaze3d/vulkan/`).

| Mod (mod id) | Version | GL classes | Neutral API | Verdict |
|---|---|---|---|---|
| Sodium (`sodium`) | 0.9.2+mc26.2 | 7 (GlDeviceMixin, GLDrawContext, GlSurfaceMixin, NvidiaWorkarounds…) | yes, plus `vulkan/VulkanDevice` and VK draw batches | works ≥ 0.9.0 |
| ImmediatelyFast (`immediatelyfast`) | 1.16.5+26.2 | 3 (mixins into GlCommandEncoder, GlSurface) | yes | works ≥ 1.16.0 |
| ModernFix mVUS (`modernfix`) | 5.27.21+mc26.2 | 1 (AsyncLoadingScreen) | no | works ≥ 5.27.18 on 26.2 |
| Distant Horizons (`distanthorizons`) | 3.3.3 | 15 (`render/openGl/…`, LWJGL3Service) | yes | works ≥ 3.1.0 |
| Entity Culling, FerriteCore, Controlify, YACL | current | 0 | — | works |
| Iris (`iris`) | 1.11.4+mc26.2 | 106 | yes | broken (rule) |

What this shows:
- Vulkan-ready mods keep GL code for the GL backend, so a reference scan alone flags them. They're vouched for in
  `known-mods.json` by mod id and minimum version (never by display name, file name or hash, which break on updates).
- Iris also uses the neutral API, so "uses the neutral API" can't clear a mod; it only softens an unknown jar's
  finding from "likely to crash" to "may not work". Known-bad rules always win.
- ModernFix shows the reverse: a GL-only finding can still be harmless. That's why unknown findings are warnings.
