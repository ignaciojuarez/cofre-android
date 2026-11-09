package com.ignacio.cofre.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The design gallery: every component and state, for the app's debug screen and the screenshot tests.

/** Gallery pages. */
public enum class GallerySection(public val title: String) {
    Tokens("Tokens"),
    Buttons("Buttons"),
    Rows("Rows"),
    Inputs("Inputs"),
    Feedback("Feedback"),
    Icons("Icons"),
    Overlays("Overlays"),
    Controller("Controller"),
}

/** Every component in every state, one section at a time. The app shows it as a debug screen. */
@Composable
public fun DesignGallery(modifier: Modifier = Modifier) {
    var section by rememberSaveable { mutableStateOf(GallerySection.Tokens) }
    DesignGalleryPage(section, { section = it }, modifier)
}

@Composable
internal fun DesignGalleryPage(
    section: GallerySection,
    onSection: (GallerySection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = GallerySection.entries
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pageBackground()
            .controllerActions(
                ControllerAction(Pad.L1, "Sections") { onSection(sections.cycle(section, -1)) },
                ControllerAction(Pad.R1, "Sections") { onSection(sections.cycle(section, 1)) },
            ),
    ) {
        CompositionLocalProvider(LocalWidthClass provides WidthClass.of(maxWidth)) {
            Column(Modifier.fillMaxSize()) {
                ColumnHeader("Design system") { Meta("${sections.indexOf(section) + 1} of ${sections.size}") }
                ChipGroup(section, onSection, sections.map { it to it.title }, Modifier.padding(horizontal = 16.dp))
                ScreenFocus(Modifier.weight(1f)) {
                    PaneScroll { GallerySectionContent(section) }
                }
                ControllerHintBar()
            }
        }
    }
}

@Composable
internal fun GallerySectionContent(section: GallerySection) {
    when (section) {
        GallerySection.Tokens -> TokensPage()
        GallerySection.Buttons -> ButtonsPage()
        GallerySection.Rows -> RowsPage()
        GallerySection.Inputs -> InputsPage()
        GallerySection.Feedback -> FeedbackPage()
        GallerySection.Icons -> IconsPage()
        GallerySection.Overlays -> OverlaysPage()
        GallerySection.Controller -> ControllerPage()
    }
}

/** Two columns on wide screens, one on compact. */
@Composable
private fun Columns(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    if (CofreTheme.widthClass == WidthClass.Compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            left()
            right()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
        }
    }
}

/** The focus ring as it looks on a focused control, drawn without focus for the gallery. */
private fun Modifier.ringPreview(accent: Color): Modifier = drawWithContent {
    drawContent()
    drawOutline(accent, CofreBlock.focusRing, -(CofreBlock.focusRing + CofreBlock.focusOffset).toPx())
}

@Composable
private fun TokensPage() {
    val c = CofreTheme.colors
    val t = CofreTheme.type
    Columns(
        left = {
            ListSection(label = "Surfaces") {
                Swatches(listOf("page" to c.page, "side" to c.side, "panel" to c.panel, "raised" to c.raised, "button" to c.button, "well" to c.well))
                Swatches(listOf("bevel" to c.bevel, "lip" to c.lip, "outline" to c.outline, "face" to c.face, "faceBevel" to c.faceBevel, "faceLip" to c.faceLip))
            }
            ListSection(label = "Ink and tones") {
                Swatches(listOf("ink" to c.ink, "inkSoft" to c.inkSoft, "muted" to c.muted, "accent" to c.accent, "onFace" to c.onFace))
                Swatches(Tone.entries.drop(1).map { it.name to c.tint(it) })
            }
            ListSection(label = "Blocks") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(BlockKind.Panel, BlockKind.Raised, BlockKind.Well, BlockKind.Button, BlockKind.Primary, BlockKind.Tinted(Tone.Success))
                        .forEach { Block(Modifier.size(52.dp, 40.dp), kind = it) {} }
                    Block(Modifier.size(52.dp, 40.dp), selected = true) {}
                }
            }
        },
        right = {
            ListSection(label = "Type") {
                CofreText("Display 34", t.display)
                CofreText("Title 26", t.title)
                CofreText("Header 22 · Title2 21", t.header)
                CofreText("Button 19 · 15 · 13", t.buttonL)
                CofreText("Body 15: Geist with relaxed leading for paragraphs that wrap onto a second line.", t.body)
                CofreText("Row title 14", t.rowTitle)
                CofreText("Secondary 13 and caption 11", t.secondary, color = c.inkSoft)
                CofreText("Label 11 · mono uppercase", t.label, color = c.muted)
                CofreText("12:04:01 [code] 11 mono", t.code, color = c.inkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CofreText("142", t.stat)
                    Badge("badge 11")
                    CofreText("Chip 12", t.chip)
                    CofreText("Nav 16", t.nav)
                }
            }
        },
    )
}

@Composable
private fun Swatches(colors: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { (name, color) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(28.dp).background(color).outline())
                CofreText(name, CofreTheme.type.label, color = CofreTheme.colors.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ButtonsPage() {
    val accent = CofreTheme.colors.accent
    Columns(
        left = {
            ListSection(label = "Roles") {
                BlockButton("Play", {}, role = ButtonRole.Primary, size = ButtonSize.Large, icon = PixelGlyph.Play)
                BlockButton("Choose file", {}, icon = PixelGlyph.Paper)
                BlockButton("Delete instance", {}, role = ButtonRole.Danger)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockButton("Ghost", {}, role = ButtonRole.Ghost, fullWidth = false)
                    BlockButton("Remove", {}, role = ButtonRole.GhostDanger, fullWidth = false)
                }
            }
            ListSection(label = "Sizes") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlockButton("Small", {}, size = ButtonSize.Small)
                    BlockButton("Small primary", {}, role = ButtonRole.Primary, size = ButtonSize.Small)
                    IconButton(PixelGlyph.Gear, "Settings", {})
                    IconButton(PixelGlyph.Cross, "Close", {}, size = ButtonSize.Regular)
                }
            }
        },
        right = {
            ListSection(label = "States") {
                BlockButton("Focused", {}, Modifier.ringPreview(accent), role = ButtonRole.Primary)
                BlockButton("Installing", {}, loading = true)
                BlockButton("Disabled", {}, enabled = false)
                BlockButton("Disabled primary", {}, role = ButtonRole.Primary, enabled = false)
            }
        },
    )
}

@Composable
private fun RowsPage() {
    var toggle by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(false) }
    val accent = CofreTheme.colors.accent
    Columns(
        left = {
            ListSection(label = "Instances") {
                BlockRow(
                    "Survival 26.2",
                    subtitle = "NeoForge 26.2.0.93 · 42 mods",
                    selected = true,
                    onClick = {},
                    leading = { PixelIcon(PixelGlyph.Grass, size = 26.dp) },
                    trailing = { Chevron() },
                )
                BlockRow(
                    "Creative flat",
                    onClick = {},
                    subtitleContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge("NeoForge", tone = Tone.NeoForge)
                            CofreText("Played 2 h ago", CofreTheme.type.caption, color = CofreTheme.colors.muted)
                        }
                    },
                    leading = { PixelIcon(PixelGlyph.Ore, size = 26.dp, tint = OreTint.Amethyst.color) },
                    trailing = { Meta("2 h") },
                )
                BlockRow("Focused row", Modifier.ringPreview(accent), subtitle = "Ring 2 dp outside", onClick = {}, leading = { PixelIcon(PixelGlyph.Chest, size = 26.dp) })
                BlockRow("Old snapshot", subtitle = "Dimmed", dimmed = true, leading = { PixelIcon(PixelGlyph.Cobble, size = 26.dp) })
                BlockRow("Big row", big = true, trailing = { Badge("Done", tone = Tone.Success) })
            }
        },
        right = {
            ListSection(label = "Settings") {
                ToggleRow("Controlify", toggle, { toggle = it }, subtitle = "Controller support in game")
                ToggleRow("Distant Horizons", off, { off = it })
                ToggleRow("Disabled", false, {}, enabled = false)
                ValueRow("Renderer", "Vulkan", {})
                ValueRow("Memory", "6 GB", {}, accent = true)
                ValueRow("Java", "25.0.5", {}, chevron = false)
            }
            ListSection(label = "Radio rows") {
                BlockRow("Release", onClick = {}, leading = { Radio(true) }, trailing = { Meta("26.2", tone = Tone.Success) })
                BlockRow("Snapshot", onClick = {}, leading = { Radio(false) })
            }
        },
    )
}

@Composable
private fun InputsPage() {
    var segment by remember { mutableIntStateOf(1) }
    var small by remember { mutableIntStateOf(0) }
    var on by remember { mutableStateOf(true) }
    var memory by remember { mutableFloatStateOf(6f) }
    var version by remember { mutableStateOf("26.2") }
    var chip by remember { mutableStateOf("All") }
    var filter by remember { mutableStateOf(true) }
    val name = rememberTextFieldState("Survival")
    val search = rememberTextFieldState()
    val secret = rememberTextFieldState("hunter2")
    val args = rememberTextFieldState("-Xmx6G -XX:+UseZGC\n-Dorg.lwjgl.system.allocator=system")
    val bad = rememberTextFieldState("my world?")
    Columns(
        left = {
            ListSection(label = "Segmented") {
                Segmented(segment, { segment = it }, listOf(0 to "Release", 1 to "Snapshot", 2 to "All"))
                Segmented(small, { small = it }, listOf(0 to "Mods", 1 to "Packs", 2 to "Shaders"), small = true)
            }
            ListSection(label = "Slider and switch") {
                BlockSlider("Memory", memory, { memory = it }, 2f..12f, { "${it.toInt()} GB" }, note = "Default 4 GB", defaultValue = 4f)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlockSwitch(on, { on = it })
                    BlockSwitch(!on, { on = !it })
                    BlockSwitch(true, {}, enabled = false)
                }
            }
            ListSection(label = "Select") {
                SelectButton("Version", version, { version = it }, listOf("26.2" to "26.2", "26.1.2" to "26.1.2"))
                BlockRow("Loader version", trailing = {
                    SelectButton("Loader", "93", {}, listOf("93" to "26.2.0.93"), compact = true)
                })
            }
        },
        right = {
            ListSection(label = "Fields") {
                Field(name, "Name")
                Field(search, "Search", placeholder = "Search mods", kind = FieldKind.Search, trailingNote = "Y")
                Field(secret, "Password", kind = FieldKind.Secure)
                Field(args, "JVM arguments", kind = FieldKind.Mono)
                Field(bad, "World name", error = "Names can't contain ?")
            }
            ListSection(label = "Chips and radio") {
                ChipGroup(chip, { chip = it }, listOf("All", "Installed", "Updates", "Disabled").map { it to it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip("Client only", filter, { filter = it })
                    Chip("Server", !filter, { filter = !it })
                    Radio(true)
                    Radio(false)
                }
            }
        },
    )
}

@Composable
private fun FeedbackPage() {
    Columns(
        left = {
            ListSection(label = "Badges") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tone.entries.take(5).forEach { Badge(it.name, tone = it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Tone.Fabric, Tone.Forge, Tone.NeoForge, Tone.Quilt).forEach { Badge(it.name, tone = it) }
                    Badge("Big", tone = Tone.Accent, big = true)
                }
            }
            ListSection(label = "Banners") {
                Banner("Iris needs OpenGL; it's off under Vulkan", PixelGlyph.Bolt)
                Banner("All mods are up to date", PixelGlyph.Check, tone = Tone.Success)
                Banner("Download failed", PixelGlyph.Cross, tone = Tone.Danger) {
                    BlockButton("Retry", {}, size = ButtonSize.Small)
                }
                Banner("Controller connected", PixelGlyph.Key, tone = Tone.Neutral)
            }
            ListSection(label = "Progress") {
                ProgressBar(0.62f)
                ProgressBar(0.3f, tone = Tone.Accent, small = true)
                ProgressBar(null, label = "Resolving")
                StepBar(listOf("Account", "Java", "Version", "Mods", "Done"), current = 2)
            }
        },
        right = {
            ListSection(label = "Stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stat("142", "mods", Modifier.weight(1f))
                    Stat("3", "updates", Modifier.weight(1f), tone = Tone.Warn)
                    Stat("24 ms", "ping", Modifier.weight(1f), tone = Tone.Success)
                }
            }
            Block(Modifier.fillMaxWidth().height(220.dp), kind = BlockKind.Well) {
                EmptyState("No instances", "Make one to start playing.", PixelGlyph.Chest) {
                    BlockButton("New instance", {}, role = ButtonRole.Primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner()
                Spinner(size = 22.dp)
                CofreText("Spinner", CofreTheme.type.label, color = CofreTheme.colors.muted)
            }
        },
    )
}

@Composable
private fun IconsPage() {
    Columns(
        left = {
            ListSection(label = "Pixel icons") {
                PixelGlyph.entries.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { g ->
                            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                PixelIcon(g, size = 28.dp)
                                CofreText(g.name, CofreTheme.type.label, color = CofreTheme.colors.muted, maxLines = 1)
                            }
                        }
                    }
                }
            }
            ListSection(label = "Ore tints") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OreTint.entries.forEach { PixelIcon(PixelGlyph.Ore, size = 28.dp, tint = it.color) }
                    AppIcon(size = 28.dp)
                }
            }
        },
        right = {
            ListSection(label = "Pixel scene") {
                PixelScene(23, Modifier.fillMaxWidth().aspectRatio(240f / 110f).outline(), width = 240, height = 110)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 7, 11).forEach { PixelScene(it, Modifier.weight(1f).aspectRatio(3f / 2f).outline()) }
                }
            }
        },
    )
}

private val SampleLog = listOf(
    LogLine("12:04:01", "launch", "Java 25.0.5 · 6 GB heap"),
    LogLine("12:04:02", "vulkan", "Adreno 830 · Vulkan 1.3.284", LogLevel.Success),
    LogLine("12:04:03", "neoforge", "Loading 42 mods"),
    LogLine("12:04:05", "mods", "Iris needs OpenGL; skipped", LogLevel.Warning),
    LogLine("12:04:09", "game", "Failed to load options.txt key", LogLevel.Error),
)

private val SampleGallery = listOf(
    GalleryItem("https://example.invalid/1", "Video", video = true),
    GalleryItem("https://example.invalid/2", "Sunset, image 1 of 3"),
    GalleryItem("https://example.invalid/3", "Mountains, image 2 of 3"),
    GalleryItem("https://example.invalid/4", "Night, image 3 of 3"),
)

@Composable
private fun OverlaysPage() {
    Columns(
        left = {
            ListSection(label = "Sheet · card") {
                SheetPanel(Modifier.fillMaxWidth(), compact = false, hugsContent = true) {
                    SheetHeader("New instance", {}) { BlockButton("Create", {}, role = ButtonRole.Primary, size = ButtonSize.Small) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ValueRow("Version", "26.2", {})
                        ValueRow("Loader", "NeoForge", {})
                    }
                }
            }
            ListSection(label = "Sheet · bottom") {
                SheetPanel(compact = true, hugsContent = true) {
                    SheetHeader("Filters", {})
                    CofreText("Bottom sheet on compact widths.", CofreTheme.type.secondary, Modifier.padding(16.dp), color = CofreTheme.colors.inkSoft)
                }
            }
        },
        right = {
            ListSection(label = "Column header") {
                Block(Modifier.fillMaxWidth()) {
                    CompositionLocalProvider(LocalColumnBack provides {}) {
                        ColumnHeader("Sodium") { IconButton(PixelGlyph.Gear, "Options", {}) }
                    }
                }
            }
            ListSection(label = "Log") { LogView(SampleLog, Modifier.fillMaxWidth().height(130.dp), follow = false) }
            ListSection(label = "Gallery") { GalleryGrid(SampleGallery, {}) }
        },
    )
}

@Composable
private fun ControllerPage() {
    Columns(
        left = {
            ControllerStyle.entries.forEach { style ->
                val state = remember(style) { ControllerState().apply { this.style = style } }
                ListSection(label = "Hint bar · ${style.name}") {
                    CompositionLocalProvider(LocalController provides state) {
                        Box(Modifier.controllerActions(ControllerAction(Pad.Y, "Search"), ControllerAction(Pad.L1, "Sections"), ControllerAction(Pad.R1, "Sections"))) {
                            ControllerHintBar(alwaysShow = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Pad.entries.forEach { ControllerGlyph(it) } }
                    }
                }
            }
        },
        right = {
            ListSection(label = "Swapped confirm/back · Nintendo") {
                val state = remember { ControllerState().apply { style = ControllerStyle.Nintendo; swapConfirmBack = true } }
                CompositionLocalProvider(LocalController provides state) { ControllerHintBar(alwaysShow = true) }
            }
            ListSection(label = "Focus ring") {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val accent = CofreTheme.colors.accent
                    BlockButton("Focused", {}, Modifier.ringPreview(accent), size = ButtonSize.Small)
                    Box(Modifier.ringPreview(accent)) { BlockSwitch(true, {}) }
                    Box(Modifier.ringPreview(accent)) { Chip("Chip", false, {}) }
                }
            }
        },
    )
}
